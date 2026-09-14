package dev.littleslot.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
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
        SlotService original = new SlotService(new JdbcSlotRepository(source.url, null, null, JdbcSlotRepository.Dialect.SQLITE),
                SlotSettings.defaults(), Clock.systemUTC());
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        original.verifyAndJoin("group", a, new VerifiedAccount(12, new HashSet<UUID>(Arrays.asList(a, b)), System.currentTimeMillis()));
        original.setLimit("group", 12, 1);
        original.setBlocked("group", b, true);
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
        assertThrows(java.sql.SQLException.class, () -> DatabaseMigrator.migrate(source, target, directory.resolve("again.json")));
    }

    private static DatabaseMigrator.Endpoint endpoint(Path file) {
        return new DatabaseMigrator.Endpoint(JdbcSlotRepository.Dialect.SQLITE, "jdbc:sqlite:" + file, null, null);
    }
}
