package io.github.ngirchev.opendaimon.telegram.command.handler.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import io.github.ngirchev.opendaimon.common.command.ICommand;
import io.github.ngirchev.opendaimon.common.model.AssistantRole;
import io.github.ngirchev.opendaimon.telegram.TelegramBot;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommand;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommandType;
import io.github.ngirchev.opendaimon.common.service.MessageLocalizationService;
import io.github.ngirchev.opendaimon.telegram.command.handler.AbstractTelegramCommandHandlerWithResponseSend;
import io.github.ngirchev.opendaimon.telegram.command.handler.TelegramCommandHandlerException;
import io.github.ngirchev.opendaimon.telegram.model.TelegramUser;
import io.github.ngirchev.opendaimon.telegram.service.TelegramUserService;
import io.github.ngirchev.opendaimon.telegram.service.TypingIndicatorService;
import io.github.ngirchev.opendaimon.common.config.CoreCommonProperties;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Slf4j
public class RoleTelegramCommandHandler extends AbstractTelegramCommandHandlerWithResponseSend {

    private static final String CALLBACK_PREFIX = "ROLE_";
    private static final String CALLBACK_CUSTOM = CALLBACK_PREFIX + "CUSTOM";
    private static final String CALLBACK_CANCEL = CALLBACK_PREFIX + "CANCEL";

    private final TelegramUserService telegramUserService;
    private final CoreCommonProperties coreCommonProperties;

    public RoleTelegramCommandHandler(ObjectProvider<TelegramBot> telegramBotProvider,
                                      TypingIndicatorService typingIndicatorService,
                                      MessageLocalizationService messageLocalizationService,
                                      TelegramUserService telegramUserService,
                                      CoreCommonProperties coreCommonProperties) {
        super(telegramBotProvider, typingIndicatorService, messageLocalizationService);
        this.telegramUserService = telegramUserService;
        this.coreCommonProperties = coreCommonProperties;
    }

    @Override
    public String getSupportedCommandText(String languageCode) {
        return messageLocalizationService.getMessage("telegram.command.role.desc", languageCode);
    }

    @Override
    protected boolean shouldShowTypingIndicator(TelegramCommand command) {
        return false;
    }

    @Override
    public boolean canHandle(ICommand<TelegramCommandType> command) {
        if (!(command instanceof TelegramCommand telegramCommand)) {
            return false;
        }
        if (telegramCommand.update().hasCallbackQuery()) {
            CallbackQuery cq = telegramCommand.update().getCallbackQuery();
            return cq.getData() != null && cq.getData().startsWith(CALLBACK_PREFIX);
        }
        var commandType = command.commandType();
        return commandType != null
                && commandType.command() != null
                && commandType.command().equals(TelegramCommand.ROLE);
    }

    @Override
    public String handleInner(TelegramCommand command) {
        if (command.update().hasCallbackQuery()) {
            handleCallbackQuery(command);
            return null;
        }
        Message message = command.update().getMessage();
        if (message == null) {
            throw new TelegramCommandHandlerException(command.telegramId(), "Message is required for role command");
        }
        TelegramUser user = telegramUserService.getOrCreateUser(message.getFrom());
        String userText = command.userText() != null ? command.userText().trim() : null;
        
        String lang = command.languageCode();
        if (userText == null || userText.isEmpty()) {
            // Show current role
            AssistantRole currentRole = telegramUserService.getOrCreateAssistantRole(
                    user,
                    messageLocalizationService.getMessage(coreCommonProperties.getAssistantRole(), lang)
            );

            // Load role data inside transaction to avoid LazyInitializationException
            Integer roleVersion = currentRole.getVersion();
            String roleContent = currentRole.getContent();

            // Send first message with header
            String roleHeader = messageLocalizationService.getMessage("telegram.role.header", lang, roleVersion);
            sendMessage(command.telegramId(), roleHeader);

            // Send second message with role content
            sendMessage(command.telegramId(), roleContent);

            // Send third message with role selection menu
            sendRoleMenu(command.telegramId(), lang);

            // Return null as messages already sent
            return null;
        } else {
            // Update role
            telegramUserService.updateAssistantRole(message.getFrom(), userText);
            telegramBotProvider.getObject().clearStatus(message.getFrom().getId());

            // Send confirmation replying to user message
            Integer replyToMessageId = message.getMessageId();
            sendMessage(command.telegramId(), messageLocalizationService.getMessage("telegram.role.updated", lang), replyToMessageId);
            return null;
        }
    }

    private void handleCallbackQuery(TelegramCommand command) {
        CallbackQuery cq = command.update().getCallbackQuery();
        String callbackData = cq.getData();
        if (callbackData == null || !callbackData.startsWith(CALLBACK_PREFIX)) {
            throw new TelegramCommandHandlerException(command.telegramId(), "Invalid callback data");
        }
        if (CALLBACK_CANCEL.equals(callbackData)) {
            ackCallback(cq.getId(), "");
            deleteMenuMessage(command.telegramId(), cq);
            return;
        }

        String lang = command.languageCode();
        String roleKey = callbackData.substring(CALLBACK_PREFIX.length());
        if ("CUSTOM".equals(roleKey)) {
            TelegramUser user = telegramUserService.getOrCreateUser(cq.getFrom());
            telegramUserService.updateUserSession(user, TelegramCommand.ROLE);
            ackCallback(cq.getId(), messageLocalizationService.getMessage("telegram.role.enter.ack", lang));
            sendMessage(command.telegramId(), messageLocalizationService.getMessage("telegram.role.enter.text", lang));
            deleteMenuMessage(command.telegramId(), cq);
            return;
        }

        Optional<RolePreset> preset = getRolePresets(lang).stream()
                .filter(role -> role.key().equals(roleKey))
                .findFirst();
        if (preset.isEmpty()) {
            ackCallback(cq.getId(), messageLocalizationService.getMessage("telegram.role.ack.not.found", lang));
            sendErrorMessage(command.telegramId(), messageLocalizationService.getMessage("telegram.role.not.found", lang));
            return;
        }

        telegramUserService.updateAssistantRole(cq.getFrom(), preset.get().content());
        telegramBotProvider.getObject().clearStatus(cq.getFrom().getId());
        ackCallback(cq.getId(), messageLocalizationService.getMessage("telegram.role.ack.updated", lang));
        deleteMenuMessage(command.telegramId(), cq);
    }

    private void sendRoleMenu(Long chatId, String lang) {
        try {
            List<List<InlineKeyboardButton>> keyboard = getRolePresets(lang).stream()
                    .map(role -> {
                        InlineKeyboardButton button = new InlineKeyboardButton(role.title());
                        button.setCallbackData(CALLBACK_PREFIX + role.key());
                        return List.of(button);
                    })
                    .toList();

            InlineKeyboardButton customButton = new InlineKeyboardButton(
                    messageLocalizationService.getMessage("telegram.role.custom.button", lang));
            customButton.setCallbackData(CALLBACK_CUSTOM);

            String closeLabel = messageLocalizationService.getMessage("telegram.role.close", lang);

            keyboard = new java.util.ArrayList<>(keyboard);
            keyboard.add(List.of(customButton));
            keyboard.add(List.of(button(closeLabel, CALLBACK_CANCEL)));

            InlineKeyboardMarkup markup = new InlineKeyboardMarkup(keyboard);
            SendMessage msg = new SendMessage(chatId.toString(),
                    messageLocalizationService.getMessage("telegram.role.menu", lang));
            msg.setReplyMarkup(markup);
            telegramBotProvider.getObject().execute(msg);
        } catch (Exception e) {
            throw new TelegramCommandHandlerException("Failed to send role menu", e);
        }
    }

    private InlineKeyboardButton button(String label, String callbackData) {
        InlineKeyboardButton button = new InlineKeyboardButton(label);
        button.setCallbackData(callbackData);
        return button;
    }

    private void deleteMenuMessage(Long chatId, CallbackQuery callbackQuery) {
        if (callbackQuery.getMessage() instanceof Message menuMessage) {
            try {
                telegramBotProvider.getObject().execute(
                        new DeleteMessage(chatId.toString(), menuMessage.getMessageId()));
            } catch (Exception e) {
                log.warn("Failed to delete role menu message: {}", e.getMessage());
            }
        }
    }

    private void ackCallback(String callbackQueryId, String text) {
        try {
            AnswerCallbackQuery ack = new AnswerCallbackQuery();
            ack.setCallbackQueryId(callbackQueryId);
            ack.setText(text);
            ack.setShowAlert(false);
            telegramBotProvider.getObject().execute(ack);
        } catch (Exception e) {
            throw new TelegramCommandHandlerException("Failed to ack callback", e);
        }
    }

    private List<RolePreset> getRolePresets(String lang) {
        return List.of(
                new RolePreset("DEFAULT", messageLocalizationService.getMessage("telegram.role.preset.default", lang),
                        messageLocalizationService.getMessage(coreCommonProperties.getAssistantRole(), lang)),
                new RolePreset("COACH", messageLocalizationService.getMessage("telegram.role.preset.coach", lang),
                        messageLocalizationService.getMessage("role.content.coach", lang)),
                new RolePreset("EDITOR", messageLocalizationService.getMessage("telegram.role.preset.editor", lang),
                        messageLocalizationService.getMessage("role.content.editor", lang)),
                new RolePreset("DEV", messageLocalizationService.getMessage("telegram.role.preset.developer", lang),
                        messageLocalizationService.getMessage("role.content.developer", lang))
        );
    }

    private record RolePreset(String key, String title, String content) {
        private RolePreset {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(content, "content");
        }
    }
}
