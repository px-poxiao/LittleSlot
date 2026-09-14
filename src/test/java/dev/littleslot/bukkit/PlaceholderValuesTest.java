package dev.littleslot.bukkit;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PlaceholderValuesTest {
    @Test void displaysOnlyCurrentAdmittedSlotDataAndNeverInventsPremiumSlots() {
        PlayerSession session = new PlayerSession(UUID.randomUUID());
        assertEquals("checking", PlaceholderValues.value(session, "group", "state"));
        assertEquals("", PlaceholderValues.value(session, "group", "used"));
        assertEquals("group", PlaceholderValues.value(null, "group", "scope"));
        assertEquals("none", PlaceholderValues.value(null, "group", "state"));
        session.uid = 42L;
        session.used = 2;
        session.limit = 3;
        session.state = PlayerSession.State.ADMITTED;
        assertEquals("42", PlaceholderValues.value(session, "group", "uid"));
        assertEquals("2", PlaceholderValues.value(session, "group", "used"));
        assertEquals("3", PlaceholderValues.value(session, "group", "limit"));
        assertEquals("1", PlaceholderValues.value(session, "group", "remaining"));
        session.limit = -1;
        assertEquals("-1", PlaceholderValues.value(session, "group", "remaining"));
        session.state = PlayerSession.State.BYPASS;
        assertEquals("bypass", PlaceholderValues.value(session, "group", "state"));
        assertEquals("", PlaceholderValues.value(session, "group", "uid"));
        assertNull(PlaceholderValues.value(session, "group", "unknown"));
    }
}
