package io.github.ngirchev.aibot.telegram.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import io.github.ngirchev.aibot.telegram.TelegramBot;
import io.github.ngirchev.aibot.telegram.command.handler.TelegramSupportedCommandProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Service for setting up Telegram bot command menu.
 */
@Slf4j
@RequiredArgsConstructor
public class TelegramBotMenuService {

    private final ObjectProvider<TelegramBot> telegramBotProvider;
    private final ObjectProvider<TelegramSupportedCommandProvider> commandHandlersProvider;

    private static final Set<String> SUPPORTED_MENU_LANGUAGES = Set.of("ru", "en");

    /**
     * Sets bot command menu for each supported language (ru, en). Telegram shows the menu in the user's app language.
     */
    public void setupBotMenu() {
        try {
            TelegramBot bot = telegramBotProvider.getObject();
            for (String lang : SUPPORTED_MENU_LANGUAGES) {
                List<BotCommand> commands = buildCommandsList(lang);
                if (commands.isEmpty()) {
                    log.warn("No commands found for language {}", lang);
                    continue;
                }
                bot.setMyCommands(commands, lang);
            }
            log.info("Bot menu configured for languages: {}", SUPPORTED_MENU_LANGUAGES);
        } catch (TelegramApiException e) {
            log.error("Failed to set up bot menu", e);
            throw new RuntimeException("Failed to set up bot menu", e);
        }
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
}

