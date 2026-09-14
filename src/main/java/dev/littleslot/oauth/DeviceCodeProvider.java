package dev.littleslot.oauth;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;

/** Requires a LittleSkin whitelisted device-flow application configured by the server owner. */
public final class DeviceCodeProvider implements OAuthProvider {
    private final String clientId;
    private final ExecutorService workers;

    public DeviceCodeProvider(String clientId, ExecutorService workers) {
        if (clientId == null || clientId.trim().isEmpty() || workers == null) throw new IllegalArgumentException("device client ID");
        this.clientId = clientId;
        this.workers = workers;
    }

    @Override public boolean available() {
        return LittleSkinHttp.apiAvailable("https://open.littleskin.cn/oauth/device_code")
                && LittleSkinHttp.apiAvailable("https://littleskin.cn/api/user");
    }

    @Override public AuthorizationSession start() {
        AuthorizationSession authorization = new AuthorizationSession();
        authorization.task(workers.submit(() -> run(authorization)));
        return authorization;
    }

    private void run(AuthorizationSession authorization) {
        try {
            JsonObject code = LittleSkinHttp.object(LittleSkinHttp.request("https://open.littleskin.cn/oauth/device_code",
                    "POST", null, "client_id=" + encode(clientId) + "&scope=" + encode("User.Read Yggdrasil.PlayerProfiles.Read")));
            String deviceCode = code.get("device_code").getAsString();
            String verification = code.has("verification_uri_complete")
                    ? code.get("verification_uri_complete").getAsString() : code.get("verification_uri").getAsString();
            if (!verification.startsWith("https://open.littleskin.cn/oauth/")) throw new IOException("Untrusted verification URL");
            long deadline = System.currentTimeMillis() + code.get("expires_in").getAsLong() * 1000L;
            long interval = Math.max(5, code.get("interval").getAsLong());
            authorization.verificationUrl.complete(verification);
            while (System.currentTimeMillis() < deadline && !Thread.currentThread().isInterrupted()) {
                Thread.sleep(interval * 1000L);
                try {
                    JsonObject token = LittleSkinHttp.object(LittleSkinHttp.request("https://open.littleskin.cn/oauth/token",
                            "POST", null, "grant_type=" + encode("urn:ietf:params:oauth:grant-type:device_code")
                            + "&client_id=" + encode(clientId) + "&device_code=" + encode(deviceCode)));
                    if (!token.has("access_token")) throw new IOException("Access token missing");
                    String accessToken = token.get("access_token").getAsString();
                    try {
                        authorization.result.complete(LittleSkinHttp.readAccount(accessToken));
                    } finally { accessToken = null; }
                    return;
                } catch (OAuthException error) {
                    if ("authorization_pending".equals(error.code())) continue;
                    if ("slow_down".equals(error.code())) { interval += 5; continue; }
                    throw error;
                }
            }
            throw new OAuthException("expired_token");
        } catch (Exception error) {
            authorization.verificationUrl.completeExceptionally(error);
            authorization.result.completeExceptionally(error);
        }
    }

    private static String encode(String text) {
        try { return URLEncoder.encode(text, StandardCharsets.UTF_8.name()); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }
}
