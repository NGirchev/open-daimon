package io.github.ngirchev.opendaimon.common.service.impl;

import io.github.ngirchev.opendaimon.common.model.AssistantRole;
import io.github.ngirchev.opendaimon.common.model.User;
import io.github.ngirchev.opendaimon.common.repository.AssistantRoleRepository;
import io.github.ngirchev.opendaimon.common.service.AssistantRoleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AssistantRoleServiceImplTest {

    @Mock
    private AssistantRoleRepository assistantRoleRepository;

    @Mock
    private ObjectProvider<AssistantRoleService> selfProvider;

    @Mock
    private User user;

    private AssistantRoleServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AssistantRoleServiceImpl(assistantRoleRepository, selfProvider);
        when(selfProvider.getObject()).thenReturn(service);
    }

    @Test
    void getActiveRole_returnsEmptyWhenNone() {
        when(assistantRoleRepository.findActiveByUser(user)).thenReturn(Optional.empty());
        assertTrue(service.getActiveRole(user).isEmpty());
    }

    @Test
    void getActiveRole_returnsRoleWhenPresent() {
        AssistantRole role = new AssistantRole();
        role.setId(1L);
        role.setContent("You are helpful.");
        role.setUser(user);
        when(assistantRoleRepository.findActiveByUser(user)).thenReturn(Optional.of(role));
        Optional<AssistantRole> result = service.getActiveRole(user);
        assertTrue(result.isPresent());
        assertEquals(1L, result.get().getId());
        assertEquals("You are helpful.", result.get().getContent());
    }

    @Test
    void createOrGetRole_createsNewWhenNoExisting() {
        when(user.getId()).thenReturn(100L);
        when(assistantRoleRepository.findByUserAndContentHash(eq(user), any())).thenReturn(Optional.empty());
        when(assistantRoleRepository.findMaxVersionByUser(user)).thenReturn(0);
        AssistantRole saved = new AssistantRole();
        saved.setId(1L);
        saved.setContent("New role");
        saved.setUser(user);
        saved.setContentHash(String.valueOf("New role".hashCode()));
        saved.setVersion(1);
        when(assistantRoleRepository.save(any(AssistantRole.class))).thenAnswer(inv -> {
            AssistantRole r = inv.getArgument(0);
            r.setId(1L);
            return r;
        });

        AssistantRole result = service.createOrGetRole(user, "New role");

        assertNotNull(result);
        assertEquals(1, result.getVersion());
        verify(assistantRoleRepository).save(any(AssistantRole.class));
    }

    @Test
    void createOrGetRole_returnsExistingWhenContentHashMatches() {
        AssistantRole existing = new AssistantRole();
        existing.setId(2L);
        existing.setContent("Same content");
        existing.setUser(user);
        when(assistantRoleRepository.findByUserAndContentHash(eq(user), any())).thenReturn(Optional.of(existing));

        AssistantRole result = service.createOrGetRole(user, "Same content");

        assertEquals(2L, result.getId());
        verify(assistantRoleRepository, never()).save(any());
    }

    @Test
    void setActiveRole_deactivatesOthersAndActivatesGiven() {
        AssistantRole role = new AssistantRole();
        role.setId(1L);
        role.setUser(user);
        AssistantRole managed = new AssistantRole();
        managed.setId(1L);
        managed.setUser(user);
        managed.setIsActive(false);
        when(assistantRoleRepository.findById(1L)).thenReturn(Optional.of(managed));
        when(assistantRoleRepository.save(any(AssistantRole.class))).thenAnswer(inv -> inv.getArgument(0));

        service.setActiveRole(role);

        verify(assistantRoleRepository).deactivateAllByUser(user);
        verify(assistantRoleRepository).save(managed);
        assertTrue(managed.getIsActive());
    }

    @Test
    void setActiveRole_throwsWhenRoleNotFound() {
        when(assistantRoleRepository.findById(999L)).thenReturn(Optional.empty());
        AssistantRole role = new AssistantRole();
        role.setId(999L);
        assertThrows(IllegalArgumentException.class, () -> service.setActiveRole(role));
    }

    @Test
    void getAllUserRoles_returnsFromRepository() {
        AssistantRole r = new AssistantRole();
        r.setId(1L);
        when(assistantRoleRepository.findAllByUserOrderByVersionDesc(user)).thenReturn(List.of(r));
        List<AssistantRole> result = service.getAllUserRoles(user);
        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).getId());
    }

    @Test
    void getRoleByVersion_returnsFromRepository() {
        AssistantRole r = new AssistantRole();
        r.setId(1L);
        r.setVersion(2);
        when(assistantRoleRepository.findByUserAndVersion(user, 2)).thenReturn(Optional.of(r));
        Optional<AssistantRole> result = service.getRoleByVersion(user, 2);
        assertTrue(result.isPresent());
        assertEquals(2, result.get().getVersion());
    }

    @Test
    void findById_returnsFromRepository() {
        AssistantRole r = new AssistantRole();
        r.setId(1L);
        when(assistantRoleRepository.findById(1L)).thenReturn(Optional.of(r));
        Optional<AssistantRole> result = service.findById(1L);
        assertTrue(result.isPresent());
        assertEquals(1L, result.get().getId());
    }

    @Test
    void cleanupUnusedRoles_deletesAndReturnsCount() {
        AssistantRole unused = new AssistantRole();
        unused.setId(1L);
        OffsetDateTime threshold = OffsetDateTime.now().minusDays(30);
        when(assistantRoleRepository.findUnusedRoles(threshold)).thenReturn(List.of(unused));
        int count = service.cleanupUnusedRoles(threshold);
        assertEquals(1, count);
        verify(assistantRoleRepository).deleteAll(List.of(unused));
    }

    @Test
    void findUnusedRoles_returnsFromRepository() {
        when(assistantRoleRepository.findUnusedRoles(any())).thenReturn(List.of());
        List<AssistantRole> result = service.findUnusedRoles(OffsetDateTime.now());
        assertTrue(result.isEmpty());
    }

    @Test
    void incrementUsage_reloadsAndSaves() {
        AssistantRole role = new AssistantRole();
        role.setId(1L);
        AssistantRole managed = new AssistantRole();
        managed.setId(1L);
        managed.setUsageCount(0L);
        when(assistantRoleRepository.findById(1L)).thenReturn(Optional.of(managed));
        when(assistantRoleRepository.save(any(AssistantRole.class))).thenAnswer(inv -> inv.getArgument(0));

        service.incrementUsage(role);

        verify(assistantRoleRepository).save(managed);
    }

    @Test
    void updateActiveRole_whenContentUnchanged_returnsCurrentRole() {
        when(user.getId()).thenReturn(100L);
        AssistantRole current = new AssistantRole();
        current.setId(1L);
        current.setContent("Same");
        current.setUser(user);
        when(assistantRoleRepository.findActiveByUser(user)).thenReturn(Optional.of(current));

        AssistantRole result = service.updateActiveRole(user, "Same");

        assertEquals(current, result);
        verify(assistantRoleRepository, never()).save(any(AssistantRole.class));
    }

    @Test
    void updateActiveRole_whenContentChanged_createsNewAndActivates() {
        when(user.getId()).thenReturn(100L);
        AssistantRole current = new AssistantRole();
        current.setId(1L);
        current.setContent("Old");
        current.setUser(user);
        when(assistantRoleRepository.findActiveByUser(user)).thenReturn(Optional.of(current));
        when(assistantRoleRepository.findByUserAndContentHash(eq(user), any())).thenReturn(Optional.empty());
        when(assistantRoleRepository.findMaxVersionByUser(user)).thenReturn(1);
        AssistantRole newRole = new AssistantRole();
        newRole.setId(2L);
        newRole.setContent("New");
        newRole.setUser(user);
        newRole.setVersion(2);
        when(assistantRoleRepository.save(any(AssistantRole.class))).thenAnswer(inv -> {
            AssistantRole r = inv.getArgument(0);
            if (r.getId() == null) {
                r.setId(2L);
            }
            return r;
        });
        when(assistantRoleRepository.findById(2L)).thenReturn(Optional.of(newRole));

        AssistantRole result = service.updateActiveRole(user, "New");

        assertNotNull(result);
        verify(assistantRoleRepository).deactivateAllByUser(user);
        verify(assistantRoleRepository, atLeast(1)).save(any(AssistantRole.class));
    }

    @Test
    void getOrCreateDefaultRole_whenActivePresent_returnsIt() {
        when(user.getId()).thenReturn(100L);
        AssistantRole active = new AssistantRole();
        active.setId(1L);
        active.setContent("Default");
        active.setUser(user);
        when(assistantRoleRepository.findActiveByUser(user)).thenReturn(Optional.of(active));

        AssistantRole result = service.getOrCreateDefaultRole(user, "Default");

        assertEquals(active, result);
        verify(assistantRoleRepository, never()).save(any(AssistantRole.class));
    }

    @Test
    void getOrCreateDefaultRole_whenNoActive_createsAndActivates() {
        when(user.getId()).thenReturn(100L);
        when(assistantRoleRepository.findActiveByUser(user)).thenReturn(Optional.empty());
        when(assistantRoleRepository.findByUserAndContentHash(eq(user), any())).thenReturn(Optional.empty());
        when(assistantRoleRepository.findMaxVersionByUser(user)).thenReturn(0);
        when(assistantRoleRepository.save(any(AssistantRole.class))).thenAnswer(inv -> {
            AssistantRole r = inv.getArgument(0);
            r.setId(1L);
            return r;
        });
        AssistantRole created = new AssistantRole();
        created.setId(1L);
        created.setUser(user);
        when(assistantRoleRepository.findById(1L)).thenReturn(Optional.of(created));

        AssistantRole result = service.getOrCreateDefaultRole(user, "Default");

        assertNotNull(result);
        assertEquals(1L, result.getId());
        verify(assistantRoleRepository).deactivateAllByUser(user);
        verify(assistantRoleRepository, atLeast(1)).save(any(AssistantRole.class));
    }
}
