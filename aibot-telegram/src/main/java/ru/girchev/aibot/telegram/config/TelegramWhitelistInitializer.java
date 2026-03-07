package ru.girchev.aibot.telegram.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import ru.girchev.aibot.telegram.repository.TelegramWhitelistRepository;
import ru.girchev.aibot.telegram.service.TelegramWhitelistService;

import java.util.Set;

/**
 * Компонент для инициализации whitelist исключений из конфигурации.
 * Выполняется при старте приложения и добавляет пользователей из whitelist-exceptions в БД.
 * Вызывает методы TelegramWhitelistService через прокси для корректной работы транзакций.
 */
@Slf4j
@RequiredArgsConstructor
public class TelegramWhitelistInitializer {

    private final TelegramWhitelistService whitelistService;
    private final TelegramWhitelistRepository whitelistRepository;
    private final TelegramProperties telegramProperties;

    @PostConstruct
    @Transactional
    public void initWhitelistExceptions() {
        Set<Long> whitelistExceptions = telegramProperties.getWhitelistExceptionsSet();
        
        log.info("Initializing whitelist exceptions. Got list: {}", whitelistExceptions);

        if (whitelistExceptions == null || whitelistExceptions.isEmpty()) {
            log.info("Whitelist exceptions list is empty, skipping initialization");
            return;
        }

        log.info("Adding exceptions from config to whitelist: {}", whitelistExceptions);
        
        int addedCount = 0;
        int skippedCount = 0;
        
        for (Long userId : whitelistExceptions) {
            // Проверяем, есть ли уже пользователь в whitelist
            if (!whitelistRepository.existsByUserId(userId)) {
                // Вызываем через прокси, чтобы транзакция работала корректно
                whitelistService.addToWhitelist(userId);
                addedCount++;
                log.info("User {} added to whitelist from config", userId);
            } else {
                skippedCount++;
                log.debug("User {} already in whitelist, skipping", userId);
            }
        }

        log.info("Whitelist exceptions initialization completed. Added: {}, skipped: {}", addedCount, skippedCount);
    }
}
