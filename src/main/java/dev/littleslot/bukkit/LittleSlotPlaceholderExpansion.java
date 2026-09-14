package dev.littleslot.bukkit;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

/** Optional in-plugin PlaceholderAPI expansion; the PAPI API is provided by the server. */
final class LittleSlotPlaceholderExpansion extends PlaceholderExpansion {
    private final LittleSlotPlugin plugin;

    LittleSlotPlaceholderExpansion(LittleSlotPlugin plugin) { this.plugin = plugin; }

    @Override public String getIdentifier() { return "littleslot"; }
    @Override public String getAuthor() { return "LittleSlot"; }
    @Override public String getVersion() { return plugin.getDescription().getVersion(); }

    // PAPI reloads must not discard an expansion owned and registered by this plugin.
    @Override public boolean persist() { return true; }

    @Override public String onRequest(OfflinePlayer player, String params) {
        PlayerSession session = player == null ? null : plugin.placeholderSession(player.getUniqueId());
        return PlaceholderValues.value(session, plugin.placeholderScope(), params);
    }
}
