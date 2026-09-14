package dev.littleslot.bukkit;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.net.URI;

/** Session evidence only. A paid-name lookup or old FastLogin database flag is insufficient. */
final class SourceDetector {
    private final boolean directLittleSkin;

    SourceDetector() {
        this.directLittleSkin = detectDirectAgent();
    }

    boolean directLittleSkin() { return directLittleSkin; }

    LoginIdentity detect(Player player, long joinedAt) {
        // AuthMe proves its own password step finished, not that this connection came from LittleSkin.
        if (Bukkit.getPluginManager().isPluginEnabled("AuthMe") && !authMeAuthenticated(player)) {
            return new LoginIdentity(LoginSource.WAITING, null);
        }
        Plugin fastLogin = Bukkit.getPluginManager().getPlugin("FastLogin");
        if (fastLogin != null && fastLogin.isEnabled()) {
            try {
                Method getStatus = fastLogin.getClass().getMethod("getStatus", java.util.UUID.class);
                Object status = getStatus.invoke(fastLogin, player.getUniqueId());
                if (status != null && "PREMIUM".equals(status.toString()))
                    return new LoginIdentity(LoginSource.PREMIUM, player.getUniqueId());
                if (status != null && "UNKNOWN".equals(status.toString()))
                    return new LoginIdentity(System.currentTimeMillis() - joinedAt < 15_000L
                            ? LoginSource.WAITING : LoginSource.UNKNOWN, null);
                // CRACKED means only that Mojang did not verify this connection; it is not LittleSkin proof.
            } catch (ReflectiveOperationException error) {
                return new LoginIdentity(LoginSource.UNKNOWN, null);
            }
        }
        if (directLittleSkin && !Bukkit.getPluginManager().isPluginEnabled("MultiLogin")
                && !Bukkit.getPluginManager().isPluginEnabled("FastLogin"))
            return new LoginIdentity(LoginSource.LITTLE_SKIN, player.getUniqueId());
        return new LoginIdentity(LoginSource.UNKNOWN, null);
    }

    private boolean authMeAuthenticated(Player player) {
        try {
            Class<?> apiClass = Class.forName("fr.xephi.authme.api.v3.AuthMeApi");
            Object api = apiClass.getMethod("getInstance").invoke(null);
            return api != null && Boolean.TRUE.equals(apiClass.getMethod("isAuthenticated", Player.class).invoke(api, player));
        } catch (ReflectiveOperationException error) {
            return false;
        }
    }

    private boolean detectDirectAgent() {
        // A direct agent is only enough evidence on a single-source, online-mode Bukkit server.
        // Proxy backends can run offline-mode, so they require a separate trusted session bridge.
        if (!Bukkit.getOnlineMode()) return false;
        for (String argument : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (!argument.startsWith("-javaagent:") || !argument.contains("=")) continue;
            String url = argument.substring(argument.indexOf('=') + 1);
            if ("littleskin.cn".equalsIgnoreCase(url)) return true;
            try {
                URI uri = URI.create(url);
                if ("https".equalsIgnoreCase(uri.getScheme()) && "littleskin.cn".equalsIgnoreCase(uri.getHost())
                        && ("/api/yggdrasil".equals(uri.getPath()) || "/api/yggdrasil/".equals(uri.getPath()))) {
                    return true;
                }
            } catch (IllegalArgumentException ignored) { }
        }
        return false;
    }
}
