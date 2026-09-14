package dev.littleslot.core;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Only data established by an OAuth provider belongs here. */
public final class VerifiedAccount {
    private final long uid;
    private final Set<UUID> profiles;
    private final long verifiedAtMillis;

    public VerifiedAccount(long uid, Set<UUID> profiles, long verifiedAtMillis) {
        if (uid <= 0 || profiles == null || profiles.contains(null) || verifiedAtMillis <= 0) {
            throw new IllegalArgumentException("Invalid verified account");
        }
        this.uid = uid;
        this.profiles = Collections.unmodifiableSet(new HashSet<UUID>(profiles));
        this.verifiedAtMillis = verifiedAtMillis;
    }

    public long uid() { return uid; }
    public Set<UUID> profiles() { return profiles; }
    public long verifiedAtMillis() { return verifiedAtMillis; }
}
