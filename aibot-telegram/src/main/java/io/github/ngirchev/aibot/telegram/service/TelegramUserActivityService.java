package io.github.ngirchev.aibot.telegram.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import io.github.ngirchev.aibot.telegram.model.TelegramUser;
import io.github.ngirchev.aibot.telegram.model.TelegramUserSession;
import io.github.ngirchev.aibot.telegram.repository.TelegramUserSessionRepository;

import java.time.OffsetDateTime;

@Slf4j
@RequiredArgsConstructor
public class TelegramUserActivityService {

    private final TelegramUserSessionRepository sessionRepository;
    private final TelegramUserSessionService sessionService;

    private static final int INACTIVITY_THRESHOLD_MINUS = 15;
    private static final int BATCH_SIZE = 100;

    @Scheduled(fixedRate = 600000) // Every hour
    @Transactional
    public void checkUserActivity() {
        log.info("Starting user activity check");

        OffsetDateTime threshold = OffsetDateTime.now().minusMinutes(INACTIVITY_THRESHOLD_MINUS);
        int page = 0;
        Page<TelegramUserSession> activeSessionsPage;

        do {
            activeSessionsPage = sessionRepository.findActiveSessionsBefore(
                threshold, PageRequest.of(page, BATCH_SIZE));

            log.info("Processing page {} of {} with {} sessions",
                page + 1, activeSessionsPage.getTotalPages(), activeSessionsPage.getNumberOfElements());

            activeSessionsPage.getContent().forEach(session -> {
                try {
                    checkUserAndCloseSession(session);
                } catch (Exception e) {
                    log.error("Error checking activity for user {}: {}",
                        session.getTelegramUser().getTelegramId(), e.getMessage());
                }
            });

            page++;
        } while (activeSessionsPage.hasNext());

        log.info("User activity check completed. Processed {} pages, {} sessions total",
            page, activeSessionsPage.getTotalElements());
    }

    private void checkUserAndCloseSession(TelegramUserSession session) {
        TelegramUser user = session.getTelegramUser();

        if (user.getLastActivityAt().isBefore(OffsetDateTime.now().minusHours(INACTIVITY_THRESHOLD_MINUS))) {
            log.info("User {} inactive for more than {} hours, closing session",
                user.getTelegramId(), INACTIVITY_THRESHOLD_MINUS);
            sessionService.closeSession(session);
        }
    }
} 