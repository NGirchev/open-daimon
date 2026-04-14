package io.github.ngirchev.opendaimon.telegram.command.handler.impl;

import io.github.ngirchev.opendaimon.bulkhead.model.UserPriority;
import io.github.ngirchev.opendaimon.bulkhead.service.IUserPriorityService;
import io.github.ngirchev.opendaimon.common.ai.ModelCapabilities;
import io.github.ngirchev.opendaimon.common.ai.command.AICommand;
import io.github.ngirchev.opendaimon.common.ai.command.ModelListAICommand;
import io.github.ngirchev.opendaimon.common.ai.model.ModelInfo;
import io.github.ngirchev.opendaimon.common.ai.response.ModelListAIResponse;
import io.github.ngirchev.opendaimon.common.command.ICommand;
import io.github.ngirchev.opendaimon.common.model.ConversationThread;
import io.github.ngirchev.opendaimon.common.model.ThreadScopeKind;
import io.github.ngirchev.opendaimon.common.service.ConversationThreadService;
import io.github.ngirchev.opendaimon.common.service.AIGateway;
import io.github.ngirchev.opendaimon.common.service.AIGatewayRegistry;
import io.github.ngirchev.opendaimon.common.service.MessageLocalizationService;
import io.github.ngirchev.opendaimon.telegram.TelegramBot;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommand;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommandType;
import io.github.ngirchev.opendaimon.telegram.command.handler.AbstractTelegramCommandHandlerWithResponseSend;
import io.github.ngirchev.opendaimon.telegram.command.handler.TelegramCommandHandlerException;
import io.github.ngirchev.opendaimon.telegram.model.TelegramUser;
import io.github.ngirchev.opendaimon.telegram.service.PersistentKeyboardService;
import io.github.ngirchev.opendaimon.telegram.service.TelegramUserService;
import io.github.ngirchev.opendaimon.telegram.service.TypingIndicatorService;
import io.github.ngirchev.opendaimon.telegram.service.UserModelPreferenceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
public class ModelTelegramCommandHandler extends AbstractTelegramCommandHandlerWithResponseSend {

    private static final String CALLBACK_PREFIX = "MODEL_";
    private static final String CALLBACK_AUTO = CALLBACK_PREFIX + "AUTO";
    private static final String CALLBACK_CANCEL = CALLBACK_PREFIX + "CANCEL";

    private static final Set<ModelCapabilities> DISPLAY_CAPS = Set.of(
            ModelCapabilities.VISION,
            ModelCapabilities.WEB,
            ModelCapabilities.TOOL_CALLING,
            ModelCapabilities.SUMMARIZATION,
            ModelCapabilities.FREE
    );

    private final TelegramUserService telegramUserService;
    private final UserModelPreferenceService userModelPreferenceService;
    private final AIGatewayRegistry aiGatewayRegistry;
    private final IUserPriorityService userPriorityService;
    private final PersistentKeyboardService persistentKeyboardService;
    private final ConversationThreadService conversationThreadService;

    public ModelTelegramCommandHandler(ObjectProvider<TelegramBot> telegramBotProvider,
                                       TypingIndicatorService typingIndicatorService,
                                       MessageLocalizationService messageLocalizationService,
                                       TelegramUserService telegramUserService,
                                       UserModelPreferenceService userModelPreferenceService,
                                       AIGatewayRegistry aiGatewayRegistry,
                                       IUserPriorityService userPriorityService,
                                       PersistentKeyboardService persistentKeyboardService,
                                       ConversationThreadService conversationThreadService) {
        super(telegramBotProvider, typingIndicatorService, messageLocalizationService);
        this.telegramUserService = telegramUserService;
        this.userModelPreferenceService = userModelPreferenceService;
        this.aiGatewayRegistry = aiGatewayRegistry;
        this.userPriorityService = userPriorityService;
        this.persistentKeyboardService = persistentKeyboardService;
        this.conversationThreadService = conversationThreadService;
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
        if (commandType == null || commandType.command() == null) {
            return false;
        }
        String cmd = commandType.command();
        return cmd.equals(TelegramCommand.MODEL)
                || (telegramCommand.userText() != null && (
                        telegramCommand.userText().startsWith(TelegramCommand.MODEL_KEYBOARD_PREFIX)
                        || telegramCommand.userText().startsWith(TelegramCommand.CONTEXT_KEYBOARD_PREFIX)));
    }

    @Override
    public String handleInner(TelegramCommand command) {
        if (command.update().hasCallbackQuery()) {
            handleCallbackQuery(command);
            return null;
        }
        Message message = command.update().getMessage();
        if (message == null) {
            throw new TelegramCommandHandlerException(command.telegramId(), "Message is required for model command");
        }
        TelegramUser user = telegramUserService.getOrCreateUser(message.getFrom());
        ConversationThread thread = conversationThreadService.findCurrentThread(
                ThreadScopeKind.TELEGRAM_CHAT, command.telegramId()).orElse(null);
        persistentKeyboardService.sendKeyboard(command.telegramId(), user.getId(), thread);
        sendModelMenu(command.telegramId(), user);
        return null;
    }

    private void sendModelMenu(Long chatId, TelegramUser user) {
        try {
            UserPriority userPriority = userPriorityService.getUserPriority(user.getId());
            Map<String, String> metadata = new HashMap<>();
            if (userPriority != null) {
                metadata.put(AICommand.USER_PRIORITY_FIELD, userPriority.name());
            }
            ModelListAICommand cmd = new ModelListAICommand(metadata);

            List<AIGateway> gateways = aiGatewayRegistry.getSupportedAiGateways(cmd);
            if (gateways.isEmpty()) {
                sendMessage(chatId, messageLocalizationService.getMessage(
                        "telegram.model.unavailable", user.getLanguageCode()));
                return;
            }
            ModelListAIResponse response = (ModelListAIResponse) gateways.getFirst().generateResponse(cmd);

            List<List<InlineKeyboardButton>> keyboard = new ArrayList<>();

            // Auto button first
            String lang = user.getLanguageCode();
            String autoLabel = messageLocalizationService.getMessage("telegram.model.auto", lang);
            InlineKeyboardButton autoBtn = new InlineKeyboardButton(autoLabel);
            autoBtn.setCallbackData(CALLBACK_AUTO);
            keyboard.add(List.of(autoBtn));

            String selectText = messageLocalizationService.getMessage("telegram.model.select", lang);
            StringBuilder text = new StringBuilder(selectText).append("\n\n");
            text.append(messageLocalizationService.getMessage("telegram.model.auto.hint", lang, autoLabel)).append("\n\n");

            // Model buttons — use numeric index as callback data to stay within Telegram's 64-byte limit
            List<ModelInfo> models = response.models();
            for (int i = 0; i < models.size(); i++) {
                ModelInfo model = models.get(i);
                String caps = buildCapabilityLabel(model.capabilities(), lang);
                String providerPrefix = model.provider() != null && !model.provider().isEmpty()
                        ? "[" + model.provider() + "] " : "";
                text.append(i + 1).append(". ").append(providerPrefix).append(model.name());
                if (!caps.isEmpty()) text.append(" — ").append(caps);
                text.append("\n");

                String btnLabel = providerPrefix + model.name();
                InlineKeyboardButton btn = new InlineKeyboardButton(btnLabel);
                btn.setCallbackData(CALLBACK_PREFIX + i);
                keyboard.add(List.of(btn));
            }

            // Cancel button last
            String cancelLabel = messageLocalizationService.getMessage("telegram.model.cancel", lang);
            InlineKeyboardButton cancelBtn = new InlineKeyboardButton(cancelLabel);
            cancelBtn.setCallbackData(CALLBACK_CANCEL);
            keyboard.add(List.of(cancelBtn));

            InlineKeyboardMarkup markup = new InlineKeyboardMarkup(keyboard);
            SendMessage msg = new SendMessage(chatId.toString(), text.toString());
            msg.setReplyMarkup(markup);
            telegramBotProvider.getObject().execute(msg);
        } catch (Exception e) {
            throw new TelegramCommandHandlerException("Failed to send model menu", e);
        }
    }

    private String buildCapabilityLabel(Set<ModelCapabilities> capabilities, String lang) {
        return capabilities.stream()
                .filter(DISPLAY_CAPS::contains)
                .map(cap -> capabilityToLabel(cap, lang))
                .collect(Collectors.joining(", "));
    }

    private String capabilityToLabel(ModelCapabilities cap, String lang) {
        return switch (cap) {
            case VISION -> messageLocalizationService.getMessage("telegram.model.cap.vision", lang);
            case WEB -> messageLocalizationService.getMessage("telegram.model.cap.web", lang);
            case TOOL_CALLING -> messageLocalizationService.getMessage("telegram.model.cap.tools", lang);
            case SUMMARIZATION -> messageLocalizationService.getMessage("telegram.model.cap.summary", lang);
            case FREE -> messageLocalizationService.getMessage("telegram.model.cap.free", lang);
            default -> cap.name();
        };
    }

    private void handleCallbackQuery(TelegramCommand command) {
        CallbackQuery cq = command.update().getCallbackQuery();
        String callbackData = cq.getData();
        if (callbackData == null || !callbackData.startsWith(CALLBACK_PREFIX)) {
            throw new TelegramCommandHandlerException(command.telegramId(), "Invalid callback data");
        }

        TelegramUser user = telegramUserService.getOrCreateUser(cq.getFrom());
        Long userId = user.getId();

        if (CALLBACK_CANCEL.equals(callbackData)) {
            ackCallback(cq.getId(), "");
            deleteMenuMessage(command.telegramId(), cq);
            return;
        }

        if (CALLBACK_AUTO.equals(callbackData)) {
            userModelPreferenceService.clearPreference(userId);
            ackCallback(cq.getId(), messageLocalizationService.getMessage(
                    "telegram.model.ack.auto", user.getLanguageCode()));
        } else {
            String modelName = resolveModelName(callbackData, user);
            userModelPreferenceService.setPreferredModel(userId, modelName);
            ackCallback(cq.getId(), "✅ " + modelName);
        }
        deleteMenuMessage(command.telegramId(), cq);
        ConversationThread thread = conversationThreadService.findCurrentThread(
                ThreadScopeKind.TELEGRAM_CHAT, command.telegramId()).orElse(null);
        persistentKeyboardService.sendKeyboard(command.telegramId(), userId, thread);
    }

    private String resolveModelName(String callbackData, TelegramUser user) {
        String raw = callbackData.substring(CALLBACK_PREFIX.length());
        try {
            int idx = Integer.parseInt(raw);
            UserPriority userPriority = userPriorityService.getUserPriority(user.getId());
            Map<String, String> metadata = new HashMap<>();
            if (userPriority != null) {
                metadata.put(AICommand.USER_PRIORITY_FIELD, userPriority.name());
            }
            ModelListAICommand cmd = new ModelListAICommand(metadata);
            List<AIGateway> gateways = aiGatewayRegistry.getSupportedAiGateways(cmd);
            if (!gateways.isEmpty()) {
                ModelListAIResponse resp = (ModelListAIResponse) gateways.getFirst().generateResponse(cmd);
                if (idx >= 0 && idx < resp.models().size()) {
                    return resp.models().get(idx).name();
                }
            }
        } catch (NumberFormatException e) {
            log.warn("Unrecognised model callback data '{}', treating as model name", raw);
        }
        return raw;
    }

    private void deleteMenuMessage(Long chatId, CallbackQuery callbackQuery) {
        if (callbackQuery.getMessage() instanceof Message menuMessage) {
            try {
                telegramBotProvider.getObject().execute(
                        new DeleteMessage(chatId.toString(), menuMessage.getMessageId()));
            } catch (Exception e) {
                log.warn("Failed to delete model menu message: {}", e.getMessage());
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

    @Override
    public String getSupportedCommandText(String languageCode) {
        return messageLocalizationService.getMessage("telegram.command.model.desc", languageCode);
    }
}
