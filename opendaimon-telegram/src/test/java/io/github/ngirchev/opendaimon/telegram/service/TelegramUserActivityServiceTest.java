package io.github.ngirchev.opendaimon.telegram.service;

import io.github.ngirchev.opendaimon.telegram.model.TelegramUser;
import io.github.ngirchev.opendaimon.telegram.model.TelegramUserSession;
import io.github.ngirchev.opendaimon.telegram.repository.TelegramUserSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TelegramUserActivityServiceTest {

    @Mock
    private TelegramUserSessionRepository sessionRepository;
    @Mock
    private TelegramUserSessionService sessionService;

    private TelegramUserActivityService service;

    @BeforeEach
    void setUp() {
        service = new TelegramUserActivityService(sessionRepository, sessionService);
    }

    @Test
    void checkUserActivity_whenNoSessions_doesNotCallCloseSession() {
        Page<TelegramUserSession> emptyPage = new PageImpl<>(Collections.emptyList(), PageRequest.of(0, 100), 0);
        when(sessionRepository.findActiveSessionsBefore(any(OffsetDateTime.class), any(Pageable.class)))
                .thenReturn(emptyPage);

        service.checkUserActivity();

        verify(sessionRepository).findActiveSessionsBefore(any(OffsetDateTime.class), eq(PageRequest.of(0, 100)));
        verify(sessionService, never()).closeSession(any());
    }

    @Test
    void checkUserActivity_whenSessionInactive_closesSession() {
        TelegramUser user = new TelegramUser();
        user.setTelegramId(123L);
        user.setLastActivityAt(OffsetDateTime.now().minusHours(20));

        TelegramUserSession session = new TelegramUserSession();
        session.setId(1L);
        session.setTelegramUser(user);

        Page<TelegramUserSession> singlePage = new PageImpl<>(List.of(session), PageRequest.of(0, 100), 1);
        when(sessionRepository.findActiveSessionsBefore(any(OffsetDateTime.class), any(Pageable.class)))
                .thenReturn(singlePage);

        service.checkUserActivity();

        verify(sessionService).closeSession(session);
    }

    @Test
    void checkUserActivity_whenSessionActive_doesNotClose() {
        TelegramUser user = new TelegramUser();
        user.setTelegramId(456L);
        user.setLastActivityAt(OffsetDateTime.now().minusMinutes(5));

        TelegramUserSession session = new TelegramUserSession();
        session.setId(2L);
        session.setTelegramUser(user);

        Page<TelegramUserSession> singlePage = new PageImpl<>(List.of(session), PageRequest.of(0, 100), 1);
        when(sessionRepository.findActiveSessionsBefore(any(OffsetDateTime.class), any(Pageable.class)))
                .thenReturn(singlePage);

        service.checkUserActivity();

        verify(sessionService, never()).closeSession(any());
    }

    @Test
    void checkUserActivity_pagination_processesAllPages() {
        Page<TelegramUserSession> firstPage = new PageImpl<>(
                Collections.emptyList(), PageRequest.of(0, 100), 250);
        Page<TelegramUserSession> secondPage = new PageImpl<>(
                Collections.emptyList(), PageRequest.of(1, 100), 250);
        Page<TelegramUserSession> lastPage = new PageImpl<>(
                Collections.emptyList(), PageRequest.of(2, 100), 250);

        when(sessionRepository.findActiveSessionsBefore(any(OffsetDateTime.class), eq(PageRequest.of(0, 100))))
                .thenReturn(firstPage);
        when(sessionRepository.findActiveSessionsBefore(any(OffsetDateTime.class), eq(PageRequest.of(1, 100))))
                .thenReturn(secondPage);
        when(sessionRepository.findActiveSessionsBefore(any(OffsetDateTime.class), eq(PageRequest.of(2, 100))))
                .thenReturn(lastPage);

        service.checkUserActivity();

        verify(sessionRepository).findActiveSessionsBefore(any(OffsetDateTime.class), eq(PageRequest.of(0, 100)));
        verify(sessionRepository).findActiveSessionsBefore(any(OffsetDateTime.class), eq(PageRequest.of(1, 100)));
        verify(sessionRepository).findActiveSessionsBefore(any(OffsetDateTime.class), eq(PageRequest.of(2, 100)));
    }

    @Test
    void checkUserActivity_whenCloseSessionThrows_logsAndContinues() {
        TelegramUser user = new TelegramUser();
        user.setTelegramId(789L);
        user.setLastActivityAt(OffsetDateTime.now().minusHours(20));

        TelegramUserSession session = new TelegramUserSession();
        session.setId(3L);
        session.setTelegramUser(user);

        Page<TelegramUserSession> singlePage = new PageImpl<>(List.of(session), PageRequest.of(0, 100), 1);
        when(sessionRepository.findActiveSessionsBefore(any(OffsetDateTime.class), any(Pageable.class)))
                .thenReturn(singlePage);
        doThrow(new RuntimeException("DB error")).when(sessionService).closeSession(session);

        service.checkUserActivity();

        verify(sessionService).closeSession(session);
    }
}
