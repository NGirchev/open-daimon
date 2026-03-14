package io.github.ngirchev.opendaimon.rest.service;

import io.github.ngirchev.opendaimon.common.service.MessageLocalizationService;
import io.github.ngirchev.opendaimon.rest.exception.UnauthorizedException;
import io.github.ngirchev.opendaimon.rest.model.RestUser;
import io.github.ngirchev.opendaimon.rest.repository.RestUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RestAuthorizationServiceTest {

    @Mock
    private RestUserRepository restUserRepository;
    @Mock
    private MessageLocalizationService messageLocalizationService;

    private RestAuthorizationService service;

    @BeforeEach
    void setUp() {
        service = new RestAuthorizationService(restUserRepository, messageLocalizationService);
    }

    @Nested
    @DisplayName("authorize(String email)")
    class AuthorizeSingleArg {

        @Test
        void whenUserExists_thenReturnsUser() {
            RestUser user = new RestUser();
            user.setEmail("user@test.com");
            when(restUserRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));

            RestUser result = service.authorize("user@test.com");

            assertSame(user, result);
            verify(restUserRepository).findByEmail("user@test.com");
        }

        @Test
        void whenEmailNull_thenThrowsUnauthorizedException() {
            when(messageLocalizationService.getMessage("rest.auth.email.required", (String) null))
                    .thenReturn("Email is required");

            UnauthorizedException ex = assertThrows(UnauthorizedException.class, () -> service.authorize(null));
            assertEquals("Email is required", ex.getMessage());
        }

        @Test
        void whenEmailBlank_thenThrowsUnauthorizedException() {
            when(messageLocalizationService.getMessage("rest.auth.email.required", (String) null))
                    .thenReturn("Email is required");

            UnauthorizedException ex = assertThrows(UnauthorizedException.class, () -> service.authorize("   "));
            assertEquals("Email is required", ex.getMessage());
        }

        @Test
        void whenUserNotFound_thenThrowsUnauthorizedException() {
            when(restUserRepository.findByEmail("unknown@test.com")).thenReturn(Optional.empty());
            when(messageLocalizationService.getMessage("rest.auth.user.not.found", (String) null))
                    .thenReturn("User not found");

            UnauthorizedException ex = assertThrows(UnauthorizedException.class, () -> service.authorize("unknown@test.com"));
            assertEquals("User not found", ex.getMessage());
        }
    }

    @Nested
    @DisplayName("authorize(String email, String languageCode)")
    class AuthorizeWithLanguage {

        @Test
        void whenUserExists_thenReturnsUserAndUsesLanguageCode() {
            RestUser user = new RestUser();
            user.setEmail("user@test.com");
            when(restUserRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));

            RestUser result = service.authorize("user@test.com", "en");

            assertSame(user, result);
            verify(restUserRepository).findByEmail("user@test.com");
        }

        @Test
        void whenEmailNull_thenUsesLanguageCodeInMessage() {
            when(messageLocalizationService.getMessage("rest.auth.email.required", "en"))
                    .thenReturn("Email is required (en)");

            UnauthorizedException ex = assertThrows(UnauthorizedException.class, () -> service.authorize(null, "en"));
            assertEquals("Email is required (en)", ex.getMessage());
        }

        @Test
        void whenUserNotFound_thenUsesLanguageCodeInMessage() {
            when(restUserRepository.findByEmail("unknown@test.com")).thenReturn(Optional.empty());
            when(messageLocalizationService.getMessage("rest.auth.user.not.found", "en"))
                    .thenReturn("User not found (en)");

            UnauthorizedException ex = assertThrows(UnauthorizedException.class, () -> service.authorize("unknown@test.com", "en"));
            assertEquals("User not found (en)", ex.getMessage());
        }
    }
}
