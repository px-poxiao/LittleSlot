package dev.littleslot.bukkit;

import java.util.Locale;

/** Formats cached session data without touching Bukkit, OAuth, or the database. */
final class PlaceholderValues {
    private PlaceholderValues() { }

    static String value(PlayerSession session, String scope, String parameter) {
        if (parameter == null) return null;
        switch (parameter.toLowerCase(Locale.ROOT)) {
            case "state": return session == null ? "none" : session.state.name().toLowerCase(Locale.ROOT);
            case "scope": return scope;
            case "uid": return admitted(session) && session.uid != null ? session.uid.toString() : "";
            case "used": return admitted(session) && session.used != null ? session.used.toString() : "";
            case "limit": return admitted(session) && session.limit != null ? session.limit.toString() : "";
            case "remaining":
                if (!admitted(session) || session.limit == null || session.used == null) return "";
                // -1 is the same unlimited sentinel used by the core slot policy.
                return session.limit == -1 ? "-1" : Integer.toString(Math.max(0, session.limit - session.used));
            default: return null;
        }
    }

    private static boolean admitted(PlayerSession session) {
        return session != null && session.state == PlayerSession.State.ADMITTED;
    }
}
