package dev.littleslot.oauth;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.littleslot.core.VerifiedAccount;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

/** Sends only an API key and a fresh random request identifier to the public service. */
public final class PublicCodeProvider implements OAuthProvider {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final URI base;
    private final String apiKey;
    private final ExecutorService workers;

    public PublicCodeProvider(URI base, String apiKey, ExecutorService workers) {
        if (base == null || !"https".equals(base.getScheme()) || base.getHost() == null
                || apiKey == null || apiKey.isEmpty() || workers == null) throw new IllegalArgumentException("public OAuth settings");
        this.base = base;
        this.apiKey = apiKey;
        this.workers = workers;
    }

    @Override public AuthorizationSession start() {
        AuthorizationSession authorization = new AuthorizationSession();
        authorization.task(workers.submit(() -> run(authorization)));
        return authorization;
    }

    @Override public boolean available() {
        try {
            return "ok".equals(request(base.resolve("/v1/health"), "GET", null).get("status").getAsString());
        } catch (Exception unavailable) {
            return false;
        }
    }

    private void run(AuthorizationSession authorization) {
        try {
            String id = requestId();
            JsonObject body = new JsonObject();
            body.addProperty("requestId", id);
            JsonObject created = request(base.resolve("/v1/requests"), "POST", body.toString());
            String verifyUrl = created.get("verificationUrl").getAsString();
            URI pollUrl = URI.create(created.get("pollUrl").getAsString());
            if (!sameOrigin(pollUrl) || !pollUrl.getPath().equals("/v1/requests/" + id))
                throw new IOException("Untrusted poll URL");
            if (!verifyUrl.startsWith("https://littleskin.cn/oauth/authorize?"))
                throw new IOException("Untrusted verification URL");
            long deadline = System.currentTimeMillis() + Math.min(300, created.get("expiresIn").getAsLong()) * 1000L;
            authorization.verificationUrl.complete(verifyUrl);
            while (System.currentTimeMillis() < deadline && !Thread.currentThread().isInterrupted()) {
                Thread.sleep(5000L);
                JsonObject result = request(pollUrl, "GET", null);
                String status = result.get("status").getAsString();
                if ("pending".equals(status)) continue;
                if ("success".equals(status)) {
                    authorization.result.complete(parseResult(result.getAsJsonObject("result")));
                    return;
                }
                throw new OAuthException(status);
            }
            throw new OAuthException("expired");
        } catch (Exception error) {
            authorization.verificationUrl.completeExceptionally(error);
            authorization.result.completeExceptionally(error);
        }
    }

    private JsonObject request(URI uri, String method, String body) throws IOException {
        if (!sameOrigin(uri)) throw new IOException("Untrusted backend origin");
        HttpURLConnection connection = (HttpURLConnection) new URL(uri.toString()).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(10000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("X-LittleSlot-Key", apiKey);
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            try (OutputStream output = connection.getOutputStream()) { output.write(body.getBytes(StandardCharsets.UTF_8)); }
        }
        try (InputStream input = connection.getResponseCode() < 400 ? connection.getInputStream() : connection.getErrorStream()) {
            if (input == null) throw new IOException("Backend response empty");
            JsonElement value = new JsonParser().parse(new InputStreamReader(input, StandardCharsets.UTF_8));
            JsonObject object = LittleSkinHttp.object(value);
            if (connection.getResponseCode() >= 400) {
                throw new OAuthException(object.has("error") ? object.get("error").getAsString() : "backend_http_" + connection.getResponseCode());
            }
            return object;
        } finally { connection.disconnect(); }
    }

    private boolean sameOrigin(URI uri) {
        return "https".equals(uri.getScheme()) && base.getHost().equalsIgnoreCase(uri.getHost())
                && base.getPort() == uri.getPort() && uri.getRawUserInfo() == null && uri.getRawQuery() == null
                && uri.getFragment() == null;
    }

    static VerifiedAccount parseResult(JsonObject result) throws IOException {
        if (result == null || !result.has("uid") || !result.has("profiles") || !result.get("profiles").isJsonArray())
            throw new IOException("Incomplete backend result");
        long uid = result.get("uid").getAsLong();
        Set<UUID> profiles = new HashSet<UUID>();
        for (JsonElement element : result.getAsJsonArray("profiles")) {
            String text = element.getAsString();
            if (!text.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
                throw new IOException("Malformed backend UUID");
            if (!profiles.add(UUID.fromString(text))) throw new IOException("Duplicate backend UUID");
        }
        return new VerifiedAccount(uid, profiles, System.currentTimeMillis());
    }

    private static String requestId() {
        byte[] random = new byte[24]; RANDOM.nextBytes(random);
        StringBuilder text = new StringBuilder(Long.toString(System.currentTimeMillis())).append('-');
        for (byte b : random) text.append(String.format("%02x", b & 0xff));
        return text.toString();
    }
}
