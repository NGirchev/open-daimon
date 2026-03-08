package io.github.ngirchev.aibot.telegram.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import io.github.ngirchev.aibot.telegram.repository.TelegramWhitelistRepository;
import io.github.ngirchev.aibot.telegram.service.TelegramWhitelistService;

import java.util.Set;

/**
 * Component to initialize whitelist exceptions from configuration.
 * Runs on startup and adds users from whitelist-exceptions to the DB.
 * Calls TelegramWhitelistService via proxy for correct transaction behaviour.
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
            // Check if user is already in whitelist
            if (!whitelistRepository.existsByUserId(userId)) {
                // Call via proxy so transaction works correctly
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
