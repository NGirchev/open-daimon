package io.github.ngirchev.opendaimon.telegram.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import io.github.ngirchev.opendaimon.common.config.CoreJpaConfig;
import io.github.ngirchev.opendaimon.common.config.CoreFlywayConfig;
import io.github.ngirchev.opendaimon.telegram.config.TelegramJpaConfig;
import io.github.ngirchev.opendaimon.telegram.config.TelegramFlywayConfig;
import io.github.ngirchev.opendaimon.telegram.model.TelegramUser;
import io.github.ngirchev.opendaimon.test.TestDatabaseConfiguration;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        TestDatabaseConfiguration.class,
        CoreJpaConfig.class,
        TelegramJpaConfig.class,
        CoreFlywayConfig.class,
        TelegramFlywayConfig.class
})
class TelegramUserRepositoryIT {

    @Autowired
    private TelegramUserRepository telegramUserRepository;

    @Test
    void whenFindByTelegramId_thenReturnUser() {
        // Arrange
        TelegramUser user = new TelegramUser();
        user.setTelegramId(123L);
        user.setUsername("testuser");
        user.setFirstName("Test");
        user.setLastName("User");
        user.setCreatedAt(OffsetDateTime.now());
        user.setUpdatedAt(OffsetDateTime.now());
        user.setLastActivityAt(OffsetDateTime.now());
        user.setIsAdmin(false);
        user.setIsPremium(false);
        user.setIsBlocked(false);
        telegramUserRepository.save(user);

        // Act
        Optional<TelegramUser> found = telegramUserRepository.findByTelegramId(123L);

        // Assert
        assertTrue(found.isPresent());
        assertEquals(123L, found.get().getTelegramId());
        assertEquals("testuser", found.get().getUsername());
        assertEquals("Test", found.get().getFirstName());
        assertEquals("User", found.get().getLastName());
    }

    @Test
    void whenFindByTelegramId_andUserDoesNotExist_thenReturnEmpty() {
        // Act
        Optional<TelegramUser> found = telegramUserRepository.findByTelegramId(999L);

        // Assert
        assertTrue(found.isEmpty());
    }

    @Test
    void whenExistsByTelegramId_andUserExists_thenReturnTrue() {
        // Arrange
        TelegramUser user = new TelegramUser();
        user.setTelegramId(123L);
        user.setUsername("testuser");
        user.setFirstName("Test");
        user.setLastName("User");
        user.setCreatedAt(OffsetDateTime.now());
        user.setUpdatedAt(OffsetDateTime.now());
        user.setLastActivityAt(OffsetDateTime.now());
        user.setIsAdmin(false);
        user.setIsPremium(false);
        user.setIsBlocked(false);
        telegramUserRepository.save(user);

        // Act
        boolean exists = telegramUserRepository.existsByTelegramId(123L);

        // Assert
        assertTrue(exists);
    }

    @Test
    void whenExistsByTelegramId_andUserDoesNotExist_thenReturnFalse() {
        // Act
        boolean exists = telegramUserRepository.existsByTelegramId(999L);

        // Assert
        assertFalse(exists);
    }

    @Test
    void whenSaveUser_thenUserIsSaved() {
        // Arrange
        TelegramUser user = new TelegramUser();
        user.setTelegramId(123L);
        user.setUsername("testuser");
        user.setFirstName("Test");
        user.setLastName("User");
        user.setCreatedAt(OffsetDateTime.now());
        user.setUpdatedAt(OffsetDateTime.now());
        user.setLastActivityAt(OffsetDateTime.now());
        user.setIsAdmin(false);
        user.setIsPremium(false);
        user.setIsBlocked(false);

        // Act
        TelegramUser saved = telegramUserRepository.save(user);

        // Assert
        assertNotNull(saved.getId());
        assertEquals(123L, saved.getTelegramId());
        assertEquals("testuser", saved.getUsername());
        assertEquals("Test", saved.getFirstName());
        assertEquals("User", saved.getLastName());
    }
}