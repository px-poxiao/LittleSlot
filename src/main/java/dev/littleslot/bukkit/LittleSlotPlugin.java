package dev.littleslot.bukkit;

import dev.littleslot.core.Admission;
import dev.littleslot.core.AuditEvent;
import dev.littleslot.core.Decision;
import dev.littleslot.core.JdbcSlotRepository;
import dev.littleslot.core.SlotService;
import dev.littleslot.core.SlotSettings;
import dev.littleslot.oauth.AuthorizationSession;
import dev.littleslot.oauth.DeviceCodeProvider;
import dev.littleslot.oauth.OAuthProvider;
import dev.littleslot.oauth.PublicCodeProvider;
import dev.littleslot.oauth.SelfHostedCodeProvider;
import dev.littleslot.oauth.OAuthException;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class LittleSlotPlugin extends JavaPlugin implements Listener, CommandExecutor {
    private final Map<UUID, PlayerSession> sessions = new ConcurrentHashMap<UUID, PlayerSession>();
    private final Map<UUID, AuthorizationSession> authorizations = new ConcurrentHashMap<UUID, AuthorizationSession>();
    private final AtomicBoolean auditPolling = new AtomicBoolean();
    private final AtomicBoolean recoveryPolling = new AtomicBoolean();
    private ExecutorService workers;
    private SlotService slots;
    private JdbcSlotRepository repository;
    private MojangLookup mojang;
    private OAuthProvider oauth;
    private MessageCatalog messages;
    private String scope;
    private long lastAuditId;
    private long nextRecoveryAt;

    @Override public void onEnable() {
        saveDefaultConfig();
        try {
            messages = new MessageCatalog(this);
            scope = getConfig().getString("scope", "local");
            SlotSettings settings = new SlotSettings(getConfig().getInt("default-limit", 2),
                    Duration.ofDays(getConfig().getLong("ownership-days", 30)),
                    Duration.ofDays(getConfig().getLong("self-release-days", 7)));
            String type = getConfig().getString("database.type", "sqlite").toLowerCase(Locale.ROOT);
            if ("sqlite".equals(type)) {
                File file = new File(getDataFolder(), getConfig().getString("database.sqlite-file", "slots.db"));
                repository = new JdbcSlotRepository("jdbc:sqlite:" + file.getAbsolutePath(), null, null, JdbcSlotRepository.Dialect.SQLITE);
            } else if ("mysql".equals(type)) {
                repository = new JdbcSlotRepository(getConfig().getString("database.mysql.url"),
                        getConfig().getString("database.mysql.user"), getConfig().getString("database.mysql.password"),
                        JdbcSlotRepository.Dialect.MYSQL);
            } else throw new IllegalArgumentException("database.type must be sqlite or mysql");
            workers = Executors.newFixedThreadPool(4, task -> {
                Thread thread = new Thread(task, "LittleSlot-worker");
                thread.setDaemon(true);
                return thread;
            });
            slots = new SlotService(repository, settings, Clock.systemUTC());
            lastAuditId = repository.latestAuditId(scope);
            mojang = new MojangLookup(URI.create("https://api.minecraftservices.com/minecraft/profile/lookup/name/"),
                    getConfig().getInt("premium-lookup-timeout-millis", 5000));
            String mode = getConfig().getString("oauth.mode", "device");
            if ("device".equalsIgnoreCase(mode) && !getConfig().getString("oauth.client-id", "").isEmpty())
                oauth = new DeviceCodeProvider(getConfig().getString("oauth.client-id"), workers);
            else if ("public".equalsIgnoreCase(mode) && !getConfig().getString("oauth.public-api-key", "").isEmpty())
                oauth = new PublicCodeProvider(URI.create(getConfig().getString("oauth.public-base-url")),
                        getConfig().getString("oauth.public-api-key"), workers);
            else if ("code".equalsIgnoreCase(mode) && !getConfig().getString("oauth.client-id", "").isEmpty())
                oauth = new SelfHostedCodeProvider(getConfig().getString("oauth.client-id"),
                        getConfig().getString("oauth.client-secret"), URI.create(getConfig().getString("oauth.code.redirect-uri")),
                        getConfig().getString("oauth.code.listen-host", "127.0.0.1"),
                        getConfig().getInt("oauth.code.listen-port", 8765), workers,
                        key -> message("oauth-code-" + key));
            Bukkit.getPluginManager().registerEvents(this, this);
            getCommand("littleslot").setExecutor(this);
            Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
            Bukkit.getScheduler().runTaskTimer(this, this::pollAudit, 40L, 40L);
            getLogger().info("LittleSlot ready; premium compatibility="
                    + getConfig().getBoolean("premium-compatibility", false) + ", scope=" + scope);
        } catch (Exception error) {
            getLogger().severe("LittleSlot startup failed: " + error.getMessage());
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    @Override public void onDisable() {
        for (AuthorizationSession auth : authorizations.values()) auth.cancel();
        authorizations.clear();
        if (oauth != null) oauth.close();
        sessions.clear();
        if (workers != null) workers.shutdownNow();
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        sessions.put(event.getPlayer().getUniqueId(), new PlayerSession(event.getPlayer().getUniqueId()));
        beginJoinCheck(event.getPlayer());
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        PlayerSession session = sessions.remove(event.getPlayer().getUniqueId());
        if (session != null) {
            AuthorizationSession auth = authorizations.remove(session.connection);
            if (auth != null) auth.cancel();
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        if (now >= nextRecoveryAt) checkTemporaryRecovery(now);
        long timeout = getConfig().getLong("binding-timeout-seconds", 300) * 1000L;
        long reminder = Math.max(10, getConfig().getLong("reminder-seconds", 30));
        for (PlayerSession session : sessions.values()) {
            Player player = Bukkit.getPlayer(session.gameUuid);
            if (player == null || !player.isOnline() || !session.restricted()) continue;
            if (now - session.restrictedSince >= timeout) {
                session.state = PlayerSession.State.DENIED;
                player.kickPlayer(message("kick-binding-timeout"));
                continue;
            }
            if (session.state == PlayerSession.State.BINDING && ((now - session.restrictedSince) / 1000) % reminder == 0) {
                player.sendMessage(message("binding-reminder", "target",
                        session.bindUrl == null ? message("bind-command-hint") : session.bindUrl));
            }
        }
    }

    private void beginJoinCheck(Player player) {
        PlayerSession session = sessions.get(player.getUniqueId());
        if (session == null || session.state != PlayerSession.State.CHECKING) return;
        if (!getConfig().getBoolean("premium-compatibility", false)) {
            resolveLittleSkin(player.getUniqueId(), session.connection);
            return;
        }
        final UUID gameUuid = player.getUniqueId(), connection = session.connection;
        final String playerName = player.getName();
        workers.execute(() -> {
            try {
                // This is deliberately uncached: a previous timeout choice must not exempt the next join.
                UUID premiumUuid = mojang.byName(playerName);
                try { repository.clearPremiumTimeoutChoice(scope, gameUuid); }
                catch (Exception clearFailure) {
                    // The record is historical only. Its cleanup must not turn a valid premium lookup into a denial.
                    getLogger().warning("Cannot clear old premium timeout record: " + clearFailure.getMessage());
                }
                main(() -> {
                    PlayerSession active = current(gameUuid, connection);
                    if (active == null || active.state != PlayerSession.State.CHECKING) return;
                    if (gameUuid.equals(premiumUuid)) {
                        active.state = PlayerSession.State.BYPASS;
                        Player target = Bukkit.getPlayer(gameUuid);
                        if (target != null) target.sendMessage(message("premium-admitted"));
                    } else resolveLittleSkin(gameUuid, connection);
                });
            } catch (SocketTimeoutException timeout) {
                main(() -> {
                    PlayerSession active = current(gameUuid, connection);
                    if (active == null || active.state != PlayerSession.State.CHECKING) return;
                    active.state = PlayerSession.State.CHOOSING;
                    Player target = Bukkit.getPlayer(gameUuid);
                    if (target != null) target.sendMessage(message("premium-lookup-timeout-choice"));
                });
            } catch (Exception error) {
                getLogger().warning("Mojang lookup failed for " + playerName + ": " + error.getMessage());
                main(() -> {
                    PlayerSession active = current(gameUuid, connection);
                    if (active != null && active.state == PlayerSession.State.CHECKING) {
                        active.state = PlayerSession.State.DENIED;
                        Player target = Bukkit.getPlayer(gameUuid);
                        if (target != null) target.kickPlayer(message("kick-premium-lookup-error"));
                    }
                });
            }
        });
    }

    private void resolveLittleSkin(UUID gameUuid, UUID connection) {
        PlayerSession session = current(gameUuid, connection);
        if (session == null || (session.state != PlayerSession.State.CHECKING && session.state != PlayerSession.State.CHOOSING)) return;
        session.state = PlayerSession.State.RESOLVING;
        workers.execute(() -> {
            try {
                Admission result = slots.join(scope, gameUuid);
                main(() -> applyAdmission(gameUuid, connection, result));
            } catch (Exception error) {
                getLogger().warning("Admission database error: " + error.getMessage());
                main(() -> {
                    Player target = Bukkit.getPlayer(gameUuid);
                    if (current(gameUuid, connection) != null && target != null) target.kickPlayer(message("kick-database-unavailable"));
                });
            }
        });
    }

    private void applyAdmission(UUID gameUuid, UUID connection, Admission admission) {
        PlayerSession session = current(gameUuid, connection);
        Player player = Bukkit.getPlayer(gameUuid);
        if (session == null || player == null || !player.isOnline()
                || (session.state != PlayerSession.State.RESOLVING && session.state != PlayerSession.State.BINDING)) return;
        Decision decision = admission.decision();
        if (admission.allowed()) {
            session.uid = admission.uid();
            session.admittedAt = System.currentTimeMillis();
            session.state = PlayerSession.State.ADMITTED;
            player.sendMessage(message("admitted", "used", Integer.toString(admission.used()), "limit",
                    admission.limit() == -1 ? message("unlimited") : Integer.toString(admission.limit())));
        } else if (decision == Decision.BIND_REQUIRED || decision == Decision.SNAPSHOT_EXPIRED) {
            if (!getConfig().getBoolean("binding-required", true)) {
                session.state = PlayerSession.State.BYPASS;
                player.sendMessage(message("guest-allowed"));
                return;
            }
            session.state = PlayerSession.State.BINDING;
            player.sendMessage(message("binding-started"));
            beginBind(player, session);
        } else if (decision == Decision.ACCOUNT_MISMATCH) {
            session.state = PlayerSession.State.DENIED;
            player.kickPlayer(message("kick-account-mismatch"));
        } else {
            session.state = PlayerSession.State.DENIED;
            player.kickPlayer(message(decision == Decision.BLOCKED ? "kick-blocked"
                    : decision == Decision.FULL ? "kick-full" : "kick-ownership-mismatch"));
        }
    }

    private void beginBind(Player player, PlayerSession session) {
        if (oauth == null) {
            player.sendMessage(message("oauth-unconfigured"));
            return;
        }
        AuthorizationSession previous = authorizations.remove(session.connection);
        if (previous != null) previous.cancel();
        AuthorizationSession auth = oauth.start();
        authorizations.put(session.connection, auth);
        UUID gameUuid = player.getUniqueId(), connection = session.connection;
        auth.verificationUrl.whenComplete((url, error) -> main(() -> {
            if (authorizations.get(connection) != auth) return;
            PlayerSession active = current(gameUuid, connection);
            if (active == null || active.state != PlayerSession.State.BINDING) return;
            if (error == null) {
                active.bindUrl = url;
                Player target = Bukkit.getPlayer(gameUuid);
                if (target != null) target.sendMessage(message("verification-link", "url", url));
            } else handleOAuthFailure(gameUuid, connection, error);
        }));
        auth.result.whenComplete((verified, error) -> {
            if (authorizations.get(connection) != auth) return;
            if (error != null) { main(() -> handleOAuthFailure(gameUuid, connection, error)); return; }
            workers.execute(() -> {
                try {
                    if (authorizations.get(connection) != auth) return;
                    PlayerSession active = current(gameUuid, connection);
                    if (active == null || active.state != PlayerSession.State.BINDING) return;
                    // The OAuth result belongs to this local connection; the public backend never sees the UUID.
                    Admission result = slots.verifyAndJoin(scope, gameUuid, verified);
                    main(() -> {
                        if (authorizations.remove(connection, auth)) applyAdmission(gameUuid, connection, result);
                    });
                } catch (Exception failure) {
                    getLogger().warning("OAuth result could not be stored: " + failure.getMessage());
                    main(() -> {
                        Player target = Bukkit.getPlayer(gameUuid);
                        if (current(gameUuid, connection) != null && target != null)
                            target.kickPlayer(message("kick-ownership-save-failed"));
                    });
                }
            });
        });
    }

    private void handleOAuthFailure(UUID gameUuid, UUID connection, Throwable failure) {
        PlayerSession session = current(gameUuid, connection);
        Player player = Bukkit.getPlayer(gameUuid);
        if (session == null || player == null || session.state != PlayerSession.State.BINDING) return;
        Throwable cause = failure;
        while (cause.getCause() != null) cause = cause.getCause();
        if (cause instanceof OAuthException && ("access_denied".equals(((OAuthException) cause).code())
                || "expired_token".equals(((OAuthException) cause).code())
                || "expired".equals(((OAuthException) cause).code())
                || "denied".equals(((OAuthException) cause).code()))) {
            player.sendMessage(message("oauth-cancelled"));
        } else if (getConfig().getBoolean("external-failure-temporary-allow", true)) {
            session.state = PlayerSession.State.TEMPORARY;
            getLogger().warning("OAuth unavailable; temporarily allowed session " + connection + ": " + cause.getMessage());
            player.sendMessage(message("oauth-temporary-allow"));
        } else player.sendMessage(message("oauth-failed"));
    }

    private PlayerSession current(UUID gameUuid, UUID connection) {
        PlayerSession session = sessions.get(gameUuid);
        return session != null && session.connection.equals(connection) ? session : null;
    }

    private void checkTemporaryRecovery(long now) {
        long interval = Math.max(15, getConfig().getLong("recovery-check-seconds", 60)) * 1000L;
        nextRecoveryAt = now + interval;
        if (oauth == null || !recoveryPolling.compareAndSet(false, true)) return;
        boolean any = sessions.values().stream().anyMatch(session -> session.state == PlayerSession.State.TEMPORARY);
        if (!any) { recoveryPolling.set(false); return; }
        workers.execute(() -> {
            boolean available = false;
            try { available = oauth.available(); }
            catch (Exception error) { getLogger().warning("OAuth health probe failed: " + error.getMessage()); }
            final boolean healthy = available;
            main(() -> {
                try {
                    if (!healthy) return;
                    int batch = Math.max(1, getConfig().getInt("recovery-batch-size", 8));
                    for (PlayerSession session : sessions.values()) {
                        if (batch <= 0) break;
                        if (session.state != PlayerSession.State.TEMPORARY) continue;
                        Player player = Bukkit.getPlayer(session.gameUuid);
                        if (player == null || !player.isOnline()) continue;
                        session.state = PlayerSession.State.BINDING;
                        session.restrictedSince = System.currentTimeMillis();
                        session.bindUrl = null;
                        player.sendMessage(message("recovery-binding", "seconds",
                                Long.toString(getConfig().getLong("binding-timeout-seconds", 300))));
                        batch--;
                    }
                } finally { recoveryPolling.set(false); }
            });
        });
    }

    private void main(Runnable action) {
        if (isEnabled()) Bukkit.getScheduler().runTask(this, action);
    }

    private String message(String key, String... replacements) {
        return messages.text(key, replacements);
    }

    private boolean restricted(Player player) {
        PlayerSession session = sessions.get(player.getUniqueId());
        return session != null && session.restricted();
    }

    @EventHandler public void onMove(PlayerMoveEvent event) {
        if (restricted(event.getPlayer()) && event.getTo() != null &&
                (event.getFrom().getX() != event.getTo().getX() || event.getFrom().getY() != event.getTo().getY()
                        || event.getFrom().getZ() != event.getTo().getZ())) event.setTo(event.getFrom());
    }
    @EventHandler public void onTeleport(PlayerTeleportEvent event) { if (restricted(event.getPlayer())) event.setCancelled(true); }
    @EventHandler public void onInteract(PlayerInteractEvent event) { if (restricted(event.getPlayer())) event.setCancelled(true); }
    @EventHandler public void onBreak(BlockBreakEvent event) { if (restricted(event.getPlayer())) event.setCancelled(true); }
    @EventHandler public void onPlace(BlockPlaceEvent event) { if (restricted(event.getPlayer())) event.setCancelled(true); }
    @EventHandler public void onClick(InventoryClickEvent event) { if (event.getWhoClicked() instanceof Player && restricted((Player) event.getWhoClicked())) event.setCancelled(true); }
    @EventHandler public void onDrag(InventoryDragEvent event) { if (event.getWhoClicked() instanceof Player && restricted((Player) event.getWhoClicked())) event.setCancelled(true); }
    @EventHandler public void onDrop(PlayerDropItemEvent event) { if (restricted(event.getPlayer())) event.setCancelled(true); }
    @EventHandler public void onPickup(EntityPickupItemEvent event) { if (event.getEntity() instanceof Player && restricted((Player) event.getEntity())) event.setCancelled(true); }
    @EventHandler public void onSwap(PlayerSwapHandItemsEvent event) { if (restricted(event.getPlayer())) event.setCancelled(true); }
    @EventHandler public void onDamage(EntityDamageByEntityEvent event) {
        if ((event.getDamager() instanceof Player && restricted((Player) event.getDamager()))
                || (event.getEntity() instanceof Player && restricted((Player) event.getEntity()))) event.setCancelled(true);
    }
    @EventHandler public void onChat(AsyncPlayerChatEvent event) { if (restricted(event.getPlayer())) event.setCancelled(true); }
    @EventHandler public void onPreCommand(PlayerCommandPreprocessEvent event) {
        if (!restricted(event.getPlayer())) return;
        String[] words = event.getMessage().toLowerCase(Locale.ROOT).split("\\s+");
        if (words.length == 0 || !(words[0].equals("/littleslot") || words[0].equals("/lslot"))) {
            event.setCancelled(true); return;
        }
        if (words.length > 1 && !Arrays.asList("bind", "status", "help", "release", "choose").contains(words[1])) event.setCancelled(true);
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || "help".equalsIgnoreCase(args[0])) {
            sender.sendMessage(message("help-player"));
            if (sender.hasPermission("littleslot.admin")) sender.sendMessage(message("help-admin"));
            return true;
        }
        try {
            String action = args[0].toLowerCase(Locale.ROOT);
            if ("status".equals(action) && sender instanceof Player) {
                PlayerSession session = sessions.get(((Player) sender).getUniqueId());
                sender.sendMessage(message("status", "state", session == null ? message("no-session") : session.state.name(),
                        "uid", session == null || session.uid == null ? "-" : session.uid.toString()));
                return true;
            }
            if ("bind".equals(action) && sender instanceof Player) {
                PlayerSession session = sessions.get(((Player) sender).getUniqueId());
                if (session == null || session.state != PlayerSession.State.BINDING) sender.sendMessage(message("bind-not-needed"));
                else beginBind((Player) sender, session);
                return true;
            }
            if ("choose".equals(action) && sender instanceof Player && args.length == 2) {
                Player player = (Player) sender;
                PlayerSession session = sessions.get(player.getUniqueId());
                if (session == null || session.state != PlayerSession.State.CHOOSING) {
                    sender.sendMessage(message("premium-choice-not-needed")); return true;
                }
                if ("littleskin".equalsIgnoreCase(args[1])) {
                    resolveLittleSkin(player.getUniqueId(), session.connection);
                    return true;
                }
                if ("premium".equalsIgnoreCase(args[1])) {
                    // Persist first. A failed write cannot be reported as a durable temporary allowance.
                    session.state = PlayerSession.State.RESOLVING;
                    UUID gameUuid = player.getUniqueId(), connection = session.connection;
                    String playerName = player.getName();
                    workers.execute(() -> {
                        try {
                            repository.recordPremiumTimeoutChoice(scope, gameUuid, playerName, System.currentTimeMillis());
                            main(() -> {
                                PlayerSession active = current(gameUuid, connection);
                                if (active == null || active.state != PlayerSession.State.RESOLVING) return;
                                active.state = PlayerSession.State.BYPASS;
                                Player target = Bukkit.getPlayer(gameUuid);
                                if (target != null) target.sendMessage(message("premium-temporary-allow"));
                            });
                        } catch (Exception error) {
                            getLogger().warning("Cannot record premium timeout choice: " + error.getMessage());
                            main(() -> {
                                Player target = Bukkit.getPlayer(gameUuid);
                                if (current(gameUuid, connection) != null && target != null)
                                    target.kickPlayer(message("kick-database-unavailable"));
                            });
                        }
                    });
                    return true;
                }
                sender.sendMessage(message("premium-choice-usage"));
                return true;
            }
            if ("release".equals(action) && sender instanceof Player && args.length == 2) {
                PlayerSession session = sessions.get(((Player) sender).getUniqueId());
                if (session == null || session.state != PlayerSession.State.ADMITTED || session.uid == null) {
                    sender.sendMessage(message("release-not-verified")); return true;
                }
                UUID target = UUID.fromString(args[1]);
                workers.execute(() -> {
                    try {
                        Decision result = slots.selfRelease(scope, session.uid, target);
                        main(() -> {
                            sender.sendMessage(message("release-result." + result.name()));
                            if (result == Decision.ALLOW_EXISTING) kickLocal(target, message("kick-self-released"));
                        });
                    } catch (Exception error) { main(() -> sender.sendMessage(message("release-database-error"))); }
                });
                return true;
            }
            if (!sender.hasPermission("littleslot.admin")) { sender.sendMessage(message("no-permission")); return true; }
            if (("account".equals(action) || "player".equals(action)) && args.length == 2) {
                final boolean account = "account".equals(action);
                final long uid = account ? Long.parseLong(args[1]) : 0L;
                final UUID profile = account ? null : UUID.fromString(args[1]);
                workers.execute(() -> {
                    try {
                        String summary = account ? slots.accountSummary(scope, uid) : slots.profileSummary(scope, profile);
                        main(() -> sender.sendMessage(message("query-result", "summary", summary)));
                    } catch (Exception error) { main(() -> sender.sendMessage(message("query-failed"))); }
                });
                return true;
            }
            if ("limit".equals(action) && args.length == 3) {
                long uid = Long.parseLong(args[1]);
                Integer limit = "reset".equalsIgnoreCase(args[2]) ? null : Integer.valueOf(args[2]);
                workers.execute(() -> {
                    try { slots.setLimit(scope, uid, limit); main(() -> sender.sendMessage(message("limit-updated"))); }
                    catch (Exception error) { main(() -> sender.sendMessage(message("admin-update-failed"))); }
                });
                return true;
            }
            if (("block".equals(action) || "unblock".equals(action)) && args.length == 2) {
                UUID target = UUID.fromString(args[1]);
                boolean block = "block".equals(action);
                workers.execute(() -> {
                    try {
                        slots.setBlocked(scope, target, block);
                        main(() -> {
                            sender.sendMessage(message("block-updated"));
                            if (block) kickLocal(target, message("kick-admin-blocked"));
                        });
                    } catch (Exception error) { main(() -> sender.sendMessage(message("admin-update-failed"))); }
                });
                return true;
            }
            if ("adminrelease".equals(action) && args.length == 2) {
                UUID target = UUID.fromString(args[1]);
                workers.execute(() -> {
                    try {
                        boolean released = slots.adminRelease(scope, target);
                        main(() -> {
                            sender.sendMessage(message(released ? "adminrelease-success" : "adminrelease-empty"));
                            if (released) kickLocal(target, message("kick-admin-released"));
                        });
                    } catch (Exception error) { main(() -> sender.sendMessage(message("adminrelease-failed"))); }
                });
                return true;
            }
        } catch (IllegalArgumentException invalid) { sender.sendMessage(message("invalid-argument")); return true; }
        sender.sendMessage(message("wrong-usage"));
        return true;
    }

    private void kickLocal(UUID profile, String reason) {
        for (PlayerSession session : sessions.values()) {
            if (!profile.equals(session.gameUuid) || session.state == PlayerSession.State.BYPASS) continue;
            Player player = Bukkit.getPlayer(session.gameUuid);
            if (player != null) player.kickPlayer(reason);
        }
    }

    private void pollAudit() {
        if (!auditPolling.compareAndSet(false, true)) return;
        final long cursor = lastAuditId;
        workers.execute(() -> {
            try {
                java.util.List<AuditEvent> events = repository.auditAfter(scope, cursor, 100);
                java.util.Set<UUID> blockedNow = java.util.Collections.emptySet();
                if (events.stream().anyMatch(event -> "block".equals(event.action()))) {
                    blockedNow = repository.transact(scope, tx -> {
                        java.util.Set<UUID> found = new java.util.HashSet<UUID>();
                        for (AuditEvent event : events) {
                            if ("block".equals(event.action()) && event.profile() != null && tx.blocked(event.profile()))
                                found.add(event.profile());
                        }
                        return found;
                    });
                }
                final java.util.Set<UUID> activeBlocks = blockedNow;
                main(() -> {
                    for (AuditEvent event : events) {
                        if (event.id() <= lastAuditId) continue;
                        applyAuditEvent(event, activeBlocks);
                        lastAuditId = event.id();
                    }
                    auditPolling.set(false);
                });
            } catch (Exception error) {
                getLogger().warning("Audit poll failed: " + error.getMessage());
                auditPolling.set(false);
            }
        });
    }

    private void applyAuditEvent(AuditEvent event, java.util.Set<UUID> blockedNow) {
        UUID profile = event.profile();
        if (profile == null) return;
        PlayerSession session = null;
        for (PlayerSession candidate : sessions.values()) {
            if (profile.equals(candidate.gameUuid)) { session = candidate; break; }
        }
        if (session == null || session.state == PlayerSession.State.BYPASS || session.state == PlayerSession.State.DENIED) return;
        String action = event.action();
        if ("block".equals(action) && blockedNow.contains(profile)) {
            kickLocal(profile, message("kick-blocked"));
        } else if (("self_release".equals(action) || "admin_release".equals(action)
                || "ownership_revoked".equals(action) || "ownership_transferred".equals(action))
                && session.uid != null && session.uid.equals(event.uid()) && session.admittedAt <= event.atMillis()) {
            kickLocal(profile, message("kick-audit-changed"));
        }
    }
}
