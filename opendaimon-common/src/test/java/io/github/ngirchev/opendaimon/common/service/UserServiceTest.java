package io.github.ngirchev.opendaimon.common.service;

import io.github.ngirchev.opendaimon.common.model.User;
import io.github.ngirchev.opendaimon.common.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    private UserService service;

    @BeforeEach
    void setUp() {
        service = new UserService(userRepository);
    }

    @Nested
    @DisplayName("findById")
    class FindById {

        @Test
        void whenUserExists_returnsOptionalWithUser() {
            User user = new User();
            user.setId(1L);
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));

            var result = service.findById(1L);

            assertTrue(result.isPresent());
            assertEquals(user, result.get());
            verify(userRepository).findById(1L);
        }

        @Test
        void whenUserNotFound_returnsEmptyOptional() {
            when(userRepository.findById(999L)).thenReturn(Optional.empty());

            var result = service.findById(999L);

            assertFalse(result.isPresent());
            verify(userRepository).findById(999L);
        }
    }
}
