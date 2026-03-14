package io.github.ngirchev.aibot.telegram.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import io.github.ngirchev.aibot.common.config.CoreJpaConfig;
import io.github.ngirchev.aibot.common.config.CoreFlywayConfig;
import io.github.ngirchev.aibot.telegram.config.TelegramJpaConfig;
import io.github.ngirchev.aibot.telegram.config.TelegramFlywayConfig;
import io.github.ngirchev.aibot.telegram.model.TelegramUser;
import io.github.ngirchev.aibot.telegram.model.TelegramUserSession;
import io.github.ngirchev.aibot.test.TestDatabaseConfiguration;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

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
class TelegramUserSessionRepositoryIT {

    @Autowired
    private TelegramUserSessionRepository sessionRepository;
    @Autowired
    private TelegramUserRepository userRepository;

    @Test
    void whenFindByTelegramUserAndSessionId_thenReturnSession() {
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
        userRepository.save(user);

        TelegramUserSession session = new TelegramUserSession();
        session.setTelegramUser(user);
        session.setSessionId("test-session");
        session.setCreatedAt(OffsetDateTime.now());
        session.setUpdatedAt(OffsetDateTime.now());
        session.setIsActive(true);
        sessionRepository.save(session);

        // Act
        Optional<TelegramUserSession> found = sessionRepository.findByTelegramUserAndSessionId(user, "test-session");

        // Assert
        assertTrue(found.isPresent());
        assertEquals("test-session", found.get().getSessionId());
        assertEquals(user.getId(), found.get().getTelegramUser().getId());
    }

    @Test
    void whenFindByTelegramUserAndIsActiveTrue_thenReturnActiveSession() {
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
        userRepository.save(user);

        TelegramUserSession session = new TelegramUserSession();
        session.setTelegramUser(user);
        session.setSessionId("test-session");
        session.setCreatedAt(OffsetDateTime.now());
        session.setUpdatedAt(OffsetDateTime.now());
        session.setIsActive(true);
        sessionRepository.save(session);

        // Act
        Optional<TelegramUserSession> found = sessionRepository.findByTelegramUserAndIsActiveTrue(user);

        // Assert
        assertTrue(found.isPresent());
        assertTrue(found.get().getIsActive());
        assertEquals(user.getId(), found.get().getTelegramUser().getId());
    }

    @Test
    void whenFindActiveSessionsBefore_thenReturnSessions() {
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
        userRepository.save(user);

        TelegramUserSession session = new TelegramUserSession();
        session.setTelegramUser(user);
        session.setSessionId("test-session");
        session.setCreatedAt(OffsetDateTime.now().minusHours(1));
        session.setUpdatedAt(OffsetDateTime.now().minusHours(1));
        session.setIsActive(true);
        sessionRepository.save(session);

        // Act
        Page<TelegramUserSession> found = sessionRepository.findActiveSessionsBefore(
            OffsetDateTime.now(), PageRequest.of(0, 10));

        // Assert
        assertEquals(1, found.getContent().size());
        assertTrue(found.getContent().get(0).getIsActive());
    }

    @Test
    void whenFindActiveSessionsForUser_thenReturnUserSessions() {
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
        userRepository.save(user);

        TelegramUserSession session = new TelegramUserSession();
        session.setTelegramUser(user);
        session.setSessionId("test-session");
        session.setCreatedAt(OffsetDateTime.now());
        session.setUpdatedAt(OffsetDateTime.now());
        session.setIsActive(true);
        sessionRepository.save(session);

        // Act
        List<TelegramUserSession> found = sessionRepository.findActiveSessionsForUser(123L);

        // Assert
        assertEquals(1, found.size());
        assertTrue(found.get(0).getIsActive());
        assertEquals(user.getId(), found.get(0).getTelegramUser().getId());
    }

    @Test
    void whenSaveSession_thenSessionIsSaved() {
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
        userRepository.save(user);

        TelegramUserSession session = new TelegramUserSession();
        session.setTelegramUser(user);
        session.setSessionId("test-session");
        session.setCreatedAt(OffsetDateTime.now());
        session.setUpdatedAt(OffsetDateTime.now());
        session.setIsActive(true);

        // Act
        TelegramUserSession saved = sessionRepository.save(session);

        // Assert
        assertNotNull(saved.getId());
        assertEquals("test-session", saved.getSessionId());
        assertEquals(user.getId(), saved.getTelegramUser().getId());
        assertTrue(saved.getIsActive());
    }
} 
