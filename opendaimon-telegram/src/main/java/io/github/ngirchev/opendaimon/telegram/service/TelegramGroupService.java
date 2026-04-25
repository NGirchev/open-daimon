package io.github.ngirchev.opendaimon.telegram.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.objects.Chat;
import io.github.ngirchev.opendaimon.common.SupportedLanguages;
import io.github.ngirchev.opendaimon.common.model.AssistantRole;
import io.github.ngirchev.opendaimon.common.model.ThinkingMode;
import io.github.ngirchev.opendaimon.common.service.AssistantRoleService;
import io.github.ngirchev.opendaimon.telegram.model.TelegramGroup;
import io.github.ngirchev.opendaimon.telegram.repository.TelegramGroupRepository;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Manages {@link TelegramGroup} rows — the settings-owner entity for Telegram
 * group and supergroup chats. Mirrors {@link TelegramUserService} methods that
 * mutate per-chat state, but keyed on the group {@code chat_id}.
 * <p>
 * Deliberately does NOT implement {@code IUserService}: the bulkhead priority
 * source stays a single source (the invoker's {@code TelegramUser}).
 */
@Slf4j
@RequiredArgsConstructor
public class TelegramGroupService {

    private static final String GROUP_NOT_FOUND = "Telegram group not found";

    private final TelegramGroupRepository telegramGroupRepository;
    private final AssistantRoleService assistantRoleService;
    /** Default value for {@code agentModeEnabled} on new groups. Sourced from {@code open-daimon.agent.enabled}. */
    private final boolean defaultAgentModeEnabled;

    public Optional<TelegramGroup> findByChatId(Long chatId) {
        return telegramGroupRepository.findByTelegramId(chatId);
    }

    @Transactional
    public TelegramGroup getOrCreateGroup(Chat chat) {
        if (chat == null || chat.getId() == null) {
            throw new IllegalArgumentException("Chat and chat.id are required");
        }
        return telegramGroupRepository.findByTelegramId(chat.getId())
                .map(existing -> updateGroupInfo(existing, chat))
                .orElseGet(() -> createGroupInner(chat));
    }

    @Transactional
    public TelegramGroup updateLanguageCode(Long chatId, String languageCode) {
        TelegramGroup group = requireGroup(chatId);
        String normalized = languageCode != null && !languageCode.isBlank()
                ? languageCode.trim().toLowerCase().split("-")[0]
                : null;
        group.setLanguageCode(normalized);
        stampTimestamps(group);
        return telegramGroupRepository.save(group);
    }

    @Transactional
    public void updateThinkingMode(Long chatId, ThinkingMode thinkingMode) {
        TelegramGroup group = requireGroup(chatId);
        group.setThinkingMode(thinkingMode);
        stampTimestamps(group);
        telegramGroupRepository.save(group);
    }

    @Transactional
    public void updateAgentMode(Long chatId, boolean enabled) {
        TelegramGroup group = requireGroup(chatId);
        group.setAgentModeEnabled(enabled);
        stampTimestamps(group);
        telegramGroupRepository.save(group);
    }

    @Transactional
    public TelegramGroup updateAssistantRole(Long chatId, String assistantRoleContent) {
        TelegramGroup group = requireGroup(chatId);
        AssistantRole role = assistantRoleService.updateActiveRole(group, assistantRoleContent);
        group.setCurrentAssistantRole(role);
        stampTimestamps(group);
        return telegramGroupRepository.save(group);
    }

    @Transactional
    public AssistantRole getOrCreateAssistantRole(TelegramGroup group, String defaultContent) {
        Long chatId = group.getTelegramId();
        if (chatId == null) {
            throw new IllegalArgumentException("Group telegramId is null");
        }
        TelegramGroup managed = requireGroup(chatId);
        AssistantRole role = managed.getCurrentAssistantRole();
        if (role == null) {
            role = assistantRoleService.getOrCreateDefaultRole(managed, defaultContent);
            managed.setCurrentAssistantRole(role);
            stampTimestamps(managed);
            telegramGroupRepository.save(managed);
        }
        // Initialize role fields in this transaction to avoid LazyInitializationException later
        role.getId();
        role.getVersion();
        role.getContent();
        return role;
    }

    @Transactional
    public void updateMenuVersionHash(Long chatId, String hash) {
        TelegramGroup group = requireGroup(chatId);
        group.setMenuVersionHash(hash);
        group.setUpdatedAt(OffsetDateTime.now());
        telegramGroupRepository.save(group);
    }

    @Transactional
    public TelegramGroup updatePreferredModel(Long chatId, String modelName) {
        TelegramGroup group = requireGroup(chatId);
        group.setPreferredModelId(modelName);
        stampTimestamps(group);
        return telegramGroupRepository.save(group);
    }

    private TelegramGroup requireGroup(Long chatId) {
        return telegramGroupRepository.findByTelegramId(chatId)
                .orElseThrow(() -> new RuntimeException(GROUP_NOT_FOUND + ": chatId=" + chatId));
    }

    private TelegramGroup createGroupInner(Chat chat) {
        TelegramGroup group = new TelegramGroup();
        group.setTelegramId(chat.getId());
        group.setTitle(chat.getTitle());
        group.setType(chat.getType());
        OffsetDateTime now = OffsetDateTime.now();
        group.setCreatedAt(now);
        group.setUpdatedAt(now);
        group.setLastActivityAt(now);
        group.setIsBlocked(false);
        group.setIsAdmin(false);
        group.setIsPremium(false);
        group.setLanguageCode(SupportedLanguages.DEFAULT_LANGUAGE);
        group.setAgentModeEnabled(defaultAgentModeEnabled);
        TelegramGroup saved = telegramGroupRepository.save(group);
        log.info("Telegram group created: id={}, chatId={}, title='{}', type={}",
                saved.getId(), saved.getTelegramId(), saved.getTitle(), saved.getType());
        return saved;
    }

    private TelegramGroup updateGroupInfo(TelegramGroup group, Chat chat) {
        String title = chat.getTitle();
        if (title != null) {
            group.setTitle(title);
        }
        String type = chat.getType();
        if (type != null) {
            group.setType(type);
        }
        stampTimestamps(group);
        return telegramGroupRepository.save(group);
    }

    private void stampTimestamps(TelegramGroup group) {
        OffsetDateTime now = OffsetDateTime.now();
        group.setUpdatedAt(now);
        group.setLastActivityAt(now);
    }
}
