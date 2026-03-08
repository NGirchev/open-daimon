package io.github.ngirchev.aibot.telegram.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import io.github.ngirchev.aibot.common.config.CoreJpaConfig;
import io.github.ngirchev.aibot.common.config.CoreFlywayConfig;
import io.github.ngirchev.aibot.telegram.config.TelegramJpaConfig;
import io.github.ngirchev.aibot.telegram.config.TelegramFlywayConfig;
import io.github.ngirchev.aibot.telegram.model.TelegramUser;
import io.github.ngirchev.aibot.telegram.model.TelegramWhitelist;
import io.github.ngirchev.aibot.test.TestDatabaseConfiguration;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        TestDatabaseConfiguration.class,
        CoreJpaConfig.class,
        TelegramJpaConfig.class,
        CoreFlywayConfig.class,
        TelegramFlywayConfig.class
})
class TelegramWhitelistRepositoryIT {

    @Autowired
    private TelegramWhitelistRepository whitelistRepository;
    @Autowired
    private TelegramUserRepository telegramUserRepository;

    @Test
    void whenFindAllUserIds_thenReturnAllUserIds() {
        // Arrange
        TelegramUser user1 = new TelegramUser();
        user1.setTelegramId(123L);
        user1.setUsername("user1");
        user1.setFirstName("First");
        user1.setLastName("User");
        user1.setCreatedAt(OffsetDateTime.now());
        user1.setUpdatedAt(OffsetDateTime.now());
        user1.setLastActivityAt(OffsetDateTime.now());
        telegramUserRepository.save(user1);

        TelegramUser user2 = new TelegramUser();
        user2.setTelegramId(456L);
        user2.setUsername("user2");
        user2.setFirstName("Second");
        user2.setLastName("User");
        user2.setCreatedAt(OffsetDateTime.now());
        user2.setUpdatedAt(OffsetDateTime.now());
        user2.setLastActivityAt(OffsetDateTime.now());
        telegramUserRepository.save(user2);

        TelegramWhitelist whitelist1 = new TelegramWhitelist();
        whitelist1.setUser(user1);
        whitelistRepository.save(whitelist1);

        TelegramWhitelist whitelist2 = new TelegramWhitelist();
        whitelist2.setUser(user2);
        whitelistRepository.save(whitelist2);

        // Act
        List<Long> userIds = whitelistRepository.findAllUserIds();

        // Assert
        assertEquals(2, userIds.size());
        assertTrue(userIds.contains(user1.getId()));
        assertTrue(userIds.contains(user2.getId()));
    }

    @Test
    void whenExistsByUserId_andUserExists_thenReturnTrue() {
        // Arrange
        TelegramUser user = new TelegramUser();
        user.setTelegramId(123L);
        user.setUsername("testuser");
        user.setFirstName("Test");
        user.setLastName("User");
        user.setCreatedAt(OffsetDateTime.now());
        user.setUpdatedAt(OffsetDateTime.now());
        user.setLastActivityAt(OffsetDateTime.now());
        telegramUserRepository.save(user);

        TelegramWhitelist whitelist = new TelegramWhitelist();
        whitelist.setUser(user);
        whitelistRepository.save(whitelist);

        // Act
        boolean exists = whitelistRepository.existsByUserId(user.getId());

        // Assert
        assertTrue(exists);
    }

    @Test
    void whenExistsByUserId_andUserDoesNotExist_thenReturnFalse() {
        // Act
        boolean exists = whitelistRepository.existsByUserId(999L);

        // Assert
        assertFalse(exists);
    }
} 
