package dev.littleslot.core;

public final class Admission {
    private final Decision decision;
    private final Long uid;
    private final int used;
    private final int limit;

    public Admission(Decision decision, Long uid, int used, int limit) {
        this.decision = decision;
        this.uid = uid;
        this.used = used;
        this.limit = limit;
    }

    public Decision decision() { return decision; }
    public Long uid() { return uid; }
    public int used() { return used; }
    public int limit() { return limit; }
    public boolean allowed() { return decision == Decision.ALLOW_EXISTING || decision == Decision.ALLOW_NEW; }
}
