package dev.littleslot.core;

import java.time.Clock;
import java.util.Set;
import java.util.UUID;

/** Scope-local ownership and slot policy; the caller supplies the UUID observed at join. */
public final class SlotService {
    private final SlotRepository repository;
    private final SlotSettings settings;
    private final Clock clock;

    public SlotService(SlotRepository repository, SlotSettings settings, Clock clock) {
        if (repository == null || settings == null || clock == null) throw new IllegalArgumentException("null dependency");
        this.repository = repository;
        this.settings = settings;
        this.clock = clock;
    }

    public Admission join(String scope, UUID profile) throws SlotException {
        require(scope, profile);
        return repository.transact(scope, tx -> admit(tx, profile, clock.millis()));
    }

    /** Apply a full OAuth snapshot only if the requesting profile belongs to it. */
    public Admission verifyAndJoin(String scope, UUID profile, VerifiedAccount verified) throws SlotException {
        require(scope, profile);
        if (verified == null) throw new IllegalArgumentException("verified");
        // A successful OAuth fetch is billable upstream, but an unrelated account must not mutate local ownership.
        if (!verified.profiles().contains(profile)) {
            return new Admission(Decision.ACCOUNT_MISMATCH, verified.uid(), 0, settings.defaultLimit());
        }
        return repository.transact(scope, tx -> {
            long now = clock.millis();
            if (verified.verifiedAtMillis() > now + 60_000L) {
                throw new IllegalArgumentException("Verification time is in the future");
            }
            long previous = tx.verifiedAt(verified.uid());
            // An older completed request cannot overwrite a snapshot already committed later.
            if (previous < verified.verifiedAtMillis()) {
                Set<UUID> accepted = new java.util.HashSet<UUID>();
                for (UUID candidate : verified.profiles()) {
                    Long oldOwner = tx.owner(candidate);
                    if (oldOwner == null || oldOwner.longValue() == verified.uid()
                            || tx.ownerVerifiedAt(candidate) < verified.verifiedAtMillis()) {
                        accepted.add(candidate);
                    }
                }
                if (!accepted.contains(profile)) {
                    return new Admission(Decision.ACCOUNT_MISMATCH, verified.uid(), 0, settings.defaultLimit());
                }
                Set<UUID> removed = tx.ownedProfiles(verified.uid());
                removed.removeAll(accepted);
                // Revocation removes eligibility and the occupied slot together; online sessions are kicked via audit.
                for (UUID old : removed) {
                    tx.removeAllocation(old);
                    tx.audit("ownership_revoked", verified.uid(), old, now);
                }
                for (UUID candidate : accepted) {
                    Long oldOwner = tx.owner(candidate);
                    if (oldOwner != null && oldOwner.longValue() != verified.uid()) {
                        tx.removeAllocation(candidate);
                        tx.audit("ownership_transferred", oldOwner, candidate, now);
                    }
                }
                tx.replaceOwnership(verified.uid(), accepted, verified.verifiedAtMillis(),
                        verified.verifiedAtMillis() + settings.snapshotTtl().toMillis());
                tx.audit("snapshot", verified.uid(), profile, now);
            }
            Long currentOwner = tx.owner(profile);
            if (currentOwner == null || currentOwner.longValue() != verified.uid()) {
                return new Admission(Decision.ACCOUNT_MISMATCH, verified.uid(), 0, settings.defaultLimit());
            }
            return admit(tx, profile, now);
        });
    }

    public Decision selfRelease(String scope, long uid, UUID profile) throws SlotException {
        require(scope, profile);
        requireUid(uid);
        return repository.transact(scope, tx -> {
            long now = clock.millis();
            // The caller's live session supplies uid; still recheck target ownership and snapshot age in the transaction.
            Long owner = tx.owner(profile);
            if (owner == null || owner.longValue() != uid || tx.verifiedUntil(uid) <= now) return Decision.ACCOUNT_MISMATCH;
            if (!tx.allocated(uid, profile)) return Decision.NOT_ALLOCATED;
            long last = tx.lastSelfRelease(uid);
            if (last > 0 && now - last < settings.releaseCooldown().toMillis()) return Decision.COOLDOWN;
            tx.removeAllocation(profile);
            tx.setLastSelfRelease(uid, now);
            tx.audit("self_release", uid, profile, now);
            return Decision.ALLOW_EXISTING;
        });
    }

    public boolean adminRelease(String scope, UUID profile) throws SlotException {
        require(scope, profile);
        return repository.transact(scope, tx -> {
            Long uid = tx.owner(profile);
            if (uid == null || !tx.allocated(uid, profile)) return false;
            tx.removeAllocation(profile);
            tx.audit("admin_release", uid, profile, clock.millis());
            return true;
        });
    }

    public void setLimit(String scope, long uid, Integer limit) throws SlotException {
        requireScope(scope);
        requireUid(uid);
        if (limit != null && limit < -1) throw new IllegalArgumentException("limit");
        repository.transact(scope, tx -> {
            tx.putLimit(uid, limit);
            tx.audit("limit", uid, null, clock.millis());
            return null;
        });
    }

    public void setBlocked(String scope, UUID profile, boolean blocked) throws SlotException {
        require(scope, profile);
        repository.transact(scope, tx -> {
            long now = clock.millis();
            tx.setBlocked(profile, blocked, now);
            tx.audit(blocked ? "block" : "unblock", tx.owner(profile), profile, now);
            return null;
        });
    }

    public String accountSummary(String scope, long uid) throws SlotException {
        requireScope(scope);
        requireUid(uid);
        return repository.transact(scope, tx -> {
            Integer override = tx.limitOverride(uid);
            int limit = override == null ? settings.defaultLimit() : override;
            Set<UUID> owned = tx.ownedProfiles(uid);
            java.util.List<String> assigned = new java.util.ArrayList<String>();
            for (UUID profile : owned) if (tx.allocated(uid, profile)) assigned.add(profile.toString());
            java.util.Collections.sort(assigned);
            return "UID=" + uid + " 已占=" + assigned.size() + "/" + (limit == -1 ? "无限" : limit)
                    + " 归属验证到期=" + tx.verifiedUntil(uid) + " 自助释放时间=" + tx.lastSelfRelease(uid)
                    + " 名额UUID=" + assigned;
        });
    }

    public String profileSummary(String scope, UUID profile) throws SlotException {
        require(scope, profile);
        return repository.transact(scope, tx -> {
            Long owner = tx.owner(profile);
            return "UUID=" + profile + " UID=" + owner + " 已占名额="
                    + (owner != null && tx.allocated(owner, profile)) + " 已禁止=" + tx.blocked(profile);
        });
    }

    private Admission admit(SlotRepository.Tx tx, UUID profile, long now) throws Exception {
        if (tx.blocked(profile)) return new Admission(Decision.BLOCKED, tx.owner(profile), 0, settings.defaultLimit());
        Long uid = tx.owner(profile);
        if (uid == null) return new Admission(Decision.BIND_REQUIRED, null, 0, settings.defaultLimit());
        int count = tx.allocatedCount(uid);
        Integer override = tx.limitOverride(uid);
        int limit = override == null ? settings.defaultLimit() : override;
        if (tx.verifiedUntil(uid) <= now) return new Admission(Decision.SNAPSHOT_EXPIRED, uid, count, limit);
        // Existing allocations survive a later limit reduction. Only a new profile needs spare capacity.
        if (tx.allocated(uid, profile)) return new Admission(Decision.ALLOW_EXISTING, uid, count, limit);
        if (limit != -1 && count >= limit) return new Admission(Decision.FULL, uid, count, limit);
        tx.allocate(uid, profile, now);
        tx.audit("allocate", uid, profile, now);
        return new Admission(Decision.ALLOW_NEW, uid, count + 1, limit);
    }

    private static void require(String scope, UUID profile) {
        requireScope(scope);
        if (profile == null) throw new IllegalArgumentException("profile");
    }

    private static void requireScope(String scope) {
        if (scope == null || !scope.matches("[A-Za-z0-9_.:-]{1,128}")) throw new IllegalArgumentException("scope");
    }

    private static void requireUid(long uid) {
        if (uid <= 0) throw new IllegalArgumentException("uid");
    }
}
