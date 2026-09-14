package dev.littleslot.core;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;

/** Offline, non-destructive migration between compatible SQLite/MySQL schemas. */
public final class DatabaseMigrator {
    public static final class Endpoint {
        public final JdbcSlotRepository.Dialect dialect;
        public final String url;
        public final String user;
        public final String password;
        public Endpoint(JdbcSlotRepository.Dialect dialect, String url, String user, String password) {
            if (dialect == null || url == null || url.isEmpty()) throw new IllegalArgumentException("endpoint");
            this.dialect = dialect; this.url = url; this.user = user; this.password = password;
        }
        Connection open() throws SQLException {
            return dialect == JdbcSlotRepository.Dialect.SQLITE ? DriverManager.getConnection(url)
                    : DriverManager.getConnection(url, user, password);
        }
    }

    private static final class Table {
        final String name;
        final String[] columns;
        final boolean[] numbers;
        Table(String name, String columns, String numeric) {
            this.name = name;
            this.columns = columns.split(",");
            this.numbers = new boolean[this.columns.length];
            for (int i = 0; i < this.columns.length; i++) numbers[i] = Arrays.asList(numeric.split(",")).contains(this.columns[i]);
        }
        String select() { return "SELECT " + String.join(",", columns) + " FROM " + name; }
        String insert() {
            String[] placeholders = new String[columns.length]; Arrays.fill(placeholders, "?");
            return "INSERT INTO " + name + "(" + String.join(",", columns) + ") VALUES (" + String.join(",", placeholders) + ")";
        }
    }

    private static final Table[] TABLES = {
        new Table("ls_scopes", "scope", ""),
        new Table("ls_accounts", "scope,uid,verified_at,verified_until,limit_override", "uid,verified_at,verified_until,limit_override"),
        new Table("ls_ownership", "scope,profile,uid,verified_at", "uid,verified_at"),
        new Table("ls_slots", "scope,profile,uid,allocated_at", "uid,allocated_at"),
        new Table("ls_blocks", "scope,profile,blocked_at", "blocked_at"),
        new Table("ls_cooldowns", "scope,uid,last_release", "uid,last_release"),
        new Table("ls_audit", "id,scope,action,uid,profile,at_millis", "id,uid,at_millis")
    };

    private DatabaseMigrator() { }

    /** Requires all LittleSlot instances using either endpoint to be stopped for the maintenance window. */
    public static void migrate(Endpoint source, Endpoint target, Path backup) throws Exception {
        if (source.url.equals(target.url)) throw new IllegalArgumentException("Source and target must differ");
        if (source.dialect == JdbcSlotRepository.Dialect.SQLITE && target.dialect == JdbcSlotRepository.Dialect.SQLITE
                && java.nio.file.Paths.get(source.url.substring("jdbc:sqlite:".length())).toAbsolutePath().normalize()
                .equals(java.nio.file.Paths.get(target.url.substring("jdbc:sqlite:".length())).toAbsolutePath().normalize()))
            throw new IllegalArgumentException("Source and target SQLite files must differ");
        if (Files.exists(backup)) throw new IOException("Backup path already exists");
        try { Class.forName(source.dialect == JdbcSlotRepository.Dialect.SQLITE ? "org.sqlite.JDBC" : "com.mysql.cj.jdbc.Driver"); }
        catch (ClassNotFoundException error) { throw new SQLException("Source JDBC driver missing", error); }
        new JdbcSlotRepository(target.url, target.user, target.password, target.dialect);
        Path parent = backup.toAbsolutePath().getParent();
        if (parent == null || !Files.isDirectory(parent)) throw new IOException("Backup parent missing");
        Path partial = Files.createTempFile(parent, backup.getFileName().toString() + ".", ".partial");
        try {
            // Write and publish a recoverable source snapshot before the empty target is modified.
            exportSnapshot(source, partial);
            try { Files.move(partial, backup, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(partial, backup); }
            importSnapshot(target, backup);
        } catch (Exception failure) {
            Files.deleteIfExists(partial);
            throw failure;
        }
    }

    private static void exportSnapshot(Endpoint source, Path destination) throws Exception {
        try (Connection db = source.open()) {
            if (source.dialect == JdbcSlotRepository.Dialect.MYSQL)
                db.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            db.setAutoCommit(false);
            checkSchema(db);
            try (Writer file = Files.newBufferedWriter(destination, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
                 JsonWriter json = new JsonWriter(file)) {
                json.beginObject();
                json.name("schemaVersion").value(1);
                for (Table table : TABLES) {
                    json.name(table.name).beginArray();
                    try (Statement statement = db.createStatement(); ResultSet rows = statement.executeQuery(table.select())) {
                        while (rows.next()) {
                            json.beginArray();
                            for (int i = 0; i < table.columns.length; i++) {
                                String value = rows.getString(i + 1);
                                if (value == null) json.nullValue();
                                else json.value(value);
                            }
                            json.endArray();
                        }
                    }
                    json.endArray();
                }
                json.endObject();
            }
            db.rollback(); // Read-only snapshot; never touch source rows.
        }
    }

    private static void importSnapshot(Endpoint target, Path backup) throws Exception {
        try (Connection db = target.open()) {
            db.setAutoCommit(false);
            try {
                checkSchema(db);
                // Refuse even partially populated targets; this command never merges or overwrites live data.
                for (Table table : TABLES) {
                    try (Statement statement = db.createStatement(); ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table.name)) {
                        if (!rows.next() || rows.getLong(1) != 0) throw new SQLException("Target is not empty: " + table.name);
                    }
                }
                try (Reader file = Files.newBufferedReader(backup, StandardCharsets.UTF_8); JsonReader json = new JsonReader(file)) {
                    json.beginObject();
                    if (!"schemaVersion".equals(json.nextName()) || json.nextInt() != 1) throw new IOException("Unsupported archive schema");
                    for (Table table : TABLES) {
                        if (!table.name.equals(json.nextName())) throw new IOException("Unexpected archive table");
                        json.beginArray();
                        long expected = 0;
                        try (PreparedStatement insert = db.prepareStatement(table.insert())) {
                            while (json.hasNext()) {
                                json.beginArray();
                                for (int i = 0; i < table.columns.length; i++) {
                                    if (json.peek() == com.google.gson.stream.JsonToken.NULL) {
                                        json.nextNull(); insert.setObject(i + 1, null);
                                    } else {
                                        String value = json.nextString();
                                        if (table.numbers[i]) insert.setLong(i + 1, Long.parseLong(value));
                                        else insert.setString(i + 1, value);
                                    }
                                }
                                json.endArray();
                                insert.executeUpdate();
                                expected++;
                            }
                        }
                        json.endArray();
                        try (Statement statement = db.createStatement(); ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table.name)) {
                            if (!rows.next() || rows.getLong(1) != expected) throw new SQLException("Migration count mismatch: " + table.name);
                        }
                    }
                    json.endObject();
                }
                db.commit();
            } catch (Exception failure) {
                db.rollback();
                throw failure;
            }
        }
    }

    private static void checkSchema(Connection db) throws SQLException {
        try (Statement statement = db.createStatement(); ResultSet row = statement.executeQuery("SELECT version FROM ls_schema WHERE id=1")) {
            if (!row.next() || row.getInt(1) != 1) throw new SQLException("Unsupported schema version");
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Usage: <source:sqlite|mysql> <target:sqlite|mysql> <backup.json>");
        Endpoint source = envEndpoint("SOURCE", args[0]);
        Endpoint target = envEndpoint("TARGET", args[1]);
        migrate(source, target, java.nio.file.Paths.get(args[2]));
        System.out.println("Migration finished. Backup: " + args[2]);
    }

    private static Endpoint envEndpoint(String side, String dialect) {
        String url = System.getenv("LITTLESLOT_" + side + "_URL");
        if (url == null || url.isEmpty()) throw new IllegalArgumentException("Missing " + side + " URL");
        return new Endpoint(JdbcSlotRepository.Dialect.valueOf(dialect.toUpperCase(java.util.Locale.ROOT)), url,
                System.getenv("LITTLESLOT_" + side + "_USER"), System.getenv("LITTLESLOT_" + side + "_PASSWORD"));
    }
}
