package io.github.ngirchev.opendaimon.telegram.service;

import io.github.ngirchev.opendaimon.bulkhead.model.UserPriority;
import io.github.ngirchev.opendaimon.telegram.TelegramBot;
import io.github.ngirchev.opendaimon.telegram.config.TelegramProperties;
import io.github.ngirchev.opendaimon.telegram.model.TelegramUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TelegramUsersStartupInitializerTest {

    @Mock
    private TelegramUserService telegramUserService;
    @Mock
    private TelegramProperties telegramProperties;
    @Mock
    private org.springframework.beans.factory.ObjectProvider<TelegramBot> telegramBotProvider;

    private TelegramUsersStartupInitializer initializer;

    @BeforeEach
    void setUp() {
        lenient().when(telegramBotProvider.getIfAvailable()).thenReturn(null);
        initializer = new TelegramUsersStartupInitializer(telegramUserService, telegramProperties, telegramBotProvider);
    }

    @Test
    @DisplayName("onApplicationReady calls ensureUserWithLevel for each configured id with correct level")
    void onApplicationReady_callsEnsureForEachIdWithLevel() {
        TelegramProperties.AccessConfig access = new TelegramProperties.AccessConfig();
        access.getAdmin().getIds().add(1L);
        access.getVip().getIds().add(2L);
        access.getRegular().getIds().add(3L);
        when(telegramProperties.getAccess()).thenReturn(access);

        initializer.onApplicationReady();

        verify(telegramUserService).ensureUserWithLevel(eq(1L), eq(UserPriority.ADMIN), any());
        verify(telegramUserService).ensureUserWithLevel(eq(2L), eq(UserPriority.VIP), any());
        verify(telegramUserService).ensureUserWithLevel(eq(3L), eq(UserPriority.REGULAR), any());
    }

    @Test
    @DisplayName("onApplicationReady uses ADMIN over VIP over REGULAR when id in multiple levels")
    void onApplicationReady_prioritizesAdminOverVipOverRegular() {
        TelegramProperties.AccessConfig access = new TelegramProperties.AccessConfig();
        access.getAdmin().getIds().add(100L);
        access.getVip().getIds().add(100L);
        access.getRegular().getIds().add(100L);
        when(telegramProperties.getAccess()).thenReturn(access);

        initializer.onApplicationReady();

        verify(telegramUserService, times(1)).ensureUserWithLevel(eq(100L), eq(UserPriority.ADMIN), any());
    }

    @Test
    @DisplayName("onApplicationReady when access null does not call ensureUserWithLevel")
    void onApplicationReady_whenAccessNull_doesNotCallEnsure() {
        when(telegramProperties.getAccess()).thenReturn(null);

        initializer.onApplicationReady();

        verify(telegramUserService, never()).ensureUserWithLevel(any(), any(), any());
    }

    @Test
    @DisplayName("onApplicationReady when no ids configured does not call ensureUserWithLevel")
    void onApplicationReady_whenNoIds_doesNotCallEnsure() {
        TelegramProperties.AccessConfig access = new TelegramProperties.AccessConfig();
        when(telegramProperties.getAccess()).thenReturn(access);

        initializer.onApplicationReady();

        verify(telegramUserService, never()).ensureUserWithLevel(any(), any(), any());
    }

    @Test
    @DisplayName("applyFlagsByLevel sets strict matrix for ADMIN")
    void applyFlagsByLevel_admin_setsFlags() {
        TelegramUser user = new TelegramUser();
        user.setIsAdmin(false);
        user.setIsPremium(false);
        user.setIsBlocked(true);

        TelegramUserService.applyFlagsByLevel(user, UserPriority.ADMIN);

        assertEquals(Boolean.TRUE, user.getIsAdmin());
        assertEquals(Boolean.TRUE, user.getIsPremium());
        assertEquals(Boolean.FALSE, user.getIsBlocked());
    }

    @Test
    @DisplayName("applyFlagsByLevel sets strict matrix for VIP")
    void applyFlagsByLevel_vip_setsFlags() {
        TelegramUser user = new TelegramUser();
        user.setIsAdmin(true);
        user.setIsPremium(false);

        TelegramUserService.applyFlagsByLevel(user, UserPriority.VIP);

        assertEquals(Boolean.FALSE, user.getIsAdmin());
        assertEquals(Boolean.TRUE, user.getIsPremium());
        assertEquals(Boolean.FALSE, user.getIsBlocked());
    }

    @Test
    @DisplayName("applyFlagsByLevel sets strict matrix for REGULAR")
    void applyFlagsByLevel_regular_setsFlags() {
        TelegramUser user = new TelegramUser();
        user.setIsAdmin(true);
        user.setIsPremium(true);

        TelegramUserService.applyFlagsByLevel(user, UserPriority.REGULAR);

        assertEquals(Boolean.FALSE, user.getIsAdmin());
        assertEquals(Boolean.FALSE, user.getIsPremium());
        assertEquals(Boolean.FALSE, user.getIsBlocked());
    }
}
