package dev.littleslot.bukkit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** One uncached username lookup per join. Only an explicit 404 means no premium profile. */
final class MojangLookup {
    private final URI base;
    private final int timeoutMillis;

    MojangLookup(URI base, int timeoutMillis) {
        if (!"https".equalsIgnoreCase(base.getScheme()) && !"http".equalsIgnoreCase(base.getScheme()))
            throw new IllegalArgumentException("Mojang lookup URL must be HTTP(S)");
        if (timeoutMillis < 100 || timeoutMillis > 60_000) throw new IllegalArgumentException("Mojang timeout");
        this.base = base;
        this.timeoutMillis = timeoutMillis;
    }

    /** Returns null only for an explicit 404. Malformed success and service errors fail closed. */
    UUID byName(String name) throws Exception {
        if (name == null || !name.matches("[A-Za-z0-9_]{1,16}")) throw new IllegalArgumentException("Minecraft name");
        HttpURLConnection connection = (HttpURLConnection) base.resolve(name).toURL().openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/json");
        connection.setConnectTimeout(timeoutMillis);
        connection.setReadTimeout(timeoutMillis);
        try {
            int status = connection.getResponseCode();
            if (status == 404) return null;
            if (status != 200) throw new LookupFailure("Mojang HTTP " + status);
            try (InputStream stream = connection.getInputStream();
                 InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                JsonObject profile = JsonParser.parseReader(reader).getAsJsonObject();
                String returnedName = profile.get("name").getAsString();
                String compact = profile.get("id").getAsString();
                if (!name.equalsIgnoreCase(returnedName) || !compact.matches("[0-9a-fA-F]{32}"))
                    throw new LookupFailure("Invalid Mojang profile response");
                return UUID.fromString(compact.replaceFirst(
                        "([0-9a-fA-F]{8})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]{12})",
                        "$1-$2-$3-$4-$5"));
            }
        } catch (SocketTimeoutException timeout) {
            throw timeout;
        } finally {
            connection.disconnect();
        }
    }

    static final class LookupFailure extends Exception {
        LookupFailure(String message) { super(message); }
    }
}
