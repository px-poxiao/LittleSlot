package dev.littleslot.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.util.Arrays;
import java.util.HashSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseMigratorTest {
    @TempDir Path directory;

    @Test void backupAndImportPreserveSlotsBlocksAndScopeWithoutOverwritingTarget() throws Exception {
        Path sourceFile = directory.resolve("source.db"), targetFile = directory.resolve("target.db"), backup = directory.resolve("backup.json");
        DatabaseMigrator.Endpoint source = endpoint(sourceFile), target = endpoint(targetFile);
        JdbcSlotRepository sourceRepository = new JdbcSlotRepository(source.url, null, null, JdbcSlotRepository.Dialect.SQLITE);
        SlotService original = new SlotService(sourceRepository,
                SlotSettings.defaults(), Clock.systemUTC());
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        original.verifyAndJoin("group", a, new VerifiedAccount(12, new HashSet<UUID>(Arrays.asList(a, b)), System.currentTimeMillis()));
        original.setLimit("group", 12, 1);
        original.setBlocked("group", b, true);
        sourceRepository.recordPremiumTimeoutChoice("group", b, "Example", System.currentTimeMillis());
        DatabaseMigrator.migrate(source, target, backup);
        assertTrue(Files.size(backup) > 100);
        SlotService imported = new SlotService(new JdbcSlotRepository(target.url, null, null, JdbcSlotRepository.Dialect.SQLITE),
                SlotSettings.defaults(), Clock.systemUTC());
        assertEquals(Decision.ALLOW_EXISTING, imported.join("group", a).decision());
        assertEquals(Decision.BLOCKED, imported.join("group", b).decision());
        assertEquals(Decision.BIND_REQUIRED, imported.join("independent", a).decision());
        imported.setBlocked("group", b, false);
        assertEquals(Decision.FULL, imported.join("group", b).decision());
        assertEquals(Decision.ALLOW_EXISTING, original.join("group", a).decision());
        try (java.sql.Connection connection = DriverManager.getConnection(target.url);
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT player_name FROM ls_premium_timeout WHERE scope='group' AND profile='" + b + "'")) {
            assertTrue(row.next());
            assertEquals("Example", row.getString(1));
        }
        assertThrows(java.sql.SQLException.class, () -> DatabaseMigrator.migrate(source, target, directory.resolve("again.json")));
    }

    @Test void existingSchemaUpgradesAndTemporaryChoiceIsDurableButNeverAFreeSlot() throws Exception {
        Path file = directory.resolve("upgrade.db");
        DatabaseMigrator.Endpoint database = endpoint(file);
        new JdbcSlotRepository(database.url, null, null, JdbcSlotRepository.Dialect.SQLITE);
        try (java.sql.Connection connection = DriverManager.getConnection(database.url);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE ls_premium_timeout");
            statement.executeUpdate("UPDATE ls_schema SET version=1 WHERE id=1");
        }
        Path migratedFile = directory.resolve("legacy-target.db");
        DatabaseMigrator.migrate(database, endpoint(migratedFile), directory.resolve("legacy-backup.json"));
        try (java.sql.Connection connection = DriverManager.getConnection("jdbc:sqlite:" + migratedFile);
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT COUNT(*) FROM ls_premium_timeout")) {
            assertTrue(row.next());
            assertEquals(0, row.getLong(1));
        }
        UUID profile = UUID.randomUUID();
        JdbcSlotRepository upgraded = new JdbcSlotRepository(database.url, null, null, JdbcSlotRepository.Dialect.SQLITE);
        upgraded.recordPremiumTimeoutChoice("group", profile, "Example", 1234);
        assertEquals(Decision.BIND_REQUIRED,
                new SlotService(upgraded, SlotSettings.defaults(), Clock.systemUTC()).join("group", profile).decision());
        try (java.sql.Connection connection = DriverManager.getConnection(database.url);
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT chosen_at FROM ls_premium_timeout WHERE profile='" + profile + "'")) {
            assertTrue(row.next());
            assertEquals(1234, row.getLong(1));
        }
        upgraded.clearPremiumTimeoutChoice("group", profile);
        try (java.sql.Connection connection = DriverManager.getConnection(database.url);
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT COUNT(*) FROM ls_premium_timeout")) {
            assertTrue(row.next());
            assertEquals(0, row.getLong(1));
        }
    }

    private static DatabaseMigrator.Endpoint endpoint(Path file) {
        return new DatabaseMigrator.Endpoint(JdbcSlotRepository.Dialect.SQLITE, "jdbc:sqlite:" + file, null, null);
    }
}
