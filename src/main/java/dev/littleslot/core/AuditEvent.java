package dev.littleslot.core;

import java.util.UUID;

public final class AuditEvent {
    private final long id;
    private final String action;
    private final Long uid;
    private final UUID profile;
    private final long atMillis;

    public AuditEvent(long id, String action, Long uid, UUID profile, long atMillis) {
        this.id = id;
        this.action = action;
        this.uid = uid;
        this.profile = profile;
        this.atMillis = atMillis;
    }
    public long id() { return id; }
    public String action() { return action; }
    public Long uid() { return uid; }
    public UUID profile() { return profile; }
    public long atMillis() { return atMillis; }
}
