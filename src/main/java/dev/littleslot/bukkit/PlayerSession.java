package dev.littleslot.bukkit;

import java.util.UUID;

final class PlayerSession {
    enum State { CHECKING, RESOLVING, BINDING, ADMITTED, TEMPORARY, BYPASS, DENIED }

    final UUID connection = UUID.randomUUID();
    final UUID gameUuid;
    volatile UUID originalUuid;
    final long joinedAt = System.currentTimeMillis();
    volatile long restrictedSince = joinedAt;
    volatile State state = State.CHECKING;
    volatile Long uid;
    volatile long admittedAt;
    volatile String bindUrl;

    PlayerSession(UUID gameUuid) { this.gameUuid = gameUuid; }

    boolean restricted() { return state == State.CHECKING || state == State.RESOLVING || state == State.BINDING || state == State.DENIED; }
}
