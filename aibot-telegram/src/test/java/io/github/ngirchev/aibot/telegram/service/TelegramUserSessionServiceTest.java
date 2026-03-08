package io.github.ngirchev.aibot.telegram.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import io.github.ngirchev.aibot.telegram.model.TelegramUser;
import io.github.ngirchev.aibot.telegram.model.TelegramUserSession;
import io.github.ngirchev.aibot.telegram.repository.TelegramUserRepository;
import io.github.ngirchev.aibot.telegram.repository.TelegramUserSessionRepository;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TelegramUserSessionServiceTest {

    @Mock
    private TelegramUserSessionRepository sessionRepository;
    @Mock
    private TelegramUserRepository userRepository;

    private TelegramUserSessionService sessionService;

    @BeforeEach
    void setUp() {
        sessionService = new TelegramUserSessionService(sessionRepository, userRepository);
    }

    @Test
    void whenGetOrCreateSession_andActiveSessionExists_thenUpdateAndReturnExisting() {
        // Arrange
        TelegramUser user = new TelegramUser();
        user.setId(10L);
        user.setTelegramId(123L);

        TelegramUserSession existingSession = new TelegramUserSession();
        existingSession.setId(1L);
        existingSession.setTelegramUser(user);
        existingSession.setIsActive(true);
        existingSession.setSessionId("existing-session");
        OffsetDateTime oldUpdateTime = OffsetDateTime.now().minusHours(1);
        existingSession.setUpdatedAt(oldUpdateTime);

        when(sessionRepository.findByTelegramUserAndIsActiveTrue(user))
                .thenReturn(Optional.of(existingSession));
        when(sessionRepository.save(any(TelegramUserSession.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        TelegramUserSession result = sessionService.getOrCreateSession(user);

        // Assert
        assertNotNull(result);
        assertEquals(existingSession.getId(), result.getId());
        assertEquals(existingSession.getSessionId(), result.getSessionId());
        assertTrue(result.getIsActive());
        assertTrue(result.getUpdatedAt().isAfter(oldUpdateTime));
        
        verify(sessionRepository).findByTelegramUserAndIsActiveTrue(user);
        verify(sessionRepository).save(any(TelegramUserSession.class));
    }

    @Test
    void whenGetOrCreateSession_andNoActiveSession_thenCreateNew() {
        // Arrange
        TelegramUser user = new TelegramUser();
        user.setId(10L);
        user.setTelegramId(123L);

        when(sessionRepository.findByTelegramUserAndIsActiveTrue(user))
                .thenReturn(Optional.empty());
        when(sessionRepository.save(any(TelegramUserSession.class)))
                .thenAnswer(invocation -> {
                    TelegramUserSession savedSession = invocation.getArgument(0);
                    savedSession.setId(2L);
                    return savedSession;
                });

        // Act
        TelegramUserSession result = sessionService.getOrCreateSession(user);

        // Assert
        assertNotNull(result);
        assertNotNull(result.getSessionId());
        assertEquals(user, result.getTelegramUser());
        assertTrue(result.getIsActive());
        
        verify(sessionRepository).findByTelegramUserAndIsActiveTrue(user);
        verify(sessionRepository).save(any(TelegramUserSession.class));
    }

    @Test
    void whenCreateSession_thenCreateNewActiveSession() {
        // Arrange
        TelegramUser user = new TelegramUser();
        user.setId(10L);
        user.setTelegramId(123L);

        when(sessionRepository.save(any(TelegramUserSession.class)))
                .thenAnswer(invocation -> {
                    TelegramUserSession savedSession = invocation.getArgument(0);
                    savedSession.setId(3L);
                    return savedSession;
                });

        // Act
        TelegramUserSession result = sessionService.createSession(user);

        // Assert
        assertNotNull(result);
        assertNotNull(result.getSessionId());
        assertEquals(user, result.getTelegramUser());
        assertTrue(result.getIsActive());
        
        verify(sessionRepository).save(any(TelegramUserSession.class));
    }

    @Test
    void whenCloseSession_thenDeactivateSession() {
        // Arrange
        TelegramUserSession session = new TelegramUserSession();
        session.setId(1L);
        session.setIsActive(true);

        when(sessionRepository.save(any(TelegramUserSession.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        sessionService.closeSession(session);

        // Assert
        assertFalse(session.getIsActive());
        verify(sessionRepository).save(session);
    }

    @Test
    void whenUserHasNoId_thenSessionUsesExistingUserByTelegramId() {
        TelegramUser transientUser = new TelegramUser();
        transientUser.setTelegramId(123L);

        TelegramUser persistedUser = new TelegramUser();
        persistedUser.setId(10L);
        persistedUser.setTelegramId(123L);

        when(userRepository.findByTelegramId(123L)).thenReturn(Optional.of(persistedUser));
        when(sessionRepository.findByTelegramUserAndIsActiveTrue(persistedUser)).thenReturn(Optional.empty());
        when(sessionRepository.save(any(TelegramUserSession.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TelegramUserSession result = sessionService.getOrCreateSession(transientUser);

        assertNotNull(result);
        assertEquals(persistedUser, result.getTelegramUser());
        verify(userRepository).findByTelegramId(123L);
    }
}
