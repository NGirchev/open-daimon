package io.github.ngirchev.aibot.telegram.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import io.github.ngirchev.aibot.telegram.TelegramBot;
import io.github.ngirchev.aibot.telegram.model.TelegramUser;
import io.github.ngirchev.aibot.telegram.model.TelegramWhitelist;
import io.github.ngirchev.aibot.telegram.repository.TelegramWhitelistRepository;
import io.github.ngirchev.aibot.telegram.repository.TelegramUserRepository;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TelegramWhitelistServiceTest {

    @Mock
    private TelegramWhitelistRepository whitelistRepository;

    @Mock
    private TelegramBot telegramBot;

    @Mock
    private TelegramUserRepository telegramUserRepository;

    private TelegramWhitelistService whitelistService;

    @BeforeEach
    void setUp() {
        Set<String> channels = new HashSet<>();
        channels.add("1001111111111");
        whitelistService = new TelegramWhitelistService(whitelistRepository, telegramBot, telegramUserRepository, channels);

        TelegramUser defaultUser = new TelegramUser();
        defaultUser.setId(1L);
        defaultUser.setTelegramId(1000L);
        lenient().when(telegramUserRepository.findById(anyLong())).thenReturn(Optional.of(defaultUser));
        lenient().when(whitelistRepository.findAllTelegramUsers()).thenReturn(List.of(defaultUser));
    }

    @Test
    void whenUserInWhitelist_thenIsUserAllowedReturnsTrue() {
        // Arrange
        Long userId = 123L;
        when(whitelistRepository.findAllUserIds()).thenReturn(List.of(userId));

        // Act
        whitelistService.onApplicationReady();
        boolean result = whitelistService.isUserAllowed(userId);

        // Assert
        assertTrue(result);
    }

    @Test
    void whenUserNotInWhitelist_thenIsUserAllowedReturnsFalse() {
        // Arrange
        Long userId = 123L;
        when(whitelistRepository.findAllUserIds()).thenReturn(List.of());

        // Act
        whitelistService.onApplicationReady();
        boolean result = whitelistService.isUserAllowed(userId);

        // Assert
        assertFalse(result);
    }

    @Test
    void whenAddToWhitelist_andUserExists_thenAddToWhitelist() {
        // Arrange
        Long userId = 123L;
        TelegramUser user = new TelegramUser();
        user.setId(userId);
        user.setTelegramId(999L);
        when(telegramUserRepository.findById(userId)).thenReturn(Optional.of(user));
        when(whitelistRepository.save(any(TelegramWhitelist.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        whitelistService.addToWhitelist(userId);

        // Assert
        verify(telegramUserRepository).findById(userId);
        verify(whitelistRepository).save(any(TelegramWhitelist.class));
    }

    @Test
    void whenAddToWhitelist_andUserDoesNotExist_thenThrowException() {
        // Arrange
        Long userId = 123L;
        when(telegramUserRepository.findById(userId)).thenReturn(Optional.empty());

        // Act
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> whitelistService.addToWhitelist(userId));

        // Assert
        assertEquals("User not found", exception.getMessage());
        verify(telegramUserRepository).findById(userId);
        verifyNoInteractions(whitelistRepository);
    }

    @Test
    void whenCheckUserInChannel_andUserIsMember_thenReturnTrue() throws TelegramApiException {
        // Arrange
        Long userId = 123L;
        ChatMember chatMember = mock(ChatMember.class);
        when(chatMember.getStatus()).thenReturn("member");
        when(telegramBot.execute(any(GetChatMember.class))).thenReturn(chatMember);

        // Act
        boolean result = whitelistService.checkUserInChannel(userId);

        // Assert
        assertTrue(result);
        verify(telegramBot).execute(any(GetChatMember.class));
    }

    @Test
    void whenCheckUserInChannel_andUserIsNotMember_thenReturnFalse() throws TelegramApiException {
        // Arrange
        Long userId = 123L;
        ChatMember chatMember = mock(ChatMember.class);
        when(chatMember.getStatus()).thenReturn("left");
        when(telegramBot.execute(any(GetChatMember.class))).thenReturn(chatMember);

        // Act
        boolean result = whitelistService.checkUserInChannel(userId);

        // Assert
        assertFalse(result);
        verify(telegramBot).execute(any(GetChatMember.class));
    }

    @Test
    void whenCheckUserInChannel_andApiException_thenReturnFalse() throws TelegramApiException {
        // Arrange
        Long userId = 123L;
        when(telegramBot.execute(any(GetChatMember.class))).thenThrow(new TelegramApiException("Error"));

        // Act
        boolean result = whitelistService.checkUserInChannel(userId);

        // Assert
        assertFalse(result);
        verify(telegramBot).execute(any(GetChatMember.class));
    }

    @Test
    void whenCheckUserInChannel_andChannelIdIsNull_thenReturnFalse() throws TelegramApiException {
        // Arrange
        Long userId = 123L;
        TelegramWhitelistService serviceWithoutChannel = new TelegramWhitelistService(
                whitelistRepository, telegramBot, telegramUserRepository, null);

        // Act
        boolean result = serviceWithoutChannel.checkUserInChannel(userId);

        // Assert
        assertFalse(result);
        verify(telegramBot, never()).execute(any(GetChatMember.class));
    }

    @Test
    void whenCheckUserInChannel_andChannelIdIsEmpty_thenReturnFalse() throws TelegramApiException {
        // Arrange
        Long userId = 123L;
        TelegramWhitelistService serviceWithoutChannel = new TelegramWhitelistService(
                whitelistRepository, telegramBot, telegramUserRepository, new HashSet<>());

        // Act
        boolean result = serviceWithoutChannel.checkUserInChannel(userId);

        // Assert
        assertFalse(result);
        verify(telegramBot, never()).execute(any(GetChatMember.class));
    }

    @Test
    void whenCheckUserInChannel_andUserInSecondChannel_thenReturnTrue() throws TelegramApiException {
        // Arrange
        Long userId = 123L;
        Set<String> channels = new HashSet<>();
        channels.add("-1001111111111");
        channels.add("-1002222222222");
        TelegramWhitelistService serviceWithMultipleChannels = new TelegramWhitelistService(
                whitelistRepository, telegramBot, telegramUserRepository, channels);
        
        // First channel - user is not a member
        ChatMember leftMember = mock(ChatMember.class);
        when(leftMember.getStatus()).thenReturn("left");
        // Second channel - user is a member
        ChatMember member = mock(ChatMember.class);
        when(member.getStatus()).thenReturn("member");
        
        when(telegramBot.execute(any(GetChatMember.class)))
                .thenReturn(leftMember)  // First call - not a member
                .thenReturn(member);     // Second call - member

        // Act
        boolean result = serviceWithMultipleChannels.checkUserInChannel(userId);

        // Assert
        assertTrue(result);
        verify(telegramBot, times(2)).execute(any(GetChatMember.class));
    }

    @Test
    void whenCheckUserInChannel_andUserNotInAnyChannel_thenReturnFalse() throws TelegramApiException {
        // Arrange
        Long userId = 123L;
        Set<String> channels = new HashSet<>();
        channels.add("-1001111111111");
        channels.add("-1002222222222");
        TelegramWhitelistService serviceWithMultipleChannels = new TelegramWhitelistService(
                whitelistRepository, telegramBot, telegramUserRepository, channels);
        
        ChatMember leftMember = mock(ChatMember.class);
        when(leftMember.getStatus()).thenReturn("left");
        when(telegramBot.execute(any(GetChatMember.class))).thenReturn(leftMember);

        // Act
        boolean result = serviceWithMultipleChannels.checkUserInChannel(userId);

        // Assert
        assertFalse(result);
        verify(telegramBot, times(2)).execute(any(GetChatMember.class));
    }

    @Test
    void whenCheckUserInChannel_andExceptionInFirstChannel_thenCheckSecondChannel() throws TelegramApiException {
        // Arrange
        Long userId = 123L;
        Set<String> channels = new HashSet<>();
        channels.add("-1001111111111");
        channels.add("-1002222222222");
        TelegramWhitelistService serviceWithMultipleChannels = new TelegramWhitelistService(
                whitelistRepository, telegramBot, telegramUserRepository, channels);
        
        // First channel - exception
        // Second channel - user is a member
        ChatMember member = mock(ChatMember.class);
        when(member.getStatus()).thenReturn("member");
        
        when(telegramBot.execute(any(GetChatMember.class)))
                .thenThrow(new TelegramApiException("Error"))  // First call - exception
                .thenReturn(member);                            // Second call - member

        // Act
        boolean result = serviceWithMultipleChannels.checkUserInChannel(userId);

        // Assert
        assertTrue(result);
        verify(telegramBot, times(2)).execute(any(GetChatMember.class));
    }
} 
