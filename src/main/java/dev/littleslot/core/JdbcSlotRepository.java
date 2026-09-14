package dev.littleslot.core;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Portable JDBC storage. A locked scope row serializes policy changes across MySQL instances. */
public final class JdbcSlotRepository implements SlotRepository {
    public enum Dialect { SQLITE, MYSQL }

    private final String url;
    private final String user;
    private final String password;
    private final Dialect dialect;

    public JdbcSlotRepository(String url, String user, String password, Dialect dialect) throws SlotException {
        if (url == null || dialect == null) throw new IllegalArgumentException("database");
        this.url = url;
        this.user = user;
        this.password = password;
        this.dialect = dialect;
        try {
            Class.forName(dialect == Dialect.SQLITE ? "org.sqlite.JDBC" : "com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException error) {
            throw new SlotException("Missing JDBC driver for " + dialect, error);
        }
        initialize();
    }

    private Connection open() throws SQLException {
        Connection connection = dialect == Dialect.SQLITE ? DriverManager.getConnection(url)
                : DriverManager.getConnection(url, user, password);
        if (dialect == Dialect.SQLITE) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA busy_timeout=5000");
            }
        }
        return connection;
    }

    private void initialize() throws SlotException {
        String text = dialect == Dialect.SQLITE ? "TEXT" : "VARCHAR(128)";
        String uuid = dialect == Dialect.SQLITE ? "TEXT" : "CHAR(36)";
        String auditId = dialect == Dialect.SQLITE ? "INTEGER PRIMARY KEY AUTOINCREMENT" : "BIGINT PRIMARY KEY AUTO_INCREMENT";
        String[] ddl = {
            "CREATE TABLE IF NOT EXISTS ls_schema (id INTEGER PRIMARY KEY, version INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS ls_scopes (scope " + text + " PRIMARY KEY)",
            "CREATE TABLE IF NOT EXISTS ls_accounts (scope " + text + " NOT NULL, uid BIGINT NOT NULL, verified_at BIGINT NOT NULL DEFAULT 0, verified_until BIGINT NOT NULL DEFAULT 0, limit_override INTEGER NULL, PRIMARY KEY(scope,uid))",
            "CREATE TABLE IF NOT EXISTS ls_ownership (scope " + text + " NOT NULL, profile " + uuid + " NOT NULL, uid BIGINT NOT NULL, verified_at BIGINT NOT NULL, PRIMARY KEY(scope,profile))",
            "CREATE INDEX IF NOT EXISTS ls_ownership_by_uid ON ls_ownership(scope,uid)",
            "CREATE TABLE IF NOT EXISTS ls_slots (scope " + text + " NOT NULL, profile " + uuid + " NOT NULL, uid BIGINT NOT NULL, allocated_at BIGINT NOT NULL, PRIMARY KEY(scope,profile))",
            "CREATE INDEX IF NOT EXISTS ls_slots_by_uid ON ls_slots(scope,uid)",
            "CREATE TABLE IF NOT EXISTS ls_blocks (scope " + text + " NOT NULL, profile " + uuid + " NOT NULL, blocked_at BIGINT NOT NULL, PRIMARY KEY(scope,profile))",
            "CREATE TABLE IF NOT EXISTS ls_cooldowns (scope " + text + " NOT NULL, uid BIGINT NOT NULL, last_release BIGINT NOT NULL, PRIMARY KEY(scope,uid))",
            "CREATE TABLE IF NOT EXISTS ls_audit (id " + auditId + ", scope " + text + " NOT NULL, action VARCHAR(32) NOT NULL, uid BIGINT NULL, profile " + uuid + " NULL, at_millis BIGINT NOT NULL)"
            ,"CREATE INDEX IF NOT EXISTS ls_audit_by_scope ON ls_audit(scope,id)"
        };
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            for (String sql : ddl) {
                if (dialect == Dialect.MYSQL && sql.startsWith("CREATE INDEX IF NOT EXISTS")) {
                    String mysqlSql = sql.replace("IF NOT EXISTS ", "");
                    try { statement.execute(mysqlSql); }
                    catch (SQLException alreadyExists) {
                        if (alreadyExists.getErrorCode() != 1061) throw alreadyExists;
                    }
                } else statement.execute(sql);
            }
            try (ResultSet result = statement.executeQuery("SELECT version FROM ls_schema WHERE id=1")) {
                if (result.next()) {
                    if (result.getInt(1) != 1) throw new SQLException("Unsupported schema version");
                } else {
                    statement.executeUpdate(dialect == Dialect.SQLITE
                            ? "INSERT OR IGNORE INTO ls_schema(id,version) VALUES (1,1)"
                            : "INSERT IGNORE INTO ls_schema(id,version) VALUES (1,1)");
                }
            }
        } catch (SQLException e) {
            throw new SlotException("Cannot initialize LittleSlot database", e);
        }
    }

    @Override
    public <T> T transact(String scope, Work<T> work) throws SlotException {
        if (scope == null || !scope.matches("[A-Za-z0-9_.:-]{1,128}") || work == null) throw new IllegalArgumentException("transaction");
        try (Connection connection = open()) {
            if (dialect == Dialect.SQLITE) {
                try (Statement statement = connection.createStatement()) { statement.execute("BEGIN IMMEDIATE"); }
            } else connection.setAutoCommit(false);
            try {
                lockScope(connection, scope);
                T value = work.run(new JdbcTx(connection, scope));
                if (dialect == Dialect.SQLITE) {
                    try (Statement statement = connection.createStatement()) { statement.execute("COMMIT"); }
                } else connection.commit();
                return value;
            } catch (Exception e) {
                try {
                    if (dialect == Dialect.SQLITE) {
                        try (Statement statement = connection.createStatement()) { statement.execute("ROLLBACK"); }
                    } else connection.rollback();
                } catch (SQLException rollback) { e.addSuppressed(rollback); }
                throw e;
            }
        } catch (Exception e) {
            throw new SlotException("LittleSlot transaction failed", e);
        }
    }

    @Override public long latestAuditId(String scope) throws SlotException {
        try (Connection db = open(); PreparedStatement query = db.prepareStatement("SELECT COALESCE(MAX(id),0) FROM ls_audit WHERE scope=?")) {
            query.setString(1, scope);
            try (ResultSet row = query.executeQuery()) { return row.next() ? row.getLong(1) : 0; }
        } catch (SQLException error) { throw new SlotException("Cannot read audit cursor", error); }
    }

    @Override public List<AuditEvent> auditAfter(String scope, long afterId, int maxRows) throws SlotException {
        if (maxRows < 1 || maxRows > 1000) throw new IllegalArgumentException("maxRows");
        List<AuditEvent> events = new ArrayList<AuditEvent>();
        try (Connection db = open(); PreparedStatement query = db.prepareStatement(
                "SELECT id,action,uid,profile,at_millis FROM ls_audit WHERE scope=? AND id>? ORDER BY id LIMIT ?")) {
            query.setString(1, scope); query.setLong(2, afterId); query.setInt(3, maxRows);
            try (ResultSet row = query.executeQuery()) {
                while (row.next()) {
                    long uid = row.getLong(3);
                    Long owner = row.wasNull() ? null : uid;
                    String raw = row.getString(4);
                    events.add(new AuditEvent(row.getLong(1), row.getString(2), owner,
                            raw == null ? null : UUID.fromString(raw), row.getLong(5)));
                }
            }
            return events;
        } catch (SQLException error) { throw new SlotException("Cannot read audit events", error); }
    }

    private void lockScope(Connection connection, String scope) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                dialect == Dialect.SQLITE ? "INSERT OR IGNORE INTO ls_scopes(scope) VALUES (?)" : "INSERT IGNORE INTO ls_scopes(scope) VALUES (?)")) {
            insert.setString(1, scope);
            insert.executeUpdate();
        }
        if (dialect == Dialect.MYSQL) {
            try (PreparedStatement lock = connection.prepareStatement("SELECT scope FROM ls_scopes WHERE scope=? FOR UPDATE")) {
                lock.setString(1, scope);
                try (ResultSet row = lock.executeQuery()) {
                    if (!row.next()) throw new SQLException("Scope lock unavailable");
                }
            }
        }
    }

    private final class JdbcTx implements Tx {
        private final Connection connection;
        private final String scope;

        private JdbcTx(Connection connection, String scope) { this.connection = connection; this.scope = scope; }

        private Long scalarLong(String sql, Object... args) throws SQLException {
            try (PreparedStatement statement = prepare(sql, args); ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getLong(1) : null;
            }
        }

        private PreparedStatement prepare(String sql, Object... args) throws SQLException {
            PreparedStatement statement = connection.prepareStatement(sql);
            statement.setString(1, scope);
            for (int i = 0; i < args.length; i++) statement.setObject(i + 2, args[i]);
            return statement;
        }

        private int update(String sql, Object... args) throws SQLException {
            try (PreparedStatement statement = prepare(sql, args)) { return statement.executeUpdate(); }
        }

        private int updateExact(String sql, Object... args) throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
                return statement.executeUpdate();
            }
        }

        private void ensureAccount(long uid) throws SQLException {
            update(dialect == Dialect.SQLITE ? "INSERT OR IGNORE INTO ls_accounts(scope,uid) VALUES (?,?)"
                    : "INSERT IGNORE INTO ls_accounts(scope,uid) VALUES (?,?)", uid);
        }

        @Override public Long owner(UUID profile) throws SQLException {
            return scalarLong("SELECT uid FROM ls_ownership WHERE scope=? AND profile=?", profile.toString());
        }

        @Override public long ownerVerifiedAt(UUID profile) throws SQLException {
            Long value = scalarLong("SELECT verified_at FROM ls_ownership WHERE scope=? AND profile=?", profile.toString());
            return value == null ? 0 : value;
        }

        @Override public long verifiedUntil(long uid) throws SQLException {
            Long value = scalarLong("SELECT verified_until FROM ls_accounts WHERE scope=? AND uid=?", uid);
            return value == null ? 0 : value;
        }

        @Override public long verifiedAt(long uid) throws SQLException {
            Long value = scalarLong("SELECT verified_at FROM ls_accounts WHERE scope=? AND uid=?", uid);
            return value == null ? 0 : value;
        }

        @Override public Integer limitOverride(long uid) throws SQLException {
            try (PreparedStatement statement = prepare("SELECT limit_override FROM ls_accounts WHERE scope=? AND uid=?", uid);
                 ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                int value = row.getInt(1);
                return row.wasNull() ? null : value;
            }
        }

        @Override public int allocatedCount(long uid) throws SQLException {
            Long value = scalarLong("SELECT COUNT(*) FROM ls_slots WHERE scope=? AND uid=?", uid);
            return value == null ? 0 : value.intValue();
        }

        @Override public boolean allocated(long uid, UUID profile) throws SQLException {
            return scalarLong("SELECT uid FROM ls_slots WHERE scope=? AND profile=? AND uid=?", profile.toString(), uid) != null;
        }

        @Override public boolean blocked(UUID profile) throws SQLException {
            return scalarLong("SELECT blocked_at FROM ls_blocks WHERE scope=? AND profile=?", profile.toString()) != null;
        }

        @Override public void allocate(long uid, UUID profile, long now) throws SQLException {
            updateExact("INSERT INTO ls_slots(scope,profile,uid,allocated_at) VALUES (?,?,?,?)", scope, profile.toString(), uid, now);
        }

        @Override public void removeAllocation(UUID profile) throws SQLException {
            update("DELETE FROM ls_slots WHERE scope=? AND profile=?", profile.toString());
        }

        @Override public void putLimit(long uid, Integer limit) throws SQLException {
            ensureAccount(uid);
            updateExact("UPDATE ls_accounts SET limit_override=? WHERE scope=? AND uid=?", limit, scope, uid);
        }

        @Override public void setBlocked(UUID profile, boolean blocked, long now) throws SQLException {
            if (blocked) {
                updateExact(dialect == Dialect.SQLITE ? "INSERT OR REPLACE INTO ls_blocks(scope,profile,blocked_at) VALUES (?,?,?)"
                        : "REPLACE INTO ls_blocks(scope,profile,blocked_at) VALUES (?,?,?)", scope, profile.toString(), now);
            } else update("DELETE FROM ls_blocks WHERE scope=? AND profile=?", profile.toString());
        }

        @Override public long lastSelfRelease(long uid) throws SQLException {
            Long value = scalarLong("SELECT last_release FROM ls_cooldowns WHERE scope=? AND uid=?", uid);
            return value == null ? 0 : value;
        }

        @Override public void setLastSelfRelease(long uid, long now) throws SQLException {
            updateExact(dialect == Dialect.SQLITE ? "INSERT OR REPLACE INTO ls_cooldowns(scope,uid,last_release) VALUES (?,?,?)"
                    : "REPLACE INTO ls_cooldowns(scope,uid,last_release) VALUES (?,?,?)", scope, uid, now);
        }

        @Override public Set<UUID> ownedProfiles(long uid) throws SQLException {
            Set<UUID> found = new HashSet<UUID>();
            try (PreparedStatement statement = prepare("SELECT profile FROM ls_ownership WHERE scope=? AND uid=?", uid);
                 ResultSet row = statement.executeQuery()) {
                while (row.next()) found.add(UUID.fromString(row.getString(1)));
            }
            return found;
        }

        @Override public void replaceOwnership(long uid, Set<UUID> profiles, long verifiedAt, long until) throws SQLException {
            ensureAccount(uid);
            update("DELETE FROM ls_ownership WHERE scope=? AND uid=?", uid);
            for (UUID profile : profiles) {
                update("DELETE FROM ls_ownership WHERE scope=? AND profile=?", profile.toString());
                updateExact("INSERT INTO ls_ownership(scope,profile,uid,verified_at) VALUES (?,?,?,?)", scope, profile.toString(), uid, verifiedAt);
            }
            updateExact("UPDATE ls_accounts SET verified_at=?, verified_until=? WHERE scope=? AND uid=?", verifiedAt, until, scope, uid);
        }

        @Override public void audit(String action, Long uid, UUID profile, long now) throws SQLException {
            updateExact("INSERT INTO ls_audit(scope,action,uid,profile,at_millis) VALUES (?,?,?,?,?)",
                    scope, action, uid, profile == null ? null : profile.toString(), now);
        }
    }
}
