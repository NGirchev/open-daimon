package io.github.ngirchev.opendaimon.telegram.command.handler.impl;

import io.github.ngirchev.opendaimon.bulkhead.model.UserPriority;
import io.github.ngirchev.opendaimon.bulkhead.service.IUserPriorityService;
import io.github.ngirchev.opendaimon.common.ai.ModelCapabilities;
import io.github.ngirchev.opendaimon.common.ai.command.AICommand;
import io.github.ngirchev.opendaimon.common.ai.command.ModelListAICommand;
import io.github.ngirchev.opendaimon.common.ai.model.ModelInfo;
import io.github.ngirchev.opendaimon.common.ai.response.ModelListAIResponse;
import io.github.ngirchev.opendaimon.common.command.ICommand;
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

    public ModelTelegramCommandHandler(ObjectProvider<TelegramBot> telegramBotProvider,
                                       TypingIndicatorService typingIndicatorService,
                                       MessageLocalizationService messageLocalizationService,
                                       TelegramUserService telegramUserService,
                                       UserModelPreferenceService userModelPreferenceService,
                                       AIGatewayRegistry aiGatewayRegistry,
                                       IUserPriorityService userPriorityService,
                                       PersistentKeyboardService persistentKeyboardService) {
        super(telegramBotProvider, typingIndicatorService, messageLocalizationService);
        this.telegramUserService = telegramUserService;
        this.userModelPreferenceService = userModelPreferenceService;
        this.aiGatewayRegistry = aiGatewayRegistry;
        this.userPriorityService = userPriorityService;
        this.persistentKeyboardService = persistentKeyboardService;
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
        userModelPreferenceService.getPreferredModel(user.getId())
                .map(m -> TelegramCommand.MODEL_KEYBOARD_PREFIX + m);
        persistentKeyboardService.sendKeyboard(command.telegramId(), user.getId(), null);
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
                sendMessage(chatId, "Model list is not available.");
                return;
            }
            ModelListAIResponse response = (ModelListAIResponse) gateways.getFirst().generateResponse(cmd);

            List<List<InlineKeyboardButton>> keyboard = new ArrayList<>();

            // Auto button first
            InlineKeyboardButton autoBtn = new InlineKeyboardButton(
                    messageLocalizationService.getMessage("telegram.model.auto", user.getLanguageCode()));
            autoBtn.setCallbackData(CALLBACK_AUTO);
            keyboard.add(List.of(autoBtn));

            // Model buttons — use numeric index as callback data to stay within Telegram's 64-byte limit
            List<ModelInfo> models = response.models();
            for (int i = 0; i < models.size(); i++) {
                ModelInfo model = models.get(i);
                String capLabels = buildCapabilityLabel(model.capabilities());
                String label = model.name() + (capLabels.isEmpty() ? "" : " [" + capLabels + "]");
                InlineKeyboardButton btn = new InlineKeyboardButton(label);
                btn.setCallbackData(CALLBACK_PREFIX + i);
                keyboard.add(List.of(btn));
            }

            InlineKeyboardMarkup markup = new InlineKeyboardMarkup(keyboard);
            String selectText = messageLocalizationService.getMessage("telegram.model.select", user.getLanguageCode());
            SendMessage msg = new SendMessage(chatId.toString(), selectText);
            msg.setReplyMarkup(markup);
            telegramBotProvider.getObject().execute(msg);
        } catch (Exception e) {
            throw new TelegramCommandHandlerException("Failed to send model menu", e);
        }
    }

    private String buildCapabilityLabel(Set<ModelCapabilities> capabilities) {
        return capabilities.stream()
                .filter(DISPLAY_CAPS::contains)
                .map(this::capabilityToLabel)
                .collect(Collectors.joining(", "));
    }

    private String capabilityToLabel(ModelCapabilities cap) {
        return switch (cap) {
            case VISION -> "Vision";
            case WEB -> "Web";
            case TOOL_CALLING -> "Tools";
            case SUMMARIZATION -> "Summary";
            case FREE -> "Free";
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

        if (CALLBACK_AUTO.equals(callbackData)) {
            userModelPreferenceService.clearPreference(userId);
            ackCallback(cq.getId(), "✅ Auto mode");
        } else {
            String modelName = resolveModelName(callbackData, user);
            userModelPreferenceService.setPreferredModel(userId, modelName);
            ackCallback(cq.getId(), "✅ " + modelName);
        }
        persistentKeyboardService.sendKeyboard(command.telegramId(), userId, null);
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
