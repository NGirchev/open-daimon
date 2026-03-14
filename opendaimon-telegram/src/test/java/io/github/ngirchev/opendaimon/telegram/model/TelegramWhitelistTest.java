package io.github.ngirchev.opendaimon.telegram.model;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TelegramWhitelist entity.
 */
class TelegramWhitelistTest {

    @Test
    void gettersAndSetters() {
        TelegramWhitelist wl = new TelegramWhitelist();
        wl.setId(1L);
        wl.setCreatedAt(OffsetDateTime.now());
        TelegramUser user = new TelegramUser();
        user.setId(2L);
        wl.setUser(user);

        assertEquals(1L, wl.getId());
        assertNotNull(wl.getCreatedAt());
        assertNotNull(wl.getUser());
        assertEquals(2L, wl.getUser().getId());
    }
}
