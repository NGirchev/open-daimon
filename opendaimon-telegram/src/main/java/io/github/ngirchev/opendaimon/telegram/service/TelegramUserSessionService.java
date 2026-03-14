package io.github.ngirchev.opendaimon.telegram.service;

import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import io.github.ngirchev.opendaimon.telegram.model.TelegramUser;
import io.github.ngirchev.opendaimon.telegram.model.TelegramUserSession;
import io.github.ngirchev.opendaimon.telegram.repository.TelegramUserRepository;
import io.github.ngirchev.opendaimon.telegram.repository.TelegramUserSessionRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RequiredArgsConstructor
public class TelegramUserSessionService {
    
    private final TelegramUserSessionRepository telegramUserSessionRepository;
    private final TelegramUserRepository telegramUserRepository;
    
    @Transactional
    @SuppressWarnings("java:S6809")
    public TelegramUserSession getOrCreateSession(TelegramUser telegramUser) {
        return getOrCreateSessionInner(requirePersistedUser(telegramUser));
    }

    @Transactional
    public TelegramUserSession createSession(TelegramUser telegramUser) {
        return createSessionInner(requirePersistedUser(telegramUser));
    }

    @Transactional(readOnly = true)
    public List<TelegramUserSession> findActiveSessionsForUser(Long userId) {
        return telegramUserSessionRepository.findActiveSessionsForUser(userId);
    }
    
    @Transactional
    public void closeSession(TelegramUserSession session) {
        session.setIsActive(false);
        session.setExpiredAt(OffsetDateTime.now());
        telegramUserSessionRepository.save(session);
    }
    
    @Transactional
    public TelegramUserSession updateSessionStatus(TelegramUser telegramUser, String botStatus) {
        TelegramUserSession session = getOrCreateSessionInner(requirePersistedUser(telegramUser));
        session.setBotStatus(botStatus);
        return telegramUserSessionRepository.save(session);
    }
    
    private TelegramUserSession getOrCreateSessionInner(TelegramUser telegramUser) {
        Optional<TelegramUserSession> activeSession = telegramUserSessionRepository.findByTelegramUserAndIsActiveTrue(telegramUser);

        if (activeSession.isPresent()) {
            TelegramUserSession session = activeSession.get();
            updateSessionActivity(session);
            return telegramUserSessionRepository.save(session);
        } else {
            return createSessionInner(telegramUser);
        }
    }

    private void updateSessionActivity(TelegramUserSession session) {
        session.setUpdatedAt(OffsetDateTime.now());
    }

    private TelegramUserSession createSessionInner(TelegramUser telegramUser) {
        TelegramUserSession session = new TelegramUserSession();
        session.setTelegramUser(telegramUser);
        session.setSessionId(UUID.randomUUID().toString());
        session.setIsActive(true);
        return telegramUserSessionRepository.save(session);
    }

    private TelegramUser requirePersistedUser(TelegramUser telegramUser) {
        if (telegramUser == null) {
            throw new IllegalArgumentException("telegramUser is required");
        }
        if (telegramUser.getId() != null) {
            return telegramUser;
        }

        Long telegramId = telegramUser.getTelegramId();
        if (telegramId == null) {
            throw new IllegalArgumentException("telegramUser.telegramId is required to create a session");
        }

        return telegramUserRepository.findByTelegramId(telegramId)
                .orElseGet(() -> telegramUserRepository.saveAndFlush(telegramUser));
    }
}
