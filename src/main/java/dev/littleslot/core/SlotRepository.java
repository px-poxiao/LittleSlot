package dev.littleslot.core;

import java.util.Set;
import java.util.List;
import java.util.UUID;

/** Every transaction locks its scope before reading or changing policy state. */
public interface SlotRepository {
    interface Work<T> { T run(Tx tx) throws Exception; }

    interface Tx {
        Long owner(UUID profile) throws Exception;
        long ownerVerifiedAt(UUID profile) throws Exception;
        long verifiedUntil(long uid) throws Exception;
        long verifiedAt(long uid) throws Exception;
        Integer limitOverride(long uid) throws Exception;
        int allocatedCount(long uid) throws Exception;
        boolean allocated(long uid, UUID profile) throws Exception;
        boolean blocked(UUID profile) throws Exception;
        void allocate(long uid, UUID profile, long now) throws Exception;
        void removeAllocation(UUID profile) throws Exception;
        void putLimit(long uid, Integer limit) throws Exception;
        void setBlocked(UUID profile, boolean blocked, long now) throws Exception;
        long lastSelfRelease(long uid) throws Exception;
        void setLastSelfRelease(long uid, long now) throws Exception;
        Set<UUID> ownedProfiles(long uid) throws Exception;
        void replaceOwnership(long uid, Set<UUID> profiles, long verifiedAt, long until) throws Exception;
        void audit(String action, Long uid, UUID profile, long now) throws Exception;
    }

    <T> T transact(String scope, Work<T> work) throws SlotException;
    long latestAuditId(String scope) throws SlotException;
    List<AuditEvent> auditAfter(String scope, long afterId, int maxRows) throws SlotException;
}
