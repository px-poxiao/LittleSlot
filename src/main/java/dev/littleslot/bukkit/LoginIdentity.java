package dev.littleslot.bukkit;

import java.util.UUID;

/** Evidence about this connection, including the upstream profile before any proxy remapping. */
final class LoginIdentity {
    final LoginSource source;
    final UUID originalUuid;

    LoginIdentity(LoginSource source, UUID originalUuid) {
        this.source = source;
        this.originalUuid = originalUuid;
    }
}
