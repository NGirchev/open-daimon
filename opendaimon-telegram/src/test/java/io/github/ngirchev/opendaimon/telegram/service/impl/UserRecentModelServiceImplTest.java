package io.github.ngirchev.opendaimon.telegram.service.impl;

import io.github.ngirchev.opendaimon.common.model.User;
import io.github.ngirchev.opendaimon.common.model.UserRecentModel;
import io.github.ngirchev.opendaimon.common.repository.UserRecentModelRepository;
import io.github.ngirchev.opendaimon.common.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserRecentModelServiceImplTest {

    private static final Long USER_ID = 42L;

    @Mock
    private UserRecentModelRepository userRecentModelRepository;
    @Mock
    private UserRepository userRepository;

    private UserRecentModelServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new UserRecentModelServiceImpl(userRecentModelRepository, userRepository);
    }

    @Test
    void shouldInsertWhenAbsent() {
        when(userRecentModelRepository.findByUserIdAndModelName(USER_ID, "gpt-4"))
                .thenReturn(Optional.empty());
        User userRef = new User();
        userRef.setId(USER_ID);
        when(userRepository.getReferenceById(USER_ID)).thenReturn(userRef);
        when(userRecentModelRepository.findTopByUser(eq(USER_ID), any(Pageable.class)))
                .thenReturn(List.of(entry(1L, "gpt-4", OffsetDateTime.now())));

        service.recordUsage(USER_ID, "gpt-4");

        ArgumentCaptor<UserRecentModel> captor = ArgumentCaptor.forClass(UserRecentModel.class);
        verify(userRecentModelRepository).save(captor.capture());
        UserRecentModel saved = captor.getValue();
        assertThat(saved.getModelName()).isEqualTo("gpt-4");
        assertThat(saved.getUser()).isSameAs(userRef);
        assertThat(saved.getLastUsedAt()).isNotNull();
    }

    @Test
    void shouldUpdateTimestampWhenPresent() {
        OffsetDateTime oldTs = OffsetDateTime.now().minusDays(1);
        UserRecentModel existing = entry(5L, "claude-opus", oldTs);
        when(userRecentModelRepository.findByUserIdAndModelName(USER_ID, "claude-opus"))
                .thenReturn(Optional.of(existing));
        when(userRecentModelRepository.findTopByUser(eq(USER_ID), any(Pageable.class)))
                .thenReturn(List.of(existing));

        service.recordUsage(USER_ID, "claude-opus");

        assertThat(existing.getLastUsedAt()).isAfter(oldTs);
        verify(userRecentModelRepository).save(existing);
        verify(userRepository, never()).getReferenceById(any());
    }

    @Test
    void shouldPruneBeyondEightOnWrite() {
        when(userRecentModelRepository.findByUserIdAndModelName(USER_ID, "new-model"))
                .thenReturn(Optional.empty());
        User userRef = new User();
        userRef.setId(USER_ID);
        when(userRepository.getReferenceById(USER_ID)).thenReturn(userRef);

        List<UserRecentModel> topEight = IntStream.range(0, 8)
                .mapToObj(i -> entry((long) (100 + i), "m" + i, OffsetDateTime.now().minusMinutes(i)))
                .toList();
        when(userRecentModelRepository.findTopByUser(eq(USER_ID), any(Pageable.class)))
                .thenReturn(topEight);

        service.recordUsage(USER_ID, "new-model");

        ArgumentCaptor<List<Long>> retainCaptor = ArgumentCaptor.forClass(List.class);
        verify(userRecentModelRepository).deleteByUserIdAndIdNotIn(eq(USER_ID), retainCaptor.capture());
        assertThat(retainCaptor.getValue()).containsExactly(100L, 101L, 102L, 103L, 104L, 105L, 106L, 107L);
    }

    @Test
    void shouldReturnEmptyWhenNoHistory() {
        when(userRecentModelRepository.findTopByUser(eq(USER_ID), any(Pageable.class)))
                .thenReturn(List.of());

        List<String> result = service.getRecentModels(USER_ID, 8);

        assertThat(result).isEmpty();
    }

    @Test
    void shouldReturnRecentModelsOrderedByRepository() {
        UserRecentModel first = entry(1L, "alpha", OffsetDateTime.now());
        UserRecentModel second = entry(2L, "beta", OffsetDateTime.now().minusMinutes(5));
        when(userRecentModelRepository.findTopByUser(eq(USER_ID), any(Pageable.class)))
                .thenReturn(List.of(first, second));

        List<String> result = service.getRecentModels(USER_ID, 8);

        assertThat(result).containsExactly("alpha", "beta");
    }

    @Test
    void shouldSkipRecordWhenUserIdNull() {
        service.recordUsage(null, "gpt-4");

        verifyNoInteractions(userRecentModelRepository);
        verifyNoInteractions(userRepository);
    }

    @Test
    void shouldSkipRecordWhenModelNameBlank() {
        service.recordUsage(USER_ID, "   ");

        verifyNoInteractions(userRecentModelRepository);
        verifyNoInteractions(userRepository);
    }

    @Test
    void shouldReturnEmptyWhenLimitNonPositive() {
        List<String> result = service.getRecentModels(USER_ID, 0);

        assertThat(result).isEmpty();
        verifyNoInteractions(userRecentModelRepository);
    }

    @Test
    void shouldNotPruneWhenNoEntriesExist() {
        when(userRecentModelRepository.findByUserIdAndModelName(USER_ID, "gpt-4"))
                .thenReturn(Optional.empty());
        User userRef = new User();
        userRef.setId(USER_ID);
        when(userRepository.getReferenceById(USER_ID)).thenReturn(userRef);
        when(userRecentModelRepository.findTopByUser(eq(USER_ID), any(Pageable.class)))
                .thenReturn(List.of());

        service.recordUsage(USER_ID, "gpt-4");

        verify(userRecentModelRepository, never()).deleteByUserIdAndIdNotIn(any(), anyList());
    }

    private UserRecentModel entry(Long id, String name, OffsetDateTime ts) {
        UserRecentModel entry = new UserRecentModel();
        entry.setId(id);
        entry.setModelName(name);
        entry.setLastUsedAt(ts);
        return entry;
    }
}
