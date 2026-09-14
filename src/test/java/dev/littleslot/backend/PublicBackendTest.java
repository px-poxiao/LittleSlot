package dev.littleslot.backend;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.littleslot.core.VerifiedAccount;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicBackendTest {
    @TempDir Path temporary;

    @Test void requestIsKeyBoundAndOneSettlementDebitsExactlyOnce() throws Exception {
        String jdbc = "jdbc:sqlite:" + temporary.resolve("billing.db");
        PublicBackend backend = new PublicBackend(jdbc, URI.create("https://public.example/"), 0, "client", "secret");
        String firstKey = PublicBackend.issueKey(jdbc, 1);
        String secondKey = PublicBackend.issueKey(jdbc, 1);
        backend.start();
        try {
            String id = System.currentTimeMillis() + "-" + repeat('a', 48);
            HttpResult created = request(backend.port(), "POST", "/v1/requests", firstKey, "{\"requestId\":\"" + id + "\"}");
            assertEquals(200, created.status);
            assertTrue(created.json.get("verificationUrl").getAsString().startsWith("https://littleskin.cn/oauth/authorize?"));
            assertEquals("https://public.example/v1/requests/" + id, created.json.get("pollUrl").getAsString());
            assertEquals(404, request(backend.port(), "GET", "/v1/requests/" + id, secondKey, null).status);
            assertEquals("pending", request(backend.port(), "GET", "/v1/requests/" + id, firstKey, null).json.get("status").getAsString());

            String url = created.json.get("verificationUrl").getAsString();
            String state = url.substring(url.indexOf("&state=") + 7);
            UUID profile = UUID.randomUUID();
            backend.settle(id, state, new VerifiedAccount(77, Collections.singleton(profile), System.currentTimeMillis()));
            assertThrows(java.sql.SQLException.class, () -> backend.settle(id, state,
                    new VerifiedAccount(77, Collections.singleton(profile), System.currentTimeMillis())));
            JsonObject result = request(backend.port(), "GET", "/v1/requests/" + id, firstKey, null).json;
            assertEquals("success", result.get("status").getAsString());
            assertEquals(77, result.getAsJsonObject("result").get("uid").getAsLong());
            assertEquals(profile.toString(), result.getAsJsonObject("result").getAsJsonArray("profiles").get(0).getAsString());
            try (Connection db = DriverManager.getConnection(jdbc); Statement statement = db.createStatement();
                 ResultSet row = statement.executeQuery("SELECT credits FROM pb_keys WHERE credits=0")) {
                assertTrue(row.next());
                assertEquals(0, row.getLong(1));
            }
            assertEquals(200, request(backend.port(), "POST", "/v1/requests", firstKey, "{\"requestId\":\"" + id + "\"}").status);
            assertEquals(402, request(backend.port(), "POST", "/v1/requests", firstKey,
                    "{\"requestId\":\"" + System.currentTimeMillis() + "-" + repeat('b', 48) + "\"}").status);
        } finally { backend.stop(); }
    }

    @Test void pendingRequestReservesLastCreditAndCancellationReleasesIt() throws Exception {
        String jdbc = "jdbc:sqlite:" + temporary.resolve("reservations.db");
        PublicBackend backend = new PublicBackend(jdbc, URI.create("https://public.example/"), 0, "client", "secret");
        String key = PublicBackend.issueKey(jdbc, 1);
        backend.start();
        try {
            String first = System.currentTimeMillis() + "-" + repeat('c', 48);
            String second = System.currentTimeMillis() + "-" + repeat('d', 48);
            HttpResult created = request(backend.port(), "POST", "/v1/requests", key, "{\"requestId\":\"" + first + "\"}");
            assertEquals(200, created.status);
            assertEquals(402, request(backend.port(), "GET", "/v1/health", key, null).status);
            assertEquals(402, request(backend.port(), "POST", "/v1/requests", key, "{\"requestId\":\"" + second + "\"}").status);
            String url = created.json.get("verificationUrl").getAsString();
            String state = url.substring(url.indexOf("&state=") + 7);
            assertEquals(200, request(backend.port(), "GET", "/oauth/callback?state=" + state + "&error=access_denied", key, null).status);
            assertEquals("denied", request(backend.port(), "GET", "/v1/requests/" + first, key, null).json.get("status").getAsString());
            assertEquals(200, request(backend.port(), "GET", "/v1/health", key, null).status);
            assertEquals(200, request(backend.port(), "POST", "/v1/requests", key, "{\"requestId\":\"" + second + "\"}").status);
        } finally { backend.stop(); }
    }

    private static HttpResult request(int port, String method, String path, String key, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        connection.setRequestMethod(method);
        connection.setRequestProperty("X-LittleSlot-Key", key);
        if (body != null) {
            connection.setDoOutput(true);
            try (OutputStream output = connection.getOutputStream()) { output.write(body.getBytes(StandardCharsets.UTF_8)); }
        }
        int status = connection.getResponseCode();
        try (InputStreamReader reader = new InputStreamReader(status < 400 ? connection.getInputStream() : connection.getErrorStream(), StandardCharsets.UTF_8)) {
            return new HttpResult(status, new JsonParser().parse(reader).getAsJsonObject());
        } finally { connection.disconnect(); }
    }

    private static String repeat(char character, int count) {
        char[] chars = new char[count]; java.util.Arrays.fill(chars, character); return new String(chars);
    }

    private static final class HttpResult {
        final int status; final JsonObject json;
        HttpResult(int status, JsonObject json) { this.status = status; this.json = json; }
    }
}
