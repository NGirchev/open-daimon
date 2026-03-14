package io.github.ngirchev.opendaimon.rest.service;

import io.github.ngirchev.opendaimon.common.model.AssistantRole;
import io.github.ngirchev.opendaimon.common.service.AssistantRoleService;
import io.github.ngirchev.opendaimon.rest.config.RestProperties;
import io.github.ngirchev.opendaimon.rest.model.RestUser;
import io.github.ngirchev.opendaimon.rest.repository.RestUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RestUserServiceTest {

    @Mock
    private RestUserRepository restUserRepository;
    @Mock
    private AssistantRoleService assistantRoleService;
    @Mock
    private RestProperties restProperties;

    private RestUserService service;

    @BeforeEach
    void setUp() {
        service = new RestUserService(restUserRepository, assistantRoleService, restProperties);
    }

    @Nested
    @DisplayName("getOrCreateUser")
    class GetOrCreateUser {

        @Test
        void whenUserExists_thenReturnsExisting() {
            RestUser existing = new RestUser();
            existing.setEmail("user@test.com");
            when(restUserRepository.findByEmail("user@test.com")).thenReturn(Optional.of(existing));

            RestUser result = service.getOrCreateUser("user@test.com");

            assertSame(existing, result);
            verify(restUserRepository).findByEmail("user@test.com");
        }

        @Test
        void whenUserDoesNotExist_thenCreatesAndSavesNew() {
            RestProperties.AccessConfig accessConfig = new RestProperties.AccessConfig();
            RestProperties.AccessConfig.LevelConfig admin = new RestProperties.AccessConfig.LevelConfig();
            admin.getEmails().add("new@test.com");
            accessConfig.setAdmin(admin);
            when(restProperties.getAccess()).thenReturn(accessConfig);

            when(restUserRepository.findByEmail("new@test.com")).thenReturn(Optional.empty());
            when(restUserRepository.save(any(RestUser.class))).thenAnswer(inv -> inv.getArgument(0));

            RestUser result = service.getOrCreateUser("new@test.com");

            assertNotNull(result);
            assertEquals("new@test.com", result.getEmail());
            assertEquals("new@test.com", result.getUsername());
            assertNotNull(result.getCreatedAt());
            assertNotNull(result.getUpdatedAt());
            assertNotNull(result.getLastActivityAt());
            ArgumentCaptor<RestUser> captor = ArgumentCaptor.forClass(RestUser.class);
            verify(restUserRepository).save(captor.capture());
            assertEquals("new@test.com", captor.getValue().getEmail());
            assertEquals(Boolean.TRUE, captor.getValue().getIsAdmin());
            assertEquals(Boolean.TRUE, captor.getValue().getIsPremium());
            assertEquals(Boolean.FALSE, captor.getValue().getIsBlocked());
        }
    }

    @Nested
    @DisplayName("getOrCreateAssistantRole")
    class GetOrCreateAssistantRole {

        @Test
        void whenEmailNull_thenThrowsIllegalArgumentException() {
            RestUser user = new RestUser();
            user.setEmail(null);

            assertThrows(IllegalArgumentException.class, () -> service.getOrCreateAssistantRole(user, "Default"));
        }

        @Test
        void whenUserNotFound_thenThrowsRuntimeException() {
            RestUser user = new RestUser();
            user.setEmail("nonexistent@test.com");
            when(restUserRepository.findByEmail("nonexistent@test.com")).thenReturn(Optional.empty());

            assertThrows(RuntimeException.class, () -> service.getOrCreateAssistantRole(user, "Default"));
        }

        @Test
        void whenUserHasCurrentRole_thenReturnsIt() {
            RestUser user = new RestUser();
            user.setEmail("user@test.com");
            AssistantRole role = new AssistantRole();
            role.setId(10L);
            role.setVersion(1);
            role.setContent("You are helpful.");
            RestUser managedUser = new RestUser();
            managedUser.setEmail("user@test.com");
            managedUser.setCurrentAssistantRole(role);
            when(restUserRepository.findByEmail("user@test.com")).thenReturn(Optional.of(managedUser));

            AssistantRole result = service.getOrCreateAssistantRole(user, "Default");

            assertSame(role, result);
            verify(restUserRepository).findByEmail("user@test.com");
        }

        @Test
        void whenUserHasNoRole_thenCreatesViaAssistantRoleServiceAndSavesUser() {
            RestUser user = new RestUser();
            user.setEmail("user@test.com");
            RestUser managedUser = new RestUser();
            managedUser.setEmail("user@test.com");
            managedUser.setCurrentAssistantRole(null);
            AssistantRole newRole = new AssistantRole();
            newRole.setId(5L);
            newRole.setVersion(1);
            newRole.setContent("Default");
            when(restUserRepository.findByEmail("user@test.com")).thenReturn(Optional.of(managedUser));
            when(assistantRoleService.getOrCreateDefaultRole(any(RestUser.class), eq("Default"))).thenReturn(newRole);
            when(restUserRepository.save(any(RestUser.class))).thenAnswer(inv -> inv.getArgument(0));

            AssistantRole result = service.getOrCreateAssistantRole(user, "Default");

            assertSame(newRole, result);
            verify(assistantRoleService).getOrCreateDefaultRole(any(RestUser.class), eq("Default"));
            verify(restUserRepository).save(managedUser);
            assertEquals(newRole, managedUser.getCurrentAssistantRole());
        }
    }

    @Nested
    @DisplayName("findByEmail / findById")
    class Find {

        @Test
        void findByEmail_delegatesToRepository() {
            RestUser user = new RestUser();
            when(restUserRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));

            assertEquals(Optional.of(user), service.findByEmail("user@test.com"));
            verify(restUserRepository).findByEmail("user@test.com");
        }

        @Test
        void findById_delegatesToRepository() {
            RestUser user = new RestUser();
            user.setId(1L);
            when(restUserRepository.findById(1L)).thenReturn(Optional.of(user));

            assertEquals(Optional.of(user), service.findById(1L));
            verify(restUserRepository).findById(1L);
        }
    }
}
