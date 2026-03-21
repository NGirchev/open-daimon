package io.github.ngirchev.opendaimon.bulkhead.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import io.github.ngirchev.opendaimon.bulkhead.model.UserPriority;
import io.github.ngirchev.opendaimon.bulkhead.service.IUserObject;
import io.github.ngirchev.opendaimon.bulkhead.service.IUserService;
import io.github.ngirchev.opendaimon.bulkhead.service.IWhitelistService;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DefaultUserPriorityServiceTest {

    @Mock
    private IUserService telegramUserService;
    @Mock
    private IWhitelistService whitelistService;

    private DefaultUserPriorityService userPriorityService;

    @BeforeEach
    void setUp() {
        userPriorityService = new DefaultUserPriorityService(telegramUserService, whitelistService);
    }

    @Test
    void whenUserIdIsNull_thenReturnBlocked() {
        // Act
        UserPriority result = userPriorityService.getUserPriority(null);

        // Assert
        assertEquals(UserPriority.BLOCKED, result);
    }

    @Test
    void whenUserNotInWhitelist_andNotInChannel_thenReturnRegular() {
        // Arrange: unknown user — not in whitelist, not in any channel → REGULAR (not BLOCKED)
        Long userId = 7L;
        IUserObject mockUser = mock(IUserObject.class);
        when(mockUser.getIsAdmin()).thenReturn(false);
        when(mockUser.getIsBlocked()).thenReturn(false);
        when(mockUser.getIsPremium()).thenReturn(false);
        when(whitelistService.isUserAllowed(userId)).thenReturn(false);
        when(whitelistService.checkUserInChannel(userId)).thenReturn(false);
        doReturn(Optional.of(mockUser)).when(telegramUserService).findById(userId);

        // Act
        UserPriority result = userPriorityService.getUserPriority(userId);

        // Assert
        assertEquals(UserPriority.REGULAR, result);
        verify(telegramUserService).findById(userId);
        verify(mockUser).getIsAdmin();
        verify(whitelistService).isUserAllowed(userId);
        verify(whitelistService, atLeast(1)).checkUserInChannel(userId);
    }

    @Test
    void whenUserInWhitelist_butBlocked_thenReturnBlocked() {
        // Arrange
        Long userId = 7L;
        IUserObject mockUser = mock(IUserObject.class);
        when(mockUser.getIsAdmin()).thenReturn(false);
        when(mockUser.getIsBlocked()).thenReturn(true);
        when(whitelistService.isUserAllowed(userId)).thenReturn(true);
        doReturn(Optional.of(mockUser)).when(telegramUserService).findById(userId);

        // Act
        UserPriority result = userPriorityService.getUserPriority(userId);

        // Assert
        assertEquals(UserPriority.BLOCKED, result);
        verify(whitelistService).isUserAllowed(userId);
        verify(telegramUserService).findById(userId);
        verify(mockUser).getIsAdmin();
        verify(mockUser).getIsBlocked();
    }

    @Test
    void whenUserInWhitelist_andRegular_thenReturnRegular() {
        // Arrange: in whitelist, not premium, not in configured channel
        Long userId = 7L;
        when(whitelistService.isUserAllowed(userId)).thenReturn(true);
        when(whitelistService.checkUserInChannel(userId)).thenReturn(false);
        when(telegramUserService.findById(userId)).thenReturn(Optional.empty());

        // Act
        UserPriority result = userPriorityService.getUserPriority(userId);

        // Assert
        assertEquals(UserPriority.REGULAR, result);
        verify(whitelistService).isUserAllowed(userId);
        verify(telegramUserService).findById(userId);
    }

    @Test
    void whenUserNotInWhitelist_butInChannel_thenAddToWhitelist_andReturnVip() {
        // Arrange: user gets access via channel, then receives Premium (VIP) as channel member
        Long userId = 7L;
        IUserObject mockUser = mock(IUserObject.class);
        when(mockUser.getIsAdmin()).thenReturn(false);
        when(mockUser.getIsBlocked()).thenReturn(false);
        when(mockUser.getIsPremium()).thenReturn(false);
        when(whitelistService.isUserAllowed(userId)).thenReturn(false);
        when(whitelistService.checkUserInChannel(userId)).thenReturn(true);
        doReturn(Optional.of(mockUser)).when(telegramUserService).findById(userId);

        // Act
        UserPriority result = userPriorityService.getUserPriority(userId);

        // Assert
        assertEquals(UserPriority.VIP, result);
        verify(whitelistService).isUserAllowed(userId);
        verify(whitelistService, atLeast(1)).checkUserInChannel(userId);
        verify(whitelistService).addToWhitelist(userId);
        verify(telegramUserService).findById(userId);
    }

    @Test
    void whenUserInWhitelist_andPremium_thenReturnVip() {
        // Arrange
        Long userId = 7L;
        IUserObject mockUser = mock(IUserObject.class);
        when(mockUser.getIsAdmin()).thenReturn(false);
        when(mockUser.getIsPremium()).thenReturn(true);
        when(whitelistService.isUserAllowed(userId)).thenReturn(true);
        doReturn(Optional.of(mockUser)).when(telegramUserService).findById(userId);

        // Act
        UserPriority result = userPriorityService.getUserPriority(userId);

        // Assert
        assertEquals(UserPriority.VIP, result);
        verify(whitelistService).isUserAllowed(userId);
        verify(telegramUserService).findById(userId);
        verify(mockUser).getIsAdmin();
        verify(mockUser).getIsPremium();
    }

    @Test
    void whenUserInWhitelist_andInChannel_butNotPremium_thenReturnVip() {
        // Arrange: channel/group members get Premium (VIP) even without Telegram Premium
        Long userId = 7L;
        IUserObject mockUser = mock(IUserObject.class);
        when(mockUser.getIsAdmin()).thenReturn(false);
        when(mockUser.getIsBlocked()).thenReturn(false);
        when(mockUser.getIsPremium()).thenReturn(false);
        when(whitelistService.isUserAllowed(userId)).thenReturn(true);
        when(whitelistService.checkUserInChannel(userId)).thenReturn(true);
        doReturn(Optional.of(mockUser)).when(telegramUserService).findById(userId);

        // Act
        UserPriority result = userPriorityService.getUserPriority(userId);

        // Assert
        assertEquals(UserPriority.VIP, result);
        verify(whitelistService).isUserAllowed(userId);
        verify(whitelistService).checkUserInChannel(userId);
        verify(telegramUserService).findById(userId);
    }

    @Test
    void whenUserIsAdmin_thenReturnAdmin_regardlessOfOtherConditions() {
        // Arrange
        Long userId = 7L;
        IUserObject mockUser = mock(IUserObject.class);
        when(mockUser.getIsAdmin()).thenReturn(true);
        doReturn(Optional.of(mockUser)).when(telegramUserService).findById(userId);

        // Act
        UserPriority result = userPriorityService.getUserPriority(userId);

        // Assert
        assertEquals(UserPriority.ADMIN, result);
        verify(telegramUserService).findById(userId);
        verify(mockUser).getIsAdmin();
        // Verify whitelist check is not called for admin
        verify(whitelistService, never()).isUserAllowed(userId);
        verify(mockUser, never()).getIsBlocked();
        verify(mockUser, never()).getIsPremium();
    }

}
