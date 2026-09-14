package dev.littleslot.oauth;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.littleslot.core.VerifiedAccount;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** OAuth code flow hosted by the server owner, with HTTPS supplied by a reverse proxy. */
public final class SelfHostedCodeProvider implements OAuthProvider {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final String clientId;
    private final String clientSecret;
    private final URI redirectUri;
    private final ExecutorService workers;
    private final Function<String, String> callbackText;
    private final ExecutorService httpWorkers = Executors.newFixedThreadPool(2);
    private final ScheduledExecutorService expirations = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, AuthorizationSession> pending = new ConcurrentHashMap<String, AuthorizationSession>();
    private final HttpServer server;

    public SelfHostedCodeProvider(String clientId, String clientSecret, URI redirectUri,
                                  String listenHost, int listenPort, ExecutorService workers) throws IOException {
        this(clientId, clientSecret, redirectUri, listenHost, listenPort, workers,
                key -> "LittleSlot OAuth: " + key);
    }

    public SelfHostedCodeProvider(String clientId, String clientSecret, URI redirectUri,
                                  String listenHost, int listenPort, ExecutorService workers,
                                  Function<String, String> callbackText) throws IOException {
        if (clientId == null || clientId.isEmpty() || clientSecret == null || clientSecret.isEmpty()
                || redirectUri == null || !"https".equals(redirectUri.getScheme()) || redirectUri.getHost() == null
                || redirectUri.getPath().isEmpty() || "/".equals(redirectUri.getPath())
                || redirectUri.getRawQuery() != null || redirectUri.getFragment() != null || workers == null
                || callbackText == null)
            throw new IllegalArgumentException("Invalid code-flow configuration");
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
        this.workers = workers;
        this.callbackText = callbackText;
        server = HttpServer.create(new InetSocketAddress(listenHost, listenPort), 0);
        server.createContext(redirectUri.getPath(), this::callback);
        server.setExecutor(httpWorkers);
        server.start();
    }

    @Override public AuthorizationSession start() {
        AuthorizationSession session = new AuthorizationSession();
        String state = randomHex(32);
        pending.put(state, session);
        session.result.whenComplete((value, error) -> pending.remove(state, session));
        expirations.schedule(() -> session.result.completeExceptionally(new OAuthException("expired_token")), 5, TimeUnit.MINUTES);
        String url = "https://littleskin.cn/oauth/authorize?client_id=" + encode(clientId)
                + "&redirect_uri=" + encode(redirectUri.toString())
                + "&response_type=code&scope=" + encode("User.Read Yggdrasil.PlayerProfiles.Read")
                + "&state=" + state;
        session.verificationUrl.complete(url);
        return session;
    }

    public int port() { return server.getAddress().getPort(); }

    @Override public boolean available() {
        return LittleSkinHttp.apiAvailable("https://littleskin.cn/oauth/authorize")
                && LittleSkinHttp.apiAvailable("https://littleskin.cn/api/user");
    }

    private void callback(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod()) || !redirectUri.getPath().equals(exchange.getRequestURI().getPath())) {
            reply(exchange, 404, callbackText.apply("invalid")); return;
        }
        Map<String, String> values = query(exchange.getRequestURI().getRawQuery());
        String state = values.get("state");
        AuthorizationSession session = state == null ? null : pending.remove(state);
        if (session == null || session.result.isDone()) { reply(exchange, 400, callbackText.apply("expired")); return; }
        if (values.containsKey("error")) {
            session.result.completeExceptionally(new OAuthException(values.get("error")));
            reply(exchange, 400, callbackText.apply("denied"));
            return;
        }
        String code = values.get("code");
        if (code == null || code.isEmpty()) {
            session.result.completeExceptionally(new OAuthException("missing_code"));
            reply(exchange, 400, callbackText.apply("missing-code"));
            return;
        }
        workers.execute(() -> {
            if (session.result.isDone()) return;
            try {
                JsonObject token = LittleSkinHttp.object(LittleSkinHttp.request("https://littleskin.cn/oauth/token", "POST", null,
                        "grant_type=authorization_code&client_id=" + encode(clientId) + "&client_secret=" + encode(clientSecret)
                                + "&redirect_uri=" + encode(redirectUri.toString()) + "&code=" + encode(code)));
                String access = token.get("access_token").getAsString();
                VerifiedAccount verified = LittleSkinHttp.readAccount(access);
                session.result.complete(verified);
            } catch (Exception error) { session.result.completeExceptionally(error); }
        });
        reply(exchange, 200, callbackText.apply("submitted"));
    }

    @Override public void close() {
        for (AuthorizationSession session : pending.values()) session.cancel();
        pending.clear();
        server.stop(1);
        expirations.shutdownNow();
        httpWorkers.shutdownNow();
    }

    private static String randomHex(int bytes) {
        byte[] data = new byte[bytes]; RANDOM.nextBytes(data);
        StringBuilder output = new StringBuilder(bytes * 2);
        for (byte item : data) output.append(String.format("%02x", item & 0xff));
        return output.toString();
    }
    private static String encode(String value) {
        try { return URLEncoder.encode(value, "UTF-8"); }
        catch (Exception impossible) { throw new AssertionError(impossible); }
    }
    private static Map<String, String> query(String raw) {
        Map<String, String> values = new HashMap<String, String>();
        if (raw == null) return values;
        for (String part : raw.split("&")) {
            String[] pieces = part.split("=", 2);
            try { values.put(java.net.URLDecoder.decode(pieces[0], "UTF-8"), pieces.length == 2 ? java.net.URLDecoder.decode(pieces[1], "UTF-8") : ""); }
            catch (Exception ignored) { }
        }
        return values;
    }
    private static void reply(HttpExchange exchange, int status, String message) throws IOException {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
    }
}
