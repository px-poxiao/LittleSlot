package dev.littleslot.core;

/** A local policy decision. Infrastructure failures are never represented as ALLOW. */
public enum Decision {
    ALLOW_EXISTING,
    ALLOW_NEW,
    BIND_REQUIRED,
    SNAPSHOT_EXPIRED,
    FULL,
    BLOCKED,
    ACCOUNT_MISMATCH,
    COOLDOWN,
    NOT_ALLOCATED
}
