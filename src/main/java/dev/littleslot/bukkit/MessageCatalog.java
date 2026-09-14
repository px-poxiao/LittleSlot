package dev.littleslot.bukkit;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Loads editable messages while retaining bundled defaults for keys added in later versions. */
final class MessageCatalog {
    private final YamlConfiguration messages;

    MessageCatalog(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) plugin.saveResource("messages.yml", false);
        messages = YamlConfiguration.loadConfiguration(file);
        try (InputStream resource = plugin.getResource("messages.yml")) {
            if (resource == null) throw new IllegalStateException("Bundled messages.yml is missing");
            // Bukkit does not add new comments to an existing user file. Missing keys still use the JAR defaults.
            messages.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(resource, StandardCharsets.UTF_8)));
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Cannot read bundled messages.yml", error);
        }
    }

    String text(String key, String... replacements) {
        if ((replacements.length & 1) != 0) throw new IllegalArgumentException("Message replacements must be pairs");
        String template = messages.getString(key);
        if (template == null) throw new IllegalArgumentException("Missing message: " + key);
        // Translate the template before substitution. A URL containing e.g. "&c" must remain a URL, not a color code.
        String rendered = ChatColor.translateAlternateColorCodes('&', template);
        for (int i = 0; i < replacements.length; i += 2)
            rendered = rendered.replace("{" + replacements[i] + "}", replacements[i + 1]);
        return rendered;
    }
}
