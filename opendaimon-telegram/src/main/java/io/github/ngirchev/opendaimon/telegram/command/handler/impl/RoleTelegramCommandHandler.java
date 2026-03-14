package io.github.ngirchev.opendaimon.telegram.command.handler.impl;

import org.springframework.beans.factory.ObjectProvider;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
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

public class RoleTelegramCommandHandler extends AbstractTelegramCommandHandlerWithResponseSend {

    private static final String CALLBACK_PREFIX = "ROLE_";
    private static final String CALLBACK_CUSTOM = CALLBACK_PREFIX + "CUSTOM";

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
        
        if (userText == null || userText.isEmpty()) {
            // Show current role
            AssistantRole currentRole = telegramUserService.getOrCreateAssistantRole(
                    user, 
                    coreCommonProperties.getAssistantRole()
            );
            
            // Load role data inside transaction to avoid LazyInitializationException
            Integer roleVersion = currentRole.getVersion();
            String roleContent = currentRole.getContent();
            
            // Send first message with header
            String roleHeader = String.format(
                    "📋 Current assistant role (version %d):", 
                    roleVersion
            );
            sendMessage(command.telegramId(), roleHeader);
            
            // Send second message with role content
            sendMessage(command.telegramId(), roleContent);
            
            // Send third message with role selection menu
            sendRoleMenu(command.telegramId());
            
            // Return null as messages already sent
            return null;
        } else {
            // Update role
            telegramUserService.updateAssistantRole(message.getFrom(), userText);
            telegramBotProvider.getObject().clearStatus(message.getFrom().getId());
            
            // Send confirmation replying to user message
            Integer replyToMessageId = message != null ? message.getMessageId() : null;
            sendMessage(command.telegramId(), "✅ Assistant role updated successfully!", replyToMessageId);
            return null;
        }
    }

    private void handleCallbackQuery(TelegramCommand command) {
        CallbackQuery cq = command.update().getCallbackQuery();
        String callbackData = cq.getData();
        if (callbackData == null || !callbackData.startsWith(CALLBACK_PREFIX)) {
            throw new TelegramCommandHandlerException(command.telegramId(), "Invalid callback data");
        }

        String roleKey = callbackData.substring(CALLBACK_PREFIX.length());
        if ("CUSTOM".equals(roleKey)) {
            TelegramUser user = telegramUserService.getOrCreateUser(cq.getFrom());
            telegramUserService.updateUserSession(user, TelegramCommand.ROLE);
            ackCallback(cq.getId(), "✏️ Enter new role");
            sendMessage(command.telegramId(), "✏️ Enter the new role as text.");
            return;
        }

        Optional<RolePreset> preset = getRolePresets().stream()
                .filter(role -> role.key().equals(roleKey))
                .findFirst();
        if (preset.isEmpty()) {
            ackCallback(cq.getId(), "❌ Role not found");
            sendErrorMessage(command.telegramId(), "Unknown role");
            return;
        }

        // Update role (TelegramUserService will add locale requirement)
        telegramUserService.updateAssistantRole(cq.getFrom(), preset.get().content());
        telegramBotProvider.getObject().clearStatus(cq.getFrom().getId());
        ackCallback(cq.getId(), "✅ Role updated");
        sendMessage(command.telegramId(), "✅ Role changed: " + preset.get().title());
    }

    private void sendRoleMenu(Long chatId) {
        try {
            List<List<InlineKeyboardButton>> keyboard = getRolePresets().stream()
                    .map(role -> {
                        InlineKeyboardButton button = new InlineKeyboardButton(role.title());
                        button.setCallbackData(CALLBACK_PREFIX + role.key());
                        return List.of(button);
                    })
                    .toList();

            InlineKeyboardButton customButton = new InlineKeyboardButton("✏️ Write custom role");
            customButton.setCallbackData(CALLBACK_CUSTOM);

            keyboard = new java.util.ArrayList<>(keyboard);
            keyboard.add(List.of(customButton));

            InlineKeyboardMarkup markup = new InlineKeyboardMarkup(keyboard);
            SendMessage msg = new SendMessage(chatId.toString(), "🎭 Choose a role from the list or set your own:");
            msg.setReplyMarkup(markup);
            telegramBotProvider.getObject().execute(msg);
        } catch (Exception e) {
            throw new TelegramCommandHandlerException("Failed to send role menu", e);
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

    private List<RolePreset> getRolePresets() {
        return List.of(
                new RolePreset("DEFAULT", "🌟 Default", coreCommonProperties.getAssistantRole()),
                new RolePreset("COACH", "🧭 Coach", "You are a development and goals coach. You help clarify requests, "
                        + "ask questions, suggest steps and support motivation. Keep answers short and structured."),
                new RolePreset("EDITOR", "✍️ Editor", "You are a text editor. You fix errors, "
                        + "improve style and suggest better wording while preserving meaning."),
                new RolePreset("DEV", "💻 Developer", "You are a senior Java developer and architect. "
                        + "You suggest solutions, code and explanations, considering Spring Boot, clean architecture and best practices.")
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
