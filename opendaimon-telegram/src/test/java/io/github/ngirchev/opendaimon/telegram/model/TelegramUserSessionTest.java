package io.github.ngirchev.opendaimon.telegram.model;

import io.github.ngirchev.opendaimon.telegram.command.TelegramCommand;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TelegramUserSession entity.
 */
class TelegramUserSessionTest {

    @Test
    void gettersAndSetters() {
        TelegramUserSession session = new TelegramUserSession();
        session.setId(1L);
        session.setSessionId("sess-1");
        session.setCreatedAt(OffsetDateTime.now());
        session.setUpdatedAt(OffsetDateTime.now());
        session.setExpiredAt(OffsetDateTime.now().plusHours(24));
        session.setIsActive(true);
        session.setBotStatus(TelegramCommand.ROLE);

        assertEquals(1L, session.getId());
        assertEquals("sess-1", session.getSessionId());
        assertNotNull(session.getCreatedAt());
        assertNotNull(session.getUpdatedAt());
        assertNotNull(session.getExpiredAt());
        assertTrue(session.getIsActive());
        assertEquals(TelegramCommand.ROLE, session.getBotStatus());
    }
}
