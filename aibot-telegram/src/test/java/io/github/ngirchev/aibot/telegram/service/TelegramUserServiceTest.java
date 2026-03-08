package io.github.ngirchev.aibot.telegram.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.objects.User;
import io.github.ngirchev.aibot.common.service.AssistantRoleService;
import io.github.ngirchev.aibot.telegram.model.TelegramUser;
import io.github.ngirchev.aibot.telegram.repository.TelegramUserRepository;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TelegramUserServiceTest {

    @Mock
    private TelegramUserRepository userRepository;
    @Mock
    private TelegramUserSessionService telegramUserSessionService;
    @Mock
    private AssistantRoleService assistantRoleService;

    @Mock
    private User telegramUserApi;

    private TelegramUserService userService;

    @BeforeEach
    void setUp() {
        userService = new TelegramUserService(userRepository, telegramUserSessionService, assistantRoleService);
    }

    @Test
    void whenGetOrCreateUser_andUserExists_thenUpdateAndReturnExisting() {
        // Arrange: language is not overwritten from API; it is preserved (set via /language)
        when(telegramUserApi.getId()).thenReturn(123L);
        when(telegramUserApi.getUserName()).thenReturn("testuser");
        when(telegramUserApi.getFirstName()).thenReturn("Test");
        when(telegramUserApi.getLastName()).thenReturn("User");
        when(telegramUserApi.getIsPremium()).thenReturn(true);

        TelegramUser existingUser = new TelegramUser();
        existingUser.setTelegramId(123L);
        existingUser.setUsername("oldusername");
        existingUser.setFirstName("OldName");
        existingUser.setLastName("OldLastName");
        existingUser.setLanguageCode("en"); // user choice via /language — not overwritten from API
        existingUser.setIsPremium(false);
        OffsetDateTime oldLastActivity = OffsetDateTime.now().minusHours(1);
        existingUser.setLastActivityAt(oldLastActivity);

        when(userRepository.findByTelegramId(123L)).thenReturn(Optional.of(existingUser));
        when(userRepository.save(any(TelegramUser.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        TelegramUser result = userService.getOrCreateUser(telegramUserApi);

        // Assert: name, premium, etc. are updated from API; language is preserved (changed only via /language)
        assertNotNull(result);
        assertEquals(123L, result.getTelegramId());
        assertEquals("testuser", result.getUsername());
        assertEquals("Test", result.getFirstName());
        assertEquals("User", result.getLastName());
        assertEquals("en", result.getLanguageCode());
        assertTrue(result.getIsPremium());
        assertTrue(result.getLastActivityAt().isAfter(oldLastActivity));

        verify(userRepository).findByTelegramId(123L);
        verify(userRepository).save(any(TelegramUser.class));
    }

    @Test
    void whenGetOrCreateUser_andUserDoesNotExist_thenCreateNew() {
        // Arrange
        when(telegramUserApi.getId()).thenReturn(123L);
        when(telegramUserApi.getUserName()).thenReturn("testuser");
        when(telegramUserApi.getFirstName()).thenReturn("Test");
        when(telegramUserApi.getLastName()).thenReturn("User");
        when(telegramUserApi.getLanguageCode()).thenReturn("ru");
        when(telegramUserApi.getIsPremium()).thenReturn(true);

        when(userRepository.findByTelegramId(123L)).thenReturn(Optional.empty());
        when(userRepository.save(any(TelegramUser.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        TelegramUser result = userService.getOrCreateUser(telegramUserApi);

        // Assert
        assertNotNull(result);
        assertEquals(123L, result.getTelegramId());
        assertEquals("testuser", result.getUsername());
        assertEquals("Test", result.getFirstName());
        assertEquals("User", result.getLastName());
        assertEquals("ru", result.getLanguageCode());
        assertTrue(result.getIsPremium());
        assertNotNull(result.getLastActivityAt());

        verify(userRepository).findByTelegramId(123L);
        verify(userRepository).save(any(TelegramUser.class));
    }

    @Test
    void whenUpdateUserActivity_thenLastActivityIsUpdated() {
        // Arrange
        TelegramUser user = new TelegramUser();
        user.setTelegramId(123L);
        OffsetDateTime oldLastActivity = OffsetDateTime.now().minusHours(1);
        user.setLastActivityAt(oldLastActivity);

        when(userRepository.save(any(TelegramUser.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        TelegramUser result = userService.updateUserActivity(user);

        // Assert
        assertNotNull(result);
        assertTrue(result.getLastActivityAt().isAfter(oldLastActivity));
        verify(userRepository).save(user);
    }

    @Test
    void whenCreateUser_thenAllFieldsAreSetCorrectly() {
        // Arrange
        when(telegramUserApi.getId()).thenReturn(123L);
        when(telegramUserApi.getUserName()).thenReturn("testuser");
        when(telegramUserApi.getFirstName()).thenReturn("Test");
        when(telegramUserApi.getLastName()).thenReturn("User");
        when(telegramUserApi.getLanguageCode()).thenReturn("ru");
        when(telegramUserApi.getIsPremium()).thenReturn(true);

        when(userRepository.findByTelegramId(123L)).thenReturn(Optional.empty());
        when(userRepository.save(any(TelegramUser.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        TelegramUser result = userService.getOrCreateUser(telegramUserApi);

        // Assert
        assertNotNull(result);
        assertEquals(123L, result.getTelegramId());
        assertEquals("testuser", result.getUsername());
        assertEquals("Test", result.getFirstName());
        assertEquals("User", result.getLastName());
        assertEquals("ru", result.getLanguageCode());
        assertTrue(result.getIsPremium());
        assertNotNull(result.getLastActivityAt());

        verify(userRepository).findByTelegramId(123L);
        verify(userRepository).save(any(TelegramUser.class));
    }
}
 