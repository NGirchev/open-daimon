package io.github.ngirchev.aibot.telegram.service;

import io.github.ngirchev.aibot.bulkhead.model.UserPriority;
import io.github.ngirchev.aibot.telegram.TelegramBot;
import io.github.ngirchev.aibot.telegram.config.TelegramProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChat;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * On application startup, ensures all Telegram users from access config (admin/vip/regular ids)
 * exist in the database with flags set by level. Priority: ADMIN &gt; VIP &gt; REGULAR.
 * When the bot is available, fetches real user data (username, first name, last name) via getChat
 * so new users get proper names instead of "id_&lt;telegramId&gt;".
 */
@Slf4j
@RequiredArgsConstructor
public class TelegramUsersStartupInitializer {

    private final TelegramUserService telegramUserService;
    private final TelegramProperties telegramProperties;
    private final ObjectProvider<TelegramBot> telegramBotProvider;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void onApplicationReady() {
        if (telegramProperties == null || telegramProperties.getAccess() == null) {
            log.debug("Telegram access config not available, skipping Telegram users startup initialization");
            return;
        }

        Map<Long, UserPriority> idToLevel = collectIdsWithEffectiveLevel();
        if (idToLevel.isEmpty()) {
            log.info("Telegram users startup: no configured ids in access config");
            return;
        }

        log.info("Telegram users startup: initializing {} user(s) from config (admin/vip/regular ids)", idToLevel.size());
        int createdOrUpdated = 0;
        for (Map.Entry<Long, UserPriority> e : idToLevel.entrySet()) {
            try {
                Optional<Chat> chatFromApi = fetchChatByTelegramId(e.getKey());
                telegramUserService.ensureUserWithLevel(e.getKey(), e.getValue(), chatFromApi);
                createdOrUpdated++;
            } catch (Exception ex) {
                log.warn("Telegram users startup: failed to ensure user telegramId={}, level={}", e.getKey(), e.getValue(), ex);
            }
        }
        log.info("Telegram users startup: completed, {} user(s) ensured", createdOrUpdated);
    }

    /**
     * Calls Telegram getChat for a private chat (chatId = telegramId). Returns empty if bot is unavailable or API fails.
     */
    private Optional<Chat> fetchChatByTelegramId(Long telegramId) {
        TelegramBot bot = telegramBotProvider.getIfAvailable();
        if (bot == null) {
            return Optional.empty();
        }
        try {
            GetChat getChat = new GetChat();
            getChat.setChatId(telegramId.toString());
            Chat chat = bot.execute(getChat);
            return Optional.ofNullable(chat);
        } catch (TelegramApiException ex) {
            log.debug("Could not fetch Telegram chat for telegramId={}, using placeholder name: {}", telegramId, ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Collects all telegram ids from admin, vip, regular with effective level. Priority: ADMIN &gt; VIP &gt; REGULAR.
     */
    private Map<Long, UserPriority> collectIdsWithEffectiveLevel() {
        Map<Long, UserPriority> map = new HashMap<>();
        var access = telegramProperties.getAccess();

        Set<Long> adminIds = safeIds(access.getAdmin());
        Set<Long> vipIds = safeIds(access.getVip());
        Set<Long> regularIds = safeIds(access.getRegular());

        for (Long id : adminIds) {
            if (id != null) {
                map.put(id, UserPriority.ADMIN);
            }
        }
        for (Long id : vipIds) {
            if (id != null) {
                map.putIfAbsent(id, UserPriority.VIP);
            }
        }
        for (Long id : regularIds) {
            if (id != null) {
                map.putIfAbsent(id, UserPriority.REGULAR);
            }
        }
        return map;
    }

    private static Set<Long> safeIds(TelegramProperties.AccessConfig.LevelConfig level) {
        if (level == null || level.getIds() == null) {
            return Set.of();
        }
        return level.getIds().stream()
                .filter(id -> id != null)
                .collect(Collectors.toSet());
    }
}
