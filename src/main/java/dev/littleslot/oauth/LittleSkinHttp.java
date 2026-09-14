package dev.littleslot.oauth;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.littleslot.core.VerifiedAccount;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Shared strict parser for all three OAuth transports. Never logs bodies or access tokens. */
public final class LittleSkinHttp {
    private LittleSkinHttp() { }

    /** A missing bearer token should be rejected by the API; any non-5xx response proves reachability. */
    static boolean apiAvailable(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json");
            int status = connection.getResponseCode();
            return status >= 200 && status < 500;
        } catch (IOException unavailable) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    public static JsonElement request(String url, String method, String bearer, String body) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(10000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Accept", "application/json");
        if (bearer != null) connection.setRequestProperty("Authorization", "Bearer " + bearer);
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
        }
        try (InputStream stream = connection.getResponseCode() < 400 ? connection.getInputStream() : connection.getErrorStream()) {
            if (stream == null) throw new IOException("Empty LittleSkin response: HTTP " + connection.getResponseCode());
            JsonElement value = new JsonParser().parse(new InputStreamReader(stream, StandardCharsets.UTF_8));
            if (connection.getResponseCode() >= 400) {
                String error = value.isJsonObject() && value.getAsJsonObject().has("error")
                        ? value.getAsJsonObject().get("error").getAsString() : "http_" + connection.getResponseCode();
                throw new OAuthException(error);
            }
            if (value.isJsonObject() && value.getAsJsonObject().has("error"))
                throw new OAuthException(value.getAsJsonObject().get("error").getAsString());
            if (value.isJsonObject() && value.getAsJsonObject().has("code")) {
                JsonElement code = value.getAsJsonObject().get("code");
                if (code.isJsonPrimitive() && code.getAsInt() != 0) throw new OAuthException("api_" + code.getAsString());
            }
            return value;
        } finally { connection.disconnect(); }
    }

    public static VerifiedAccount readAccount(String accessToken) throws IOException {
        JsonElement user = request("https://littleskin.cn/api/user", "GET", accessToken, null);
        JsonElement profiles = request("https://littleskin.cn/api/yggdrasil/sessionserver/session/minecraft/profile", "GET", accessToken, null);
        return parseAccount(user, profiles, System.currentTimeMillis());
    }

    static VerifiedAccount parseAccount(JsonElement userData, JsonElement profileData, long verifiedAt) throws IOException {
        JsonObject user = object(userData);
        if (!user.has("uid") || !user.get("uid").isJsonPrimitive()) throw new IOException("LittleSkin user UID missing");
        long uid = user.get("uid").getAsLong();
        if (!profileData.isJsonArray()) throw new IOException("LittleSkin profile list is not an array");
        Set<UUID> uuids = new HashSet<UUID>();
        JsonArray list = profileData.getAsJsonArray();
        for (JsonElement entry : list) {
            JsonObject profile = object(entry);
            if (!profile.has("id")) throw new IOException("Profile UUID missing");
            String raw = profile.get("id").getAsString();
            if (!raw.matches("[0-9a-fA-F]{32}")) throw new IOException("Malformed profile UUID");
            String formatted = raw.substring(0, 8) + "-" + raw.substring(8, 12) + "-" + raw.substring(12, 16)
                    + "-" + raw.substring(16, 20) + "-" + raw.substring(20);
            if (!uuids.add(UUID.fromString(formatted))) throw new IOException("Duplicate profile UUID");
        }
        return new VerifiedAccount(uid, uuids, verifiedAt);
    }

    public static JsonObject object(JsonElement value) throws IOException {
        if (value == null || !value.isJsonObject()) throw new IOException("Expected JSON object");
        return value.getAsJsonObject();
    }
}
