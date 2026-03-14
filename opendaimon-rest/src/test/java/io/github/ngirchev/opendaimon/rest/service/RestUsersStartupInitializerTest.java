package io.github.ngirchev.opendaimon.rest.service;

import io.github.ngirchev.opendaimon.bulkhead.model.UserPriority;
import io.github.ngirchev.opendaimon.rest.config.RestProperties;
import io.github.ngirchev.opendaimon.rest.model.RestUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RestUsersStartupInitializerTest {

    @Mock
    private RestUserService restUserService;
    @Mock
    private RestProperties restProperties;

    private RestUsersStartupInitializer initializer;

    @BeforeEach
    void setUp() {
        initializer = new RestUsersStartupInitializer(restUserService, restProperties);
    }

    @Test
    @DisplayName("onApplicationReady calls ensureUserWithLevel for each configured email with correct level")
    void onApplicationReady_callsEnsureForEachEmailWithLevel() {
        RestProperties.AccessConfig access = new RestProperties.AccessConfig();
        access.getAdmin().getEmails().add("admin@test.com");
        access.getVip().getEmails().add("vip@test.com");
        access.getRegular().getEmails().add("regular@test.com");
        when(restProperties.getAccess()).thenReturn(access);

        initializer.onApplicationReady();

        verify(restUserService).ensureUserWithLevel(eq("admin@test.com"), eq(UserPriority.ADMIN));
        verify(restUserService).ensureUserWithLevel(eq("vip@test.com"), eq(UserPriority.VIP));
        verify(restUserService).ensureUserWithLevel(eq("regular@test.com"), eq(UserPriority.REGULAR));
    }

    @Test
    @DisplayName("onApplicationReady uses ADMIN over VIP over REGULAR when email in multiple levels")
    void onApplicationReady_prioritizesAdminOverVipOverRegular() {
        RestProperties.AccessConfig access = new RestProperties.AccessConfig();
        access.getAdmin().getEmails().add("both@test.com");
        access.getVip().getEmails().add("both@test.com");
        access.getRegular().getEmails().add("both@test.com");
        when(restProperties.getAccess()).thenReturn(access);

        initializer.onApplicationReady();

        verify(restUserService, times(1)).ensureUserWithLevel(eq("both@test.com"), eq(UserPriority.ADMIN));
    }

    @Test
    @DisplayName("onApplicationReady when access null does not call ensureUserWithLevel")
    void onApplicationReady_whenAccessNull_doesNotCallEnsure() {
        when(restProperties.getAccess()).thenReturn(null);

        initializer.onApplicationReady();

        verify(restUserService, never()).ensureUserWithLevel(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("onApplicationReady when no emails configured does not call ensureUserWithLevel")
    void onApplicationReady_whenNoEmails_doesNotCallEnsure() {
        RestProperties.AccessConfig access = new RestProperties.AccessConfig();
        when(restProperties.getAccess()).thenReturn(access);

        initializer.onApplicationReady();

        verify(restUserService, never()).ensureUserWithLevel(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("applyFlagsByLevel sets strict matrix for ADMIN")
    void applyFlagsByLevel_admin_setsFlags() {
        RestUser user = new RestUser();
        user.setIsAdmin(false);
        user.setIsPremium(false);
        user.setIsBlocked(true);

        RestUserService.applyFlagsByLevel(user, UserPriority.ADMIN);

        assertEquals(Boolean.TRUE, user.getIsAdmin());
        assertEquals(Boolean.TRUE, user.getIsPremium());
        assertEquals(Boolean.FALSE, user.getIsBlocked());
    }

    @Test
    @DisplayName("applyFlagsByLevel sets strict matrix for VIP")
    void applyFlagsByLevel_vip_setsFlags() {
        RestUser user = new RestUser();
        user.setIsAdmin(true);
        user.setIsPremium(false);

        RestUserService.applyFlagsByLevel(user, UserPriority.VIP);

        assertEquals(Boolean.FALSE, user.getIsAdmin());
        assertEquals(Boolean.TRUE, user.getIsPremium());
        assertEquals(Boolean.FALSE, user.getIsBlocked());
    }

    @Test
    @DisplayName("applyFlagsByLevel sets strict matrix for REGULAR")
    void applyFlagsByLevel_regular_setsFlags() {
        RestUser user = new RestUser();
        user.setIsAdmin(true);
        user.setIsPremium(true);

        RestUserService.applyFlagsByLevel(user, UserPriority.REGULAR);

        assertEquals(Boolean.FALSE, user.getIsAdmin());
        assertEquals(Boolean.FALSE, user.getIsPremium());
        assertEquals(Boolean.FALSE, user.getIsBlocked());
    }
}
