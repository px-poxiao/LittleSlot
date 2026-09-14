package dev.littleslot.core;

import java.time.Duration;

public final class SlotSettings {
    private final int defaultLimit;
    private final Duration snapshotTtl;
    private final Duration releaseCooldown;

    public SlotSettings(int defaultLimit, Duration snapshotTtl, Duration releaseCooldown) {
        if (defaultLimit < -1 || snapshotTtl == null || snapshotTtl.isZero() || snapshotTtl.isNegative()
                || releaseCooldown == null || releaseCooldown.isNegative()) {
            throw new IllegalArgumentException("Invalid slot settings");
        }
        this.defaultLimit = defaultLimit;
        this.snapshotTtl = snapshotTtl;
        this.releaseCooldown = releaseCooldown;
    }

    public static SlotSettings defaults() {
        return new SlotSettings(2, Duration.ofDays(30), Duration.ofDays(7));
    }

    public int defaultLimit() { return defaultLimit; }
    public Duration snapshotTtl() { return snapshotTtl; }
    public Duration releaseCooldown() { return releaseCooldown; }
}
