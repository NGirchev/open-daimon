package io.github.ngirchev.opendaimon.telegram.service;

import io.github.ngirchev.opendaimon.bulkhead.model.UserPriority;
import io.github.ngirchev.opendaimon.bulkhead.service.IWhitelistService;
import io.github.ngirchev.opendaimon.telegram.config.TelegramProperties;
import io.github.ngirchev.opendaimon.telegram.model.TelegramUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TelegramUserPriorityServiceTest {

    @Mock
    private TelegramUserService telegramUserService;
    @Mock
    private IWhitelistService whitelistService;
    @Mock
    private TelegramProperties telegramProperties;

    private TelegramUserPriorityService priorityService;

    private TelegramProperties.AccessConfig accessConfig;
    private TelegramProperties.AccessConfig.LevelConfig adminLevel;
    private TelegramProperties.AccessConfig.LevelConfig vipLevel;
    private TelegramProperties.AccessConfig.LevelConfig regularLevel;

    @BeforeEach
    void setUp() {
        accessConfig = new TelegramProperties.AccessConfig();
        adminLevel = accessConfig.getAdmin();
        vipLevel = accessConfig.getVip();
        regularLevel = accessConfig.getRegular();
    }

    @Test
    void whenUserIdNull_thenBlocked() {
        priorityService = new TelegramUserPriorityService(telegramUserService, whitelistService, telegramProperties);
        assertEquals(UserPriority.BLOCKED, priorityService.getUserPriority(null));
    }

    @Test
    void whenUserInAdminIds_thenAdmin() {
        Long userId = 1L;
        adminLevel.getIds().add(userId);
        when(telegramProperties.getAccess()).thenReturn(accessConfig);
        when(telegramUserService.findById(userId)).thenReturn(Optional.empty());

        priorityService = new TelegramUserPriorityService(telegramUserService, whitelistService, telegramProperties);

        UserPriority result = priorityService.getUserPriority(userId);

        assertEquals(UserPriority.ADMIN, result);
    }

    @Test
    void whenUserAdminFlagTrue_thenAdmin() {
        Long userId = 2L;
        TelegramUser user = new TelegramUser();
        user.setIsAdmin(true);
        when(telegramProperties.getAccess()).thenReturn(accessConfig);
        when(telegramUserService.findById(userId)).thenReturn(Optional.of(user));

        priorityService = new TelegramUserPriorityService(telegramUserService, whitelistService, telegramProperties);

        UserPriority result = priorityService.getUserPriority(userId);

        assertEquals(UserPriority.ADMIN, result);
    }

    @Test
    void whenUserInVipIds_thenVip() {
        Long userId = 3L;
        vipLevel.getIds().add(userId);
        when(telegramProperties.getAccess()).thenReturn(accessConfig);
        when(telegramUserService.findById(userId)).thenReturn(Optional.empty());
        when(whitelistService.isUserAllowed(userId)).thenReturn(true);

        priorityService = new TelegramUserPriorityService(telegramUserService, whitelistService, telegramProperties);

        UserPriority result = priorityService.getUserPriority(userId);

        assertEquals(UserPriority.VIP, result);
    }

    @Test
    void whenUserPremium_thenVip() {
        Long userId = 4L;
        TelegramUser user = new TelegramUser();
        user.setIsPremium(true);
        when(telegramProperties.getAccess()).thenReturn(accessConfig);
        when(telegramUserService.findById(userId)).thenReturn(Optional.of(user));
        when(whitelistService.isUserAllowed(userId)).thenReturn(true);

        priorityService = new TelegramUserPriorityService(telegramUserService, whitelistService, telegramProperties);

        UserPriority result = priorityService.getUserPriority(userId);

        assertEquals(UserPriority.VIP, result);
    }

    @Test
    void whenUserInVipChannel_thenVipAndEnsureUserLevelCalled() {
        Long userId = 5L;
        TelegramUser user = new TelegramUser();
        user.setId(userId);
        user.setTelegramId(12345L);
        when(telegramProperties.getAccess()).thenReturn(accessConfig);
        when(telegramUserService.findById(userId)).thenReturn(Optional.of(user));

        vipLevel.getChannels().add("@vipgroup");
        when(whitelistService.isUserAllowed(userId)).thenReturn(true);
        when(whitelistService.checkUserInChannel(userId, "@vipgroup")).thenReturn(true);

        priorityService = new TelegramUserPriorityService(telegramUserService, whitelistService, telegramProperties);

        UserPriority result = priorityService.getUserPriority(userId);

        assertEquals(UserPriority.VIP, result);
        verify(telegramUserService).ensureUserWithLevel(user.getTelegramId(), UserPriority.VIP);
    }

    @Test
    void whenUserOnlyInRegularChannel_thenRegular() {
        Long userId = 6L;
        regularLevel.getChannels().add("@regular");
        when(telegramProperties.getAccess()).thenReturn(accessConfig);
        when(telegramUserService.findById(userId)).thenReturn(Optional.empty());
        when(whitelistService.isUserAllowed(userId)).thenReturn(true);
        when(whitelistService.checkUserInChannel(userId)).thenReturn(true);

        priorityService = new TelegramUserPriorityService(telegramUserService, whitelistService, telegramProperties);

        UserPriority result = priorityService.getUserPriority(userId);

        assertEquals(UserPriority.REGULAR, result);
    }

    @Test
    void whenUserInAdminChannel_thenAdmin() {
        Long userId = 8L;
        adminLevel.getChannels().add("@adminchat");
        when(telegramProperties.getAccess()).thenReturn(accessConfig);
        when(telegramUserService.findById(userId)).thenReturn(Optional.empty());
        when(whitelistService.checkUserInChannel(userId, "@adminchat")).thenReturn(true);

        priorityService = new TelegramUserPriorityService(telegramUserService, whitelistService, telegramProperties);

        assertEquals(UserPriority.ADMIN, priorityService.getUserPriority(userId));
    }

    @Test
    void whenAdminDefaultBlocked_andUserNotAdmin_thenBlocked() {
        Long userId = 9L;
        adminLevel.setDefaultBlocked(true);
        when(telegramProperties.getAccess()).thenReturn(accessConfig);
        when(telegramUserService.findById(userId)).thenReturn(Optional.empty());

        priorityService = new TelegramUserPriorityService(telegramUserService, whitelistService, telegramProperties);

        assertEquals(UserPriority.BLOCKED, priorityService.getUserPriority(userId));
    }

    @Test
    void whenVipDefaultBlocked_andUserNotVip_thenBlocked() {
        Long userId = 10L;
        vipLevel.setDefaultBlocked(true);
        when(telegramProperties.getAccess()).thenReturn(accessConfig);
        when(telegramUserService.findById(userId)).thenReturn(Optional.empty());
        when(whitelistService.isUserAllowed(userId)).thenReturn(false);

        priorityService = new TelegramUserPriorityService(telegramUserService, whitelistService, telegramProperties);

        assertEquals(UserPriority.BLOCKED, priorityService.getUserPriority(userId));
    }

    @Test
    void whenUserNotInWhitelistOrChannels_thenRegular() {
        // Unknown users (not in whitelist, not in any channel) get REGULAR by default.
        // BLOCKED is reserved for users explicitly flagged isBlocked = true.
        Long userId = 7L;
        when(telegramProperties.getAccess()).thenReturn(accessConfig);
        when(telegramUserService.findById(userId)).thenReturn(Optional.empty());
        when(whitelistService.isUserAllowed(userId)).thenReturn(false);

        priorityService = new TelegramUserPriorityService(telegramUserService, whitelistService, telegramProperties);

        UserPriority result = priorityService.getUserPriority(userId);

        assertEquals(UserPriority.REGULAR, result);
    }
}

