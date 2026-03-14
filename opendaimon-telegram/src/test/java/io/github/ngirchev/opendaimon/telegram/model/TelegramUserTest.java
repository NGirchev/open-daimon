package io.github.ngirchev.opendaimon.telegram.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TelegramUser entity.
 */
class TelegramUserTest {

    @Test
    void gettersAndSetters() {
        TelegramUser user = new TelegramUser();
        user.setId(1L);
        user.setTelegramId(200L);
        user.setUsername("test");
        user.setLanguageCode("en");

        assertEquals(1L, user.getId());
        assertEquals(200L, user.getTelegramId());
        assertEquals("test", user.getUsername());
        assertEquals("en", user.getLanguageCode());
    }

    @Test
    void getId_returnsSuperId() {
        TelegramUser user = new TelegramUser();
        user.setId(42L);
        assertEquals(42L, user.getId());
    }
}
