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
import io.github.ngirchev.opendaimon.common.model.User;
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
import io.github.ngirchev.opendaimon.telegram.service.ChatSettingsService;
import io.github.ngirchev.opendaimon.telegram.service.ModelSelectionSession;
import io.github.ngirchev.opendaimon.telegram.service.PersistentKeyboardService;
import io.github.ngirchev.opendaimon.telegram.service.TelegramUserService;
import io.github.ngirchev.opendaimon.telegram.service.TypingIndicatorService;
import io.github.ngirchev.opendaimon.telegram.service.UserRecentModelService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
public class ModelTelegramCommandHandler extends AbstractTelegramCommandHandlerWithResponseSend {

    private static final String CALLBACK_PREFIX = "MODEL_";
    private static final String CALLBACK_AUTO = CALLBACK_PREFIX + "AUTO";
    private static final String CALLBACK_CANCEL = CALLBACK_PREFIX + "CANCEL";
    private static final String CALLBACK_BACK = CALLBACK_PREFIX + "BACK";
    private static final String CALLBACK_NOOP = CALLBACK_PREFIX + "NOOP";
    private static final String CALLBACK_CAT_PREFIX = CALLBACK_PREFIX + "C_";

    private static final int PAGE_SIZE = 8;

    private static final Set<ModelCapabilities> DISPLAY_CAPS = Set.of(
            ModelCapabilities.VISION,
            ModelCapabilities.WEB,
            ModelCapabilities.TOOL_CALLING,
            ModelCapabilities.SUMMARIZATION,
            ModelCapabilities.FREE
    );

    private final TelegramUserService telegramUserService;
    private final ChatSettingsService chatSettingsService;
    private final AIGatewayRegistry aiGatewayRegistry;
    private final IUserPriorityService userPriorityService;
    private final PersistentKeyboardService persistentKeyboardService;
    private final ConversationThreadService conversationThreadService;
    private final ModelSelectionSession modelSelectionSession;
    private final UserRecentModelService userRecentModelService;

    /**
     * Ordered category catalogue shown in the Level-1 menu. RECENT captures
     * {@link #userRecentModelService} so it has to be an instance field rather
     * than a {@code static final} list.
     */
    private final List<ModelCategory> categoryDefinitions;

    public ModelTelegramCommandHandler(ObjectProvider<TelegramBot> telegramBotProvider,
                                       TypingIndicatorService typingIndicatorService,
                                       MessageLocalizationService messageLocalizationService,
                                       TelegramUserService telegramUserService,
                                       ChatSettingsService chatSettingsService,
                                       AIGatewayRegistry aiGatewayRegistry,
                                       IUserPriorityService userPriorityService,
                                       PersistentKeyboardService persistentKeyboardService,
                                       ConversationThreadService conversationThreadService,
                                       ModelSelectionSession modelSelectionSession,
                                       UserRecentModelService userRecentModelService) {
        super(telegramBotProvider, typingIndicatorService, messageLocalizationService);
        this.telegramUserService = telegramUserService;
        this.chatSettingsService = chatSettingsService;
        this.aiGatewayRegistry = aiGatewayRegistry;
        this.userPriorityService = userPriorityService;
        this.persistentKeyboardService = persistentKeyboardService;
        this.conversationThreadService = conversationThreadService;
        this.modelSelectionSession = modelSelectionSession;
        this.userRecentModelService = userRecentModelService;
        this.categoryDefinitions = buildCategoryDefinitions();
    }

    private List<ModelCategory> buildCategoryDefinitions() {
        return List.of(
                ModelCategory.dynamic("RECENT", "telegram.model.cat.recent",
                        (allModels, userId) -> {
                            List<String> recent = userRecentModelService
                                    .getRecentModels(userId, PAGE_SIZE);
                            if (recent.isEmpty()) {
                                return List.of();
                            }
                            Map<String, Integer> nameToIdx = indexByName(allModels);
                            return recent.stream()
                                    .map(nameToIdx::get)
                                    .filter(Objects::nonNull)
                                    .toList();
                        }),
                ModelCategory.filtered("LOCAL", "telegram.model.cat.local",
                        model -> "Ollama".equalsIgnoreCase(model.provider())),
                ModelCategory.filtered("VISION", "telegram.model.cat.vision",
                        model -> model.capabilities().contains(ModelCapabilities.VISION)
                                && !"Ollama".equalsIgnoreCase(model.provider())),
                ModelCategory.filtered("FREE", "telegram.model.cat.free",
                        model -> model.capabilities().contains(ModelCapabilities.FREE)
                                && !model.capabilities().contains(ModelCapabilities.VISION)
                                && !"Ollama".equalsIgnoreCase(model.provider())),
                ModelCategory.filtered("ALL", "telegram.model.cat.all", model -> true)
        );
    }

    private static Map<String, Integer> indexByName(List<ModelInfo> models) {
        Map<String, Integer> map = new HashMap<>(models.size() * 2);
        for (int i = 0; i < models.size(); i++) {
            map.put(models.get(i).name(), i);
        }
        return map;
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
        User owner = TelegramCommand.resolveOwner(command, user);
        sendCategoryMenu(command.telegramId(), user, owner.getId(), command.languageCode());
        return null;
    }

    // ==================== Category Menu (Level 1) ====================

    /**
     * @param ownerId id of the settings owner (TelegramGroup in groups, TelegramUser in
     *                private chats) — used as the key for per-chat recent-model lookups so
     *                group members see the group's recent models, not the invoker's private ones.
     * @param lang    language code resolved from the settings owner (populated on
     *                {@code command.languageCode()} in {@code TelegramBot.mapToTelegram*}).
     */
    private void sendCategoryMenu(Long chatId, TelegramUser user, Long ownerId, String lang) {
        try {
            List<ModelInfo> models = fetchModels(user);
            if (models.isEmpty()) {
                sendMessage(chatId, messageLocalizationService.getMessage(
                        "telegram.model.unavailable", lang));
                return;
            }

            if (models.size() <= PAGE_SIZE) {
                sendFlatModelList(chatId, models, lang);
                return;
            }

            MenuContent menu = buildCategoryMenuContent(models, lang, ownerId);
            SendMessage msg = new SendMessage(chatId.toString(), menu.text());
            msg.setReplyMarkup(menu.markup());
            telegramBotProvider.getObject().execute(msg);
        } catch (Exception e) {
            throw new TelegramCommandHandlerException("Failed to send category menu", e);
        }
    }

    /**
     * Flat model list for small model counts (no categories needed).
     */
    private void sendFlatModelList(Long chatId, List<ModelInfo> models, String lang) throws Exception {
        List<List<InlineKeyboardButton>> keyboard = new ArrayList<>();

        keyboard.add(List.of(createButton(
                messageLocalizationService.getMessage("telegram.model.auto", lang), CALLBACK_AUTO)));

        StringBuilder text = new StringBuilder(
                messageLocalizationService.getMessage("telegram.model.select", lang)).append("\n\n");

        for (int i = 0; i < models.size(); i++) {
            ModelInfo model = models.get(i);
            String caps = buildCapabilityLabel(model.capabilities(), lang);
            String providerPrefix = formatProviderPrefix(model.provider());
            text.append(i + 1).append(". ").append(providerPrefix).append(model.name());
            if (!caps.isEmpty()) {
                text.append(" — ").append(caps);
            }
            text.append("\n");

            keyboard.add(List.of(createButton(providerPrefix + model.name(), CALLBACK_PREFIX + i)));
        }

        keyboard.add(List.of(createButton(
                messageLocalizationService.getMessage("telegram.model.cancel", lang), CALLBACK_CANCEL)));

        SendMessage msg = new SendMessage(chatId.toString(), text.toString());
        msg.setReplyMarkup(new InlineKeyboardMarkup(keyboard));
        telegramBotProvider.getObject().execute(msg);
    }

    /**
     * Builds category menu content reused by both send and edit flows.
     * Categories with an empty resolver result (e.g. {@code RECENT} for a new
     * chat) are omitted automatically. {@code ownerId} is the settings-owner id
     * used by dynamic categories (like RECENT) to look up chat-scoped state —
     * passing the invoker's id instead would leak their private recent models
     * into the group view.
     */
    private MenuContent buildCategoryMenuContent(List<ModelInfo> models, String lang, Long ownerId) {
        List<List<InlineKeyboardButton>> keyboard = new ArrayList<>();

        keyboard.add(List.of(createButton(
                messageLocalizationService.getMessage("telegram.model.auto", lang), CALLBACK_AUTO)));

        for (ModelCategory category : categoryDefinitions) {
            int count = category.resolver().resolve(models, ownerId).size();
            if (count == 0) {
                continue;
            }
            String label = messageLocalizationService.getMessage(category.labelKey(), lang)
                    + " " + messageLocalizationService.getMessage("telegram.model.cat.count", lang, count);
            keyboard.add(List.of(createButton(label, CALLBACK_CAT_PREFIX + category.key())));
        }

        keyboard.add(List.of(createButton(
                messageLocalizationService.getMessage("telegram.model.cancel", lang), CALLBACK_CANCEL)));

        String text = messageLocalizationService.getMessage("telegram.model.categories", lang);
        return new MenuContent(text, new InlineKeyboardMarkup(keyboard));
    }

    // ==================== Model List within Category (Level 2) ====================

    private void showCategoryPage(Long chatId, Integer messageId, TelegramUser user, Long ownerId,
                                  String lang, String categoryKey, int page) {
        try {
            List<ModelInfo> allModels = fetchModels(user);

            ModelCategory category = findCategory(categoryKey);
            if (category == null) {
                log.warn("Unknown category '{}' for chat={}", categoryKey, chatId);
                return;
            }

            List<Integer> matchingIndices = category.resolver().resolve(allModels, ownerId);

            if (matchingIndices.isEmpty()) {
                log.warn("Empty category '{}' for chat={}", categoryKey, chatId);
                return;
            }

            int totalPages = (matchingIndices.size() + PAGE_SIZE - 1) / PAGE_SIZE;
            int safePage = Math.min(Math.max(page, 0), totalPages - 1);
            int fromIndex = safePage * PAGE_SIZE;
            int toIndex = Math.min(fromIndex + PAGE_SIZE, matchingIndices.size());
            List<Integer> pageIndices = matchingIndices.subList(fromIndex, toIndex);

            String catLabel = messageLocalizationService.getMessage(category.labelKey(), lang);
            String header = messageLocalizationService.getMessage(
                    "telegram.model.cat.header", lang, catLabel, safePage + 1, totalPages);

            StringBuilder text = new StringBuilder(header).append("\n\n");
            List<List<InlineKeyboardButton>> keyboard = new ArrayList<>();

            for (int globalIdx : pageIndices) {
                ModelInfo model = allModels.get(globalIdx);
                String caps = buildCapabilityLabel(model.capabilities(), lang);
                String providerPrefix = formatProviderPrefix(model.provider());
                text.append(providerPrefix).append(model.name());
                if (!caps.isEmpty()) {
                    text.append(" — ").append(caps);
                }
                text.append("\n");

                keyboard.add(List.of(createButton(
                        providerPrefix + model.name(), CALLBACK_PREFIX + globalIdx)));
            }

            if (totalPages > 1) {
                keyboard.add(buildPaginationRow(categoryKey, safePage, totalPages, lang));
            }

            keyboard.add(List.of(
                    createButton(messageLocalizationService.getMessage("telegram.model.back", lang), CALLBACK_BACK),
                    createButton(messageLocalizationService.getMessage("telegram.model.cancel", lang), CALLBACK_CANCEL)
            ));

            editMenuMessage(chatId, messageId, text.toString(), new InlineKeyboardMarkup(keyboard));
        } catch (Exception e) {
            log.error("Failed to show category page: {}", e.getMessage(), e);
        }
    }

    private List<InlineKeyboardButton> buildPaginationRow(String categoryKey, int currentPage,
                                                          int totalPages, String lang) {
        List<InlineKeyboardButton> row = new ArrayList<>();
        if (currentPage > 0) {
            row.add(createButton(
                    messageLocalizationService.getMessage("telegram.model.page.prev", lang),
                    CALLBACK_CAT_PREFIX + categoryKey + "_P" + (currentPage - 1)));
        }
        row.add(createButton((currentPage + 1) + "/" + totalPages, CALLBACK_NOOP));
        if (currentPage < totalPages - 1) {
            row.add(createButton(
                    messageLocalizationService.getMessage("telegram.model.page.next", lang),
                    CALLBACK_CAT_PREFIX + categoryKey + "_P" + (currentPage + 1)));
        }
        return row;
    }

    // ==================== Callback Handling ====================

    private void handleCallbackQuery(TelegramCommand command) {
        CallbackQuery cq = command.update().getCallbackQuery();
        String callbackData = cq.getData();
        if (callbackData == null || !callbackData.startsWith(CALLBACK_PREFIX)) {
            throw new TelegramCommandHandlerException(command.telegramId(), "Invalid callback data");
        }

        TelegramUser user = telegramUserService.getOrCreateUser(cq.getFrom());
        Long userId = user.getId();
        User owner = TelegramCommand.resolveOwner(command, user);
        Long ownerId = owner.getId();
        Integer messageId = extractMessageId(cq);

        // Cancel — delete, evict cache, return
        if (CALLBACK_CANCEL.equals(callbackData)) {
            ackCallback(cq.getId(), "");
            deleteMenuMessage(command.telegramId(), cq);
            modelSelectionSession.evict(userId);
            return;
        }

        // No-op (page indicator click)
        if (CALLBACK_NOOP.equals(callbackData)) {
            ackCallback(cq.getId(), "");
            return;
        }

        // Back to categories
        if (CALLBACK_BACK.equals(callbackData)) {
            ackCallback(cq.getId(), "");
            editToCategoryMenu(command.telegramId(), messageId, user, ownerId, command.languageCode());
            return;
        }

        // Open category or navigate page: MODEL_C_<cat> or MODEL_C_<cat>_P<n>
        if (callbackData.startsWith(CALLBACK_CAT_PREFIX)) {
            ackCallback(cq.getId(), "");
            String catPart = callbackData.substring(CALLBACK_CAT_PREFIX.length());
            String categoryKey;
            int page = 0;
            int pageIdx = catPart.lastIndexOf("_P");
            if (pageIdx > 0) {
                categoryKey = catPart.substring(0, pageIdx);
                try {
                    page = Integer.parseInt(catPart.substring(pageIdx + 2));
                } catch (NumberFormatException e) {
                    categoryKey = catPart;
                }
            } else {
                categoryKey = catPart;
            }
            showCategoryPage(command.telegramId(), messageId, user, ownerId, command.languageCode(),
                    categoryKey, page);
            return;
        }

        // Auto selection
        if (CALLBACK_AUTO.equals(callbackData)) {
            chatSettingsService.clearPreferredModel(owner);
            ackCallback(cq.getId(), messageLocalizationService.getMessage(
                    "telegram.model.ack.auto", command.languageCode()));
            deleteMenuMessage(command.telegramId(), cq);
            modelSelectionSession.evict(userId);
            sendPersistentKeyboard(command.telegramId(), ownerId);
            return;
        }

        // Model selection: MODEL_<idx>
        String modelName = resolveModelName(callbackData, user);
        chatSettingsService.setPreferredModel(owner, modelName);
        userRecentModelService.recordUsage(ownerId, modelName);
        ackCallback(cq.getId(), "✅ " + modelName);
        deleteMenuMessage(command.telegramId(), cq);
        modelSelectionSession.evict(userId);
        sendPersistentKeyboard(command.telegramId(), ownerId);
    }

    private void editToCategoryMenu(Long chatId, Integer messageId, TelegramUser user, Long ownerId,
                                    String lang) {
        try {
            List<ModelInfo> models = fetchModels(user);
            MenuContent menu = buildCategoryMenuContent(models, lang, ownerId);
            editMenuMessage(chatId, messageId, menu.text(), menu.markup());
        } catch (Exception e) {
            log.error("Failed to edit category menu: {}", e.getMessage(), e);
        }
    }

    // ==================== Model Resolution ====================

    private String resolveModelName(String callbackData, TelegramUser user) {
        String raw = callbackData.substring(CALLBACK_PREFIX.length());
        try {
            int idx = Integer.parseInt(raw);
            List<ModelInfo> models = fetchModels(user);
            if (idx >= 0 && idx < models.size()) {
                return models.get(idx).name();
            }
        } catch (NumberFormatException e) {
            log.warn("Unrecognised model callback data '{}', treating as model name", raw);
        }
        return raw;
    }

    private List<ModelInfo> fetchModels(TelegramUser user) {
        return modelSelectionSession.getOrFetch(user.getId(), () -> fetchModelsFromGateway(user));
    }

    private List<ModelInfo> fetchModelsFromGateway(TelegramUser user) {
        UserPriority userPriority = userPriorityService.getUserPriority(user.getId());
        Map<String, String> metadata = new HashMap<>();
        if (userPriority != null) {
            metadata.put(AICommand.USER_PRIORITY_FIELD, userPriority.name());
        }
        ModelListAICommand cmd = new ModelListAICommand(metadata);
        List<AIGateway> gateways = aiGatewayRegistry.getSupportedAiGateways(cmd);
        if (gateways.isEmpty()) {
            return List.of();
        }
        ModelListAIResponse response = (ModelListAIResponse) gateways.getFirst().generateResponse(cmd);
        return response.models();
    }

    // ==================== Helpers ====================

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

    private static String formatProviderPrefix(String provider) {
        return provider != null && !provider.isEmpty() ? "[" + provider + "] " : "";
    }

    private ModelCategory findCategory(String key) {
        return categoryDefinitions.stream()
                .filter(c -> c.key().equals(key))
                .findFirst()
                .orElse(null);
    }

    private static InlineKeyboardButton createButton(String label, String callbackData) {
        InlineKeyboardButton btn = new InlineKeyboardButton(label);
        btn.setCallbackData(callbackData);
        return btn;
    }

    private Integer extractMessageId(CallbackQuery cq) {
        if (cq.getMessage() instanceof Message message) {
            return message.getMessageId();
        }
        return null;
    }

    private void sendPersistentKeyboard(Long chatId, Long userId) {
        ConversationThread thread = conversationThreadService.findCurrentThread(
                ThreadScopeKind.TELEGRAM_CHAT, chatId).orElse(null);
        persistentKeyboardService.sendKeyboard(chatId, userId, thread);
    }

    private void editMenuMessage(Long chatId, Integer messageId, String text,
                                 InlineKeyboardMarkup markup) {
        if (messageId == null) {
            return;
        }
        try {
            EditMessageText edit = new EditMessageText();
            edit.setChatId(chatId.toString());
            edit.setMessageId(messageId);
            edit.setText(text);
            edit.setReplyMarkup(markup);
            telegramBotProvider.getObject().execute(edit);
        } catch (Exception e) {
            log.warn("Failed to edit menu message: {}", e.getMessage());
        }
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

    /**
     * Resolves the ordered list of model indices that belong to a category,
     * given the full model list and the user viewing the menu.
     */
    @FunctionalInterface
    interface IndexResolver {
        List<Integer> resolve(List<ModelInfo> allModels, Long userId);
    }

    private record ModelCategory(String key, String labelKey, IndexResolver resolver) {

        /**
         * Category whose members are fully determined by a per-model predicate;
         * order follows the natural order of {@code allModels}.
         */
        static ModelCategory filtered(String key, String labelKey, Predicate<ModelInfo> filter) {
            return new ModelCategory(key, labelKey,
                    (allModels, userId) -> IntStream.range(0, allModels.size())
                            .filter(i -> filter.test(allModels.get(i)))
                            .boxed()
                            .toList());
        }

        /**
         * Category with custom resolver (e.g. user-specific history).
         */
        static ModelCategory dynamic(String key, String labelKey, IndexResolver resolver) {
            return new ModelCategory(key, labelKey, resolver);
        }
    }

    private record MenuContent(String text, InlineKeyboardMarkup markup) {}
}
