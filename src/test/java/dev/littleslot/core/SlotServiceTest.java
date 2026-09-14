package dev.littleslot.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlotServiceTest {
    @TempDir Path temporary;

    private static final class MutableClock extends Clock {
        long now = 1_800_000_000_000L;
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now); }
    }

    private SlotService service(Path db, MutableClock clock) throws Exception {
        return new SlotService(new JdbcSlotRepository("jdbc:sqlite:" + db, null, null, JdbcSlotRepository.Dialect.SQLITE),
                SlotSettings.defaults(), clock);
    }

    private static VerifiedAccount verified(long uid, long at, UUID... profiles) {
        Set<UUID> values = new HashSet<UUID>(Arrays.asList(profiles));
        return new VerifiedAccount(uid, values, at);
    }

    @Test void fullAccountSnapshotDoesNotAllocateUnusedProfiles() throws Exception {
        MutableClock clock = new MutableClock();
        SlotService slots = service(temporary.resolve("slots.db"), clock);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        assertEquals(Decision.BIND_REQUIRED, slots.join("single", a).decision());
        assertEquals(Decision.ALLOW_NEW, slots.verifyAndJoin("single", a, verified(1, clock.now, a, b, c)).decision());
        assertEquals(Decision.ALLOW_NEW, slots.join("single", b).decision());
        assertEquals(Decision.FULL, slots.join("single", c).decision());
        assertEquals(Decision.ALLOW_EXISTING, slots.join("single", a).decision());
        assertEquals(Decision.BIND_REQUIRED, slots.join("other", a).decision());
    }

    @Test void unrelatedOAuthResultCannotReplaceOwnershipOrAllocateSlot() throws Exception {
        MutableClock clock = new MutableClock();
        SlotService slots = service(temporary.resolve("mismatch.db"), clock);
        UUID joining = UUID.randomUUID(), unrelated = UUID.randomUUID();
        assertEquals(Decision.ALLOW_NEW,
                slots.verifyAndJoin("s", joining, verified(1, clock.now, joining)).decision());
        clock.now += 1000;
        assertEquals(Decision.ACCOUNT_MISMATCH,
                slots.verifyAndJoin("s", joining, verified(2, clock.now, unrelated)).decision());
        assertEquals(Decision.ALLOW_EXISTING, slots.join("s", joining).decision());
        assertEquals(Long.valueOf(1), slots.join("s", joining).uid());
        assertEquals(Decision.BIND_REQUIRED, slots.join("s", unrelated).decision());
    }

    @Test void releaseCooldownAndAdminReleaseAreDistinct() throws Exception {
        MutableClock clock = new MutableClock();
        SlotService slots = service(temporary.resolve("release.db"), clock);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        slots.verifyAndJoin("s", a, verified(8, clock.now, a, b));
        assertEquals(Decision.ALLOW_EXISTING, slots.selfRelease("s", 8, a));
        assertEquals(Decision.ALLOW_NEW, slots.join("s", b).decision());
        assertEquals(Decision.COOLDOWN, slots.selfRelease("s", 8, b));
        assertTrue(slots.adminRelease("s", b));
        assertEquals(Decision.ALLOW_NEW, slots.join("s", a).decision());
        clock.now += Duration.ofDays(7).toMillis();
        assertEquals(Decision.ALLOW_EXISTING, slots.selfRelease("s", 8, a));
    }

    @Test void blockedAndExistingSlotsSurviveLimitReduction() throws Exception {
        MutableClock clock = new MutableClock();
        SlotService slots = service(temporary.resolve("policy.db"), clock);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        slots.verifyAndJoin("s", a, verified(9, clock.now, a, b, c));
        slots.join("s", b);
        slots.setLimit("s", 9, 0);
        assertEquals(Decision.ALLOW_EXISTING, slots.join("s", a).decision());
        assertEquals(Decision.FULL, slots.join("s", c).decision());
        slots.setBlocked("s", a, true);
        assertEquals(Decision.BLOCKED, slots.join("s", a).decision());
        slots.setBlocked("s", a, false);
        assertEquals(Decision.ALLOW_EXISTING, slots.join("s", a).decision());
        slots.setLimit("s", 9, -1);
        assertEquals(Decision.ALLOW_NEW, slots.join("s", c).decision());
    }

    @Test void newSnapshotRevokesRemovedProfileAndOldResultCannotOverwriteIt() throws Exception {
        MutableClock clock = new MutableClock();
        SlotService slots = service(temporary.resolve("snapshot.db"), clock);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        slots.verifyAndJoin("s", a, verified(9, clock.now, a, b));
        slots.join("s", b);
        long previous = clock.now;
        clock.now += 1000;
        slots.verifyAndJoin("s", a, verified(9, clock.now, a));
        assertEquals(Decision.BIND_REQUIRED, slots.join("s", b).decision());
        slots.verifyAndJoin("s", b, verified(9, previous, a, b));
        assertEquals(Decision.BIND_REQUIRED, slots.join("s", b).decision());
    }

    @Test void expiredSnapshotRequiresFreshVerificationWithoutReleasingSlot() throws Exception {
        MutableClock clock = new MutableClock();
        SlotService slots = service(temporary.resolve("expiry.db"), clock);
        UUID a = UUID.randomUUID();
        slots.verifyAndJoin("s", a, verified(7, clock.now, a));
        clock.now += Duration.ofDays(30).toMillis();
        assertEquals(Decision.SNAPSHOT_EXPIRED, slots.join("s", a).decision());
        assertEquals(Decision.ALLOW_EXISTING, slots.verifyAndJoin("s", a, verified(7, clock.now, a)).decision());
    }

    @Test void newOwnerCanClaimTransferredProfileButOlderResultCannotTakeItBack() throws Exception {
        MutableClock clock = new MutableClock();
        SlotService slots = service(temporary.resolve("transfer.db"), clock);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        slots.verifyAndJoin("s", a, verified(1, clock.now, a, b));
        slots.join("s", b);
        long oldTime = clock.now;
        clock.now += 1000;
        assertEquals(Decision.ALLOW_NEW, slots.verifyAndJoin("s", b, verified(2, clock.now, b)).decision());
        assertEquals(Decision.ACCOUNT_MISMATCH,
                slots.verifyAndJoin("s", b, verified(1, oldTime, a, b)).decision());
        assertEquals(Long.valueOf(2), slots.join("s", b).uid());
    }

    @Test void separateRepositoryInstancesCannotClaimLastSlotTwice() throws Exception {
        MutableClock clock = new MutableClock();
        Path db = temporary.resolve("parallel.db");
        SlotService one = service(db, clock), two = service(db, clock);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        one.verifyAndJoin("s", a, verified(7, clock.now, a, b, c));
        one.setLimit("s", 7, 2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Decision> first = workers.submit(() -> { start.await(); return one.join("s", b).decision(); });
            Future<Decision> second = workers.submit(() -> { start.await(); return two.join("s", c).decision(); });
            start.countDown();
            Set<Decision> results = new HashSet<Decision>(Arrays.asList(first.get(), second.get()));
            assertEquals(new HashSet<Decision>(Arrays.asList(Decision.ALLOW_NEW, Decision.FULL)), results);
        } finally { workers.shutdownNow(); }
    }

    @Test void anotherRepositoryInstanceCanObserveRevocationEvents() throws Exception {
        MutableClock clock = new MutableClock();
        Path db = temporary.resolve("events.db");
        JdbcSlotRepository writer = new JdbcSlotRepository("jdbc:sqlite:" + db, null, null, JdbcSlotRepository.Dialect.SQLITE);
        JdbcSlotRepository reader = new JdbcSlotRepository("jdbc:sqlite:" + db, null, null, JdbcSlotRepository.Dialect.SQLITE);
        SlotService slots = new SlotService(writer, SlotSettings.defaults(), clock);
        UUID profile = UUID.randomUUID();
        long cursor = reader.latestAuditId("s");
        slots.verifyAndJoin("s", profile, verified(3, clock.now, profile));
        slots.setBlocked("s", profile, true);
        assertTrue(reader.auditAfter("s", cursor, 100).stream().anyMatch(event ->
                "block".equals(event.action()) && profile.equals(event.profile())));
        assertEquals(0, reader.auditAfter("another", cursor, 100).size());
    }
}
