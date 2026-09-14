package dev.littleslot.backend;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.littleslot.core.VerifiedAccount;
import dev.littleslot.oauth.LittleSkinHttp;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Minimal public validation service. Terminate HTTPS at a trusted reverse proxy. */
public final class PublicBackend {
    private static final long REQUEST_TTL = 5 * 60_000L;
    private static final long RESULT_TTL = 10 * 60_000L;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String databaseUrl;
    private final URI publicBase;
    private final String clientId;
    private final String clientSecret;
    private final Properties browserMessages;
    private final HttpServer server;
    private final ExecutorService httpWorkers = Executors.newFixedThreadPool(12);
    private final ScheduledExecutorService janitor = Executors.newSingleThreadScheduledExecutor();

    public static void main(String[] args) throws Exception {
        if (args.length == 3 && "issue-key".equals(args[0])) {
            System.out.println(issueKey(args[1], Long.parseLong(args[2])));
            return;
        }
        if (args.length != 3) throw new IllegalArgumentException("Usage: <sqlite-jdbc-url> <https-public-base> <loopback-port>");
        String clientId = requiredEnv("LITTLESLOT_OAUTH_CLIENT_ID");
        String clientSecret = requiredEnv("LITTLESLOT_OAUTH_CLIENT_SECRET");
        PublicBackend backend = new PublicBackend(args[0], URI.create(args[1]), Integer.parseInt(args[2]), clientId, clientSecret);
        backend.server.start();
        System.out.println("LittleSlot validation service listening on 127.0.0.1:" + args[2]);
    }

    public PublicBackend(String databaseUrl, URI publicBase, int port, String clientId, String clientSecret) throws Exception {
        if (databaseUrl == null || publicBase == null || !"https".equals(publicBase.getScheme())
                || publicBase.getHost() == null || publicBase.getRawQuery() != null || publicBase.getFragment() != null
                || !(publicBase.getPath().isEmpty() || "/".equals(publicBase.getPath()))
                || clientId == null || clientId.isEmpty() || clientSecret == null || clientSecret.isEmpty())
            throw new IllegalArgumentException("Invalid backend settings");
        this.databaseUrl = databaseUrl;
        this.publicBase = publicBase;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.browserMessages = loadBrowserMessages();
        try (Connection db = open()) { schema(db); }
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/v1/requests", this::requests);
        server.createContext("/v1/health", this::health);
        server.createContext("/oauth/callback", this::callback);
        server.setExecutor(httpWorkers);
        janitor.scheduleAtFixedRate(this::cleanExpired, 1, 1, TimeUnit.MINUTES);
    }

    public void start() { server.start(); }
    public void stop() { server.stop(1); janitor.shutdownNow(); httpWorkers.shutdownNow(); }
    public int port() { return server.getAddress().getPort(); }

    public static String issueKey(String databaseUrl, long credits) throws SQLException {
        if (credits < 0) throw new IllegalArgumentException("credits");
        sqliteDriver();
        String key = newKey();
        try (Connection db = DriverManager.getConnection(databaseUrl)) {
            schema(db);
            try (PreparedStatement statement = db.prepareStatement("INSERT INTO pb_keys(key_hash,credits,enabled) VALUES (?,?,1)")) {
                statement.setString(1, hash(key));
                statement.setLong(2, credits);
                statement.executeUpdate();
            }
        }
        return key;
    }
    private Connection open() throws SQLException {
        sqliteDriver();
        Connection db = DriverManager.getConnection(databaseUrl);
        try (Statement statement = db.createStatement()) { statement.execute("PRAGMA busy_timeout=5000"); }
        return db;
    }

    private static void sqliteDriver() throws SQLException {
        try { Class.forName("org.sqlite.JDBC"); }
        catch (ClassNotFoundException error) { throw new SQLException("Missing SQLite JDBC driver", error); }
    }

    private static void schema(Connection db) throws SQLException {
        try (Statement statement = db.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS pb_keys(key_hash TEXT PRIMARY KEY, credits BIGINT NOT NULL, enabled INTEGER NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS pb_requests(request_id TEXT PRIMARY KEY, key_hash TEXT NOT NULL, oauth_state TEXT UNIQUE NOT NULL, status TEXT NOT NULL, created_at BIGINT NOT NULL, result_until BIGINT NOT NULL DEFAULT 0, uid BIGINT, profiles_json TEXT)");
            statement.execute("CREATE INDEX IF NOT EXISTS pb_requests_key ON pb_requests(key_hash)");
        }
    }

    private void requests(HttpExchange exchange) throws IOException {
        try {
            String key = exchange.getRequestHeaders().getFirst("X-LittleSlot-Key");
            if (key == null || !key.matches("ls_[0-9a-f]{64}")) { reply(exchange, 401, error("invalid_key")); return; }
            String keyHash = hash(key);
            if ("POST".equals(exchange.getRequestMethod()) && "/v1/requests".equals(exchange.getRequestURI().getPath())) {
                JsonObject body = new JsonParser().parse(new InputStreamReader(limited(exchange.getRequestBody(), 2048), StandardCharsets.UTF_8)).getAsJsonObject();
                if (!body.has("requestId") || body.entrySet().size() != 1) { reply(exchange, 400, error("invalid_request")); return; }
                create(exchange, keyHash, body.get("requestId").getAsString());
            } else if ("GET".equals(exchange.getRequestMethod()) && exchange.getRequestURI().getPath().startsWith("/v1/requests/")) {
                poll(exchange, keyHash, exchange.getRequestURI().getPath().substring("/v1/requests/".length()));
            } else reply(exchange, 404, error("not_found"));
        } catch (Exception failure) {
            reply(exchange, 500, error("server_error"));
        }
    }

    private void health(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod()) || !"/v1/health".equals(exchange.getRequestURI().getPath())) {
            reply(exchange, 404, error("not_found")); return;
        }
        String key = exchange.getRequestHeaders().getFirst("X-LittleSlot-Key");
        if (key == null || !key.matches("ls_[0-9a-f]{64}")) { reply(exchange, 401, error("invalid_key")); return; }
        try (Connection db = open(); PreparedStatement query = db.prepareStatement(
                "SELECT credits FROM pb_keys WHERE key_hash=? AND enabled=1")) {
            query.setString(1, hash(key));
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) { reply(exchange, 401, error("invalid_key")); return; }
                if (result.getLong(1) <= activeReservations(db, hash(key), System.currentTimeMillis())) {
                    reply(exchange, 402, error("no_credits")); return;
                }
            }
            JsonObject response = new JsonObject();
            response.addProperty("status", "ok");
            reply(exchange, 200, response);
        } catch (SQLException failure) { reply(exchange, 503, error("unavailable")); }
    }

    private void create(HttpExchange exchange, String keyHash, String requestId) throws Exception {
        long now = System.currentTimeMillis();
        if (!freshRequestId(requestId, now)) { reply(exchange, 400, error("stale_request_id")); return; }
        String state = randomHex(32);
        try (Connection db = open()) {
            begin(db);
            try {
                long credits;
                try (PreparedStatement query = db.prepareStatement("SELECT credits FROM pb_keys WHERE key_hash=? AND enabled=1")) {
                    query.setString(1, keyHash);
                    try (ResultSet result = query.executeQuery()) {
                        if (!result.next()) { rollback(db); reply(exchange, 401, error("invalid_key")); return; }
                        credits = result.getLong(1);
                    }
                }
                String existingState = null;
                try (PreparedStatement query = db.prepareStatement("SELECT oauth_state FROM pb_requests WHERE request_id=? AND key_hash=?")) {
                    query.setString(1, requestId); query.setString(2, keyHash);
                    try (ResultSet result = query.executeQuery()) { if (result.next()) existingState = result.getString(1); }
                }
                if (existingState == null) {
                    // BEGIN IMMEDIATE serializes creators; pending requests reserve capacity without billing yet.
                    if (credits <= activeReservations(db, keyHash, now)) {
                        rollback(db); reply(exchange, 402, error("no_credits")); return;
                    }
                    try (PreparedStatement query = db.prepareStatement("SELECT request_id FROM pb_requests WHERE request_id=?")) {
                        query.setString(1, requestId);
                        try (ResultSet result = query.executeQuery()) {
                            if (result.next()) { rollback(db); reply(exchange, 409, error("request_id_owned_by_other_key")); return; }
                        }
                    }
                    try (PreparedStatement insert = db.prepareStatement("INSERT INTO pb_requests(request_id,key_hash,oauth_state,status,created_at) VALUES (?,?,?,'pending',?)")) {
                        insert.setString(1, requestId); insert.setString(2, keyHash); insert.setString(3, state); insert.setLong(4, now);
                        insert.executeUpdate();
                    }
                } else state = existingState;
                commit(db);
            } catch (Exception error) { rollback(db); throw error; }
        }
        JsonObject response = new JsonObject();
        response.addProperty("verificationUrl", authorizeUrl(state));
        response.addProperty("pollUrl", publicBase.resolve("/v1/requests/" + requestId).toString());
        response.addProperty("expiresIn", 300);
        reply(exchange, 200, response);
    }

    private void poll(HttpExchange exchange, String keyHash, String requestId) throws Exception {
        try (Connection db = open(); PreparedStatement query = db.prepareStatement(
                "SELECT r.status,r.result_until,r.uid,r.profiles_json,r.created_at FROM pb_requests r "
                        + "JOIN pb_keys k ON k.key_hash=r.key_hash AND k.enabled=1 "
                        + "WHERE r.request_id=? AND r.key_hash=?")) {
            query.setString(1, requestId); query.setString(2, keyHash);
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) { reply(exchange, 404, error("not_found")); return; }
                String status = row.getString(1);
                long until = row.getLong(2);
                if ("success".equals(status) && until <= System.currentTimeMillis()) status = "expired";
                if ("pending".equals(status) && row.getLong(5) + REQUEST_TTL <= System.currentTimeMillis()) status = "expired";
                JsonObject response = new JsonObject();
                response.addProperty("status", status);
                if ("success".equals(status)) {
                    JsonObject result = new JsonObject();
                    result.addProperty("uid", row.getLong(3));
                    result.add("profiles", new JsonParser().parse(row.getString(4)));
                    response.add("result", result);
                }
                reply(exchange, 200, response);
            }
        }
    }

    private void callback(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) { reply(exchange, 405, error("method_not_allowed")); return; }
        Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
        String state = query.get("state"), code = query.get("code");
        if (state == null || !state.matches("[0-9a-f]{64}")) {
            reply(exchange, 400, error("invalid_callback")); return;
        }
        if (query.containsKey("error")) {
            try (Connection db = open(); PreparedStatement update = db.prepareStatement(
                    "UPDATE pb_requests SET status='denied' WHERE oauth_state=? AND status='pending' AND created_at>=?")) {
                update.setString(1, state);
                update.setLong(2, System.currentTimeMillis() - REQUEST_TTL);
                if (update.executeUpdate() != 1) { reply(exchange, 400, error("expired_or_replayed_state")); return; }
                reply(exchange, 200, message(browserMessages.getProperty("cancelled")));
            } catch (SQLException failure) { reply(exchange, 503, error("unavailable")); }
            return;
        }
        if (code == null || code.isEmpty()) { reply(exchange, 400, error("invalid_callback")); return; }
        try {
            String requestId;
            try (Connection db = open(); PreparedStatement find = db.prepareStatement("SELECT request_id,status,created_at FROM pb_requests WHERE oauth_state=?")) {
                find.setString(1, state);
                try (ResultSet row = find.executeQuery()) {
                    if (!row.next() || !"pending".equals(row.getString(2)) || row.getLong(3) + REQUEST_TTL < System.currentTimeMillis()) {
                        reply(exchange, 400, error("expired_or_replayed_state")); return;
                    }
                    requestId = row.getString(1);
                }
            }
            JsonObject token = LittleSkinHttp.object(LittleSkinHttp.request("https://littleskin.cn/oauth/token", "POST", null,
                    "grant_type=authorization_code&client_id=" + encode(clientId) + "&client_secret=" + encode(clientSecret)
                            + "&redirect_uri=" + encode(publicBase.resolve("/oauth/callback").toString()) + "&code=" + encode(code)));
            String access = token.get("access_token").getAsString();
            VerifiedAccount account = LittleSkinHttp.readAccount(access);
            settle(requestId, state, account);
            reply(exchange, 200, message(browserMessages.getProperty("completed")));
        } catch (Exception error) {
            reply(exchange, 502, error("verification_failed"));
        }
    }

    void settle(String requestId, String state, VerifiedAccount account) throws SQLException {
        try (Connection db = open()) {
            begin(db);
            try {
                // Debit and the short-lived result commit together. A replay cannot debit after status leaves pending.
                String keyHash;
                try (PreparedStatement find = db.prepareStatement("SELECT key_hash FROM pb_requests WHERE request_id=? AND oauth_state=? AND status='pending' AND created_at>=?")) {
                    find.setString(1, requestId); find.setString(2, state); find.setLong(3, System.currentTimeMillis() - REQUEST_TTL);
                    try (ResultSet row = find.executeQuery()) {
                        if (!row.next()) throw new SQLException("Request no longer pending");
                        keyHash = row.getString(1);
                    }
                }
                try (PreparedStatement debit = db.prepareStatement("UPDATE pb_keys SET credits=credits-1 WHERE key_hash=? AND enabled=1 AND credits>0")) {
                    debit.setString(1, keyHash);
                    if (debit.executeUpdate() != 1) throw new SQLException("No credits");
                }
                JsonArray profiles = new JsonArray();
                for (UUID profile : account.profiles()) profiles.add(profile.toString());
                try (PreparedStatement update = db.prepareStatement("UPDATE pb_requests SET status='success',result_until=?,uid=?,profiles_json=? WHERE request_id=? AND status='pending'")) {
                    update.setLong(1, System.currentTimeMillis() + RESULT_TTL);
                    update.setLong(2, account.uid());
                    update.setString(3, profiles.toString());
                    update.setString(4, requestId);
                    if (update.executeUpdate() != 1) throw new SQLException("Request no longer pending");
                }
                commit(db);
            } catch (SQLException error) { rollback(db); throw error; }
        }
    }

    private void cleanExpired() {
        try (Connection db = open()) {
            long now = System.currentTimeMillis();
            try (PreparedStatement clear = db.prepareStatement("UPDATE pb_requests SET profiles_json=NULL,uid=NULL WHERE status='success' AND result_until<?")) {
                clear.setLong(1, now); clear.executeUpdate();
            }
            try (PreparedStatement delete = db.prepareStatement("DELETE FROM pb_requests WHERE created_at<?")) {
                delete.setLong(1, now - TimeUnit.DAYS.toMillis(180)); delete.executeUpdate();
            }
        } catch (SQLException ignored) { /* Next scheduled sweep retries; do not log account payloads. */ }
    }

    private String authorizeUrl(String state) {
        return "https://littleskin.cn/oauth/authorize?client_id=" + encode(clientId)
                + "&redirect_uri=" + encode(publicBase.resolve("/oauth/callback").toString())
                + "&response_type=code&scope=" + encode("User.Read Yggdrasil.PlayerProfiles.Read")
                + "&state=" + state;
    }

    private static void begin(Connection db) throws SQLException { try (Statement s = db.createStatement()) { s.execute("BEGIN IMMEDIATE"); } }
    private static long activeReservations(Connection db, String keyHash, long now) throws SQLException {
        try (PreparedStatement count = db.prepareStatement(
                "SELECT COUNT(*) FROM pb_requests WHERE key_hash=? AND status='pending' AND created_at>=?")) {
            count.setString(1, keyHash);
            count.setLong(2, now - REQUEST_TTL);
            try (ResultSet row = count.executeQuery()) { return row.next() ? row.getLong(1) : 0; }
        }
    }
    private static void commit(Connection db) throws SQLException { try (Statement s = db.createStatement()) { s.execute("COMMIT"); } }
    private static void rollback(Connection db) throws SQLException { try (Statement s = db.createStatement()) { s.execute("ROLLBACK"); } }
    private static boolean freshRequestId(String id, long now) {
        // The timestamp survives deletion of old dedup rows: an ancient ID cannot become a fresh request.
        if (id == null || !id.matches("[0-9]{13}-[0-9a-f]{48}")) return false;
        try { return Math.abs(now - Long.parseLong(id.substring(0, 13))) <= REQUEST_TTL; }
        catch (NumberFormatException error) { return false; }
    }
    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("Missing " + name);
        return value;
    }
    private static Properties loadBrowserMessages() throws IOException {
        Properties values = new Properties();
        try (InputStream bundled = PublicBackend.class.getResourceAsStream("/backend-messages.properties")) {
            if (bundled == null) throw new IOException("Bundled backend-messages.properties is missing");
            values.load(new InputStreamReader(bundled, StandardCharsets.UTF_8));
        }
        String path = System.getenv("LITTLESLOT_BACKEND_MESSAGES_FILE");
        if (path != null && !path.trim().isEmpty()) {
            Properties custom = new Properties();
            try (InputStream input = Files.newInputStream(Paths.get(path))) {
                custom.load(new InputStreamReader(input, StandardCharsets.UTF_8));
            }
            values.putAll(custom);
        }
        if (values.getProperty("cancelled") == null || values.getProperty("completed") == null)
            throw new IOException("Backend browser messages are incomplete");
        return values;
    }
    private static String newKey() { return "ls_" + randomHex(32); }
    private static String randomHex(int bytes) {
        byte[] value = new byte[bytes]; RANDOM.nextBytes(value);
        StringBuilder text = new StringBuilder(bytes * 2);
        for (byte item : value) text.append(String.format("%02x", item & 0xff));
        return text.toString();
    }
    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder text = new StringBuilder();
            for (byte item : digest) text.append(String.format("%02x", item & 0xff));
            return text.toString();
        } catch (Exception impossible) { throw new AssertionError(impossible); }
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
    private static InputStream limited(InputStream input, int maxBytes) { return new java.io.FilterInputStream(input) {
        int remaining = maxBytes;
        @Override public int read() throws IOException { if (remaining-- <= 0) throw new IOException("body too large"); return super.read(); }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            if (remaining <= 0) throw new IOException("body too large");
            int count = super.read(bytes, offset, Math.min(length, remaining));
            if (count > 0) remaining -= count;
            return count;
        }
    }; }
    private static JsonObject error(String code) { JsonObject value = new JsonObject(); value.addProperty("error", code); return value; }
    private static JsonObject message(String text) { JsonObject value = new JsonObject(); value.addProperty("message", text); return value; }
    private static void reply(HttpExchange exchange, int status, JsonObject json) throws IOException {
        byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
    }
}
