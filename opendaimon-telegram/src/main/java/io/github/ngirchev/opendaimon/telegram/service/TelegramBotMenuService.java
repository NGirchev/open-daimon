package io.github.ngirchev.opendaimon.telegram.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import io.github.ngirchev.opendaimon.common.model.User;
import io.github.ngirchev.opendaimon.telegram.TelegramBot;
import io.github.ngirchev.opendaimon.telegram.command.handler.TelegramSupportedCommandProvider;
import io.github.ngirchev.opendaimon.telegram.model.TelegramGroup;
import io.github.ngirchev.opendaimon.telegram.model.TelegramUser;

import io.github.ngirchev.opendaimon.common.SupportedLanguages;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Service for setting up Telegram bot command menu.
 */
@Slf4j
@RequiredArgsConstructor
public class TelegramBotMenuService {

    private final ObjectProvider<TelegramBot> telegramBotProvider;
    private final ObjectProvider<TelegramSupportedCommandProvider> commandHandlersProvider;
    private final ObjectProvider<ChatSettingsService> chatSettingsServiceProvider;

    /**
     * Cached hash of the current enabled-commands set. Computed lazily on first access
     * because command handler beans are registered as part of application context startup
     * and may not be fully available at this service's construction time.
     * <p>
     * Double-checked locking with a {@code volatile} reference; value is set once per JVM.
     */
    private volatile String currentMenuVersionHash;

    /**
     * Sets bot command menu for each supported language. Telegram shows the menu in the user's app language.
     */
    public void setupBotMenu() {
        try {
            TelegramBot bot = telegramBotProvider.getObject();
            for (String lang : SupportedLanguages.SUPPORTED_LANGUAGES) {
                List<BotCommand> commands = buildCommandsList(lang);
                if (commands.isEmpty()) {
                    log.warn("No commands found for language {}", lang);
                    continue;
                }
                log.info("Bot menu commands for [{}]: {}", lang,
                        commands.stream().map(c -> c.getCommand() + " - " + c.getDescription())
                                .toList());
                bot.setMyCommands(commands, lang);
            }
            log.info("Bot menu configured for languages: {}", SupportedLanguages.SUPPORTED_LANGUAGES);
        } catch (TelegramApiException e) {
            log.error("Failed to set up bot menu", e);
            throw new RuntimeException("Failed to set up bot menu", e);
        }
    }

    /**
     * Sets bot command menu for a specific user chat, overriding the global language-based menu.
     */
    public void setupBotMenuForUser(Long chatId, String languageCode) {
        try {
            TelegramBot bot = telegramBotProvider.getObject();
            List<BotCommand> commands = buildCommandsList(languageCode);
            if (!commands.isEmpty()) {
                bot.setMyCommands(commands, chatId);
            }
        } catch (TelegramApiException e) {
            log.error("Failed to set bot menu for chat {}", chatId, e);
        }
    }

    /**
     * Returns a stable SHA-256 hex digest of the currently enabled command set, computed
     * over every supported language. Used as a per-user marker to detect that a chat-scoped
     * menu (set via {@code BotCommandScopeChat}) is stale after a deployment adds or removes
     * commands.
     * <p>
     * Computed lazily on first access and cached; never recomputed afterwards for the lifetime
     * of this bean.
     *
     * @return 64-char lowercase hex string
     */
    public String getCurrentMenuVersionHash() {
        String cached = currentMenuVersionHash;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (currentMenuVersionHash == null) {
                currentMenuVersionHash = computeCurrentMenuVersionHash();
            }
            return currentMenuVersionHash;
        }
    }

    /**
     * Deterministic hash of the command set across every supported language. Languages are
     * iterated in sorted order; within each language, the handler-provided command texts are
     * sorted alphabetically. Each entry is encoded as {@code "<lang>:<commandText>\n"}.
     * <p>
     * Package-private for testing.
     */
    String computeCurrentMenuVersionHash() {
        StringBuilder payload = new StringBuilder();
        TreeSet<String> sortedLanguages = new TreeSet<>(SupportedLanguages.SUPPORTED_LANGUAGES);
        for (String lang : sortedLanguages) {
            TreeSet<String> commandTexts = new TreeSet<>();
            commandHandlersProvider.orderedStream()
                    .map(h -> h.getSupportedCommandText(lang))
                    .filter(Objects::nonNull)
                    .forEach(commandTexts::add);
            for (String commandText : commandTexts) {
                payload.append(lang).append(':').append(commandText).append('\n');
            }
        }
        return sha256Hex(payload.toString());
    }

    /**
     * Reconciles the chat-scoped command menu for the given settings owner if it differs
     * from the current menu version. The owner is polymorphic: a {@link TelegramGroup} for
     * group chats and a {@link TelegramUser} for private chats. {@code chatId} is the
     * Telegram {@code chat_id} to which the menu is pushed via {@code BotCommandScopeChat}.
     * <p>
     * No-op when the owner has no language code (they rely on the Default-scope menu refreshed
     * at startup) or when the stored hash already matches.
     * <p>
     * <b>Side effects:</b> on refresh this method writes {@code currentHash} into the owner's
     * {@code menuVersionHash} field in memory AND persists it via the repository (by subtype).
     * Telegram API failures are swallowed internally (already handled in
     * {@code setupBotMenuForUser}) and surfaced only via logs — this method never propagates
     * a checked exception to callers.
     *
     * @param owner  settings owner whose chat menu may need refreshing
     * @param chatId Telegram chat id (private-chat userId for users, negative group id for groups)
     * @return {@code true} if the menu was refreshed and the owner's hash was updated
     */
    public boolean reconcileMenuIfStale(User owner, Long chatId) {
        if (owner == null || chatId == null) {
            return false;
        }
        String languageCode = owner.getLanguageCode();
        if (languageCode == null) {
            // Owner is still on Default-scope menu; startup refresh already covers them.
            return false;
        }
        String currentHash = getCurrentMenuVersionHash();
        String storedHash = menuVersionHashOf(owner);
        if (storedHash != null && storedHash.equals(currentHash)) {
            return false;
        }
        setupBotMenuForUser(chatId, languageCode);
        setMenuVersionHashOn(owner, currentHash);
        ChatSettingsService chatSettingsService = chatSettingsServiceProvider != null
                ? chatSettingsServiceProvider.getIfAvailable() : null;
        if (chatSettingsService != null) {
            chatSettingsService.updateMenuVersionHash(owner, currentHash);
        }
        log.info("Reconciled menu for chatId={} ownerType={}: versionHash updated from {} to {}",
                chatId, owner.getClass().getSimpleName(), storedHash, currentHash);
        return true;
    }

    private static String menuVersionHashOf(User owner) {
        if (owner instanceof TelegramGroup group) return group.getMenuVersionHash();
        if (owner instanceof TelegramUser user) return user.getMenuVersionHash();
        return null;
    }

    private static void setMenuVersionHashOn(User owner, String hash) {
        if (owner instanceof TelegramGroup group) group.setMenuVersionHash(hash);
        else if (owner instanceof TelegramUser user) user.setMenuVersionHash(hash);
    }

    /**
     * Builds list of commands from handlers for the given language.
     */
    private List<BotCommand> buildCommandsList(String languageCode) {
        List<BotCommand> commands = new ArrayList<>();
        commandHandlersProvider.orderedStream()
                .map(h -> h.getSupportedCommandText(languageCode))
                .filter(Objects::nonNull)
                .forEach(commandText -> {
                    BotCommand command = parseCommandText(commandText);
                    if (command != null) {
                        commands.add(command);
                    }
                });
        return commands;
    }

    /**
     * Parses string of form "/command - description" into BotCommand.
     * @param commandText string with command and description
     * @return BotCommand or null if parsing failed
     */
    private BotCommand parseCommandText(String commandText) {
        if (commandText == null || commandText.trim().isEmpty()) {
            return null;
        }

        String trimmed = commandText.trim();
        int dashIndex = trimmed.indexOf(" - ");

        if (dashIndex == -1) {
            // If no description, use command as is
            String command = trimmed.startsWith("/") ? trimmed : "/" + trimmed;
            return new BotCommand(command, "");
        }

        String command = trimmed.substring(0, dashIndex).trim();
        String description = trimmed.substring(dashIndex + 3).trim();

        // Ensure command starts with /
        if (!command.startsWith("/")) {
            command = "/" + command;
        }

        // Limit description length (Telegram max 256 chars)
        if (description.length() > 256) {
            description = description.substring(0, 253) + "...";
        }

        return new BotCommand(command, description);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a MUST-have in every JVM; this branch is effectively unreachable.
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
