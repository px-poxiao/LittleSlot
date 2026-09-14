package dev.littleslot.bukkit;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Protects the user-facing configuration contract, including documentation on every value. */
class ConfigurationDocumentationTest {
    @Test void configAndMessagesAreValidAndEveryValueHasBilingualComments() throws Exception {
        Map<?, ?> config = parse("config.yml");
        Map<?, ?> messages = parse("messages.yml");
        assertNotNull(config.get("oauth"));
        assertNotNull(config.get("database"));
        assertNotNull(config.get("premium-compatibility"));
        assertNotNull(config.get("premium-lookup-timeout-millis"));
        assertNotNull(messages.get("release-result"));
        for (String name : Arrays.asList("config.yml", "messages.yml")) {
            String[] lines = resource(name).split("\\R");
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                if (line.trim().isEmpty() || line.trim().startsWith("#") || line.trim().endsWith(":")) continue;
                assertTrue(line.matches("\\s*[A-Za-z][A-Za-z0-9_-]*:.*"), name + ":" + (i + 1));
                List<String> comments = new ArrayList<String>();
                for (int j = i - 1; j >= 0 && lines[j].trim().startsWith("#"); j--) comments.add(lines[j]);
                String joined = String.join(" ", comments);
                assertTrue(joined.matches("(?s).*[\\u4e00-\\u9fff].*"), "Missing Chinese explanation: " + name + ":" + (i + 1));
                assertTrue(joined.matches("(?s).*[A-Za-z]{3,}.*"), "Missing English explanation: " + name + ":" + (i + 1));
            }
        }
        Set<String> messageKeys = new HashSet<String>();
        flatten("", messages, messageKeys);
        for (String key : Arrays.asList("admitted", "binding-reminder", "verification-link", "kick-blocked",
                "kick-full", "oauth-cancelled", "recovery-binding", "query-result", "release-result.ALLOW_EXISTING",
                "release-result.ACCOUNT_MISMATCH", "release-result.COOLDOWN", "release-result.NOT_ALLOCATED",
                "adminrelease-success", "adminrelease-empty", "oauth-code-invalid", "oauth-code-expired",
                "oauth-code-denied", "oauth-code-missing-code", "oauth-code-submitted",
                "premium-lookup-timeout-choice", "premium-temporary-allow", "kick-premium-lookup-error",
                "kick-account-mismatch", "premium-choice-usage"))
            assertTrue(messageKeys.contains(key), "Missing message key: " + key);
        String pluginSource = new String(Files.readAllBytes(Paths.get(
                "src/main/java/dev/littleslot/bukkit/LittleSlotPlugin.java")), StandardCharsets.UTF_8);
        Matcher calls = Pattern.compile("message\\(\\\"([a-z0-9.-]+)\\\"").matcher(pluginSource);
        while (calls.find()) {
            String key = calls.group(1);
            if (!key.endsWith(".") && !key.endsWith("-"))
                assertTrue(messageKeys.contains(key), "Missing message key: " + key);
        }
        Properties backend = new Properties();
        backend.load(new StringReader(resource("backend-messages.properties")));
        assertNotNull(backend.getProperty("cancelled"));
        assertNotNull(backend.getProperty("completed"));
    }

    private static Map<?, ?> parse(String name) throws Exception {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        try (InputStream stream = ConfigurationDocumentationTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(stream, name);
            Object value = new Yaml(new SafeConstructor(options)).load(stream);
            assertTrue(value instanceof Map, name);
            return (Map<?, ?>) value;
        }
    }

    private static String resource(String name) throws Exception {
        try (InputStream stream = ConfigurationDocumentationTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(stream, name);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int read; (read = stream.read(buffer)) != -1; ) output.write(buffer, 0, read);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void flatten(String prefix, Map<?, ?> node, Set<String> result) {
        for (Map.Entry<?, ?> entry : node.entrySet()) {
            String path = prefix + entry.getKey();
            if (entry.getValue() instanceof Map) flatten(path + ".", (Map<?, ?>) entry.getValue(), result);
            else result.add(path);
        }
    }
}
