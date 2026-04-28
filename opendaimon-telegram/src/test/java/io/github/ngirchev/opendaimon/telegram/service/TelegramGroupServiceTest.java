package io.github.ngirchev.opendaimon.telegram.service;

import io.github.ngirchev.opendaimon.common.model.AssistantRole;
import io.github.ngirchev.opendaimon.common.model.ThinkingMode;
import io.github.ngirchev.opendaimon.common.service.AssistantRoleService;
import io.github.ngirchev.opendaimon.telegram.model.TelegramGroup;
import io.github.ngirchev.opendaimon.telegram.repository.TelegramGroupRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.objects.Chat;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TelegramGroupServiceTest {

    private static final Long GROUP_CHAT_ID = -1001234567890L;
    private static final boolean DEFAULT_AGENT_MODE_ENABLED = false;

    @Mock
    private TelegramGroupRepository telegramGroupRepository;
    @Mock
    private AssistantRoleService assistantRoleService;

    private TelegramGroupService service;

    @BeforeEach
    void setUp() {
        service = new TelegramGroupService(telegramGroupRepository, assistantRoleService, DEFAULT_AGENT_MODE_ENABLED);
    }

    @Test
    void shouldCreateNewGroupWhenGetOrCreateCalledForUnknownChat() {
        Chat chat = new Chat();
        chat.setId(GROUP_CHAT_ID);
        chat.setTitle("DevOps team");
        chat.setType("supergroup");
        when(telegramGroupRepository.findByTelegramId(GROUP_CHAT_ID)).thenReturn(Optional.empty());
        when(telegramGroupRepository.save(any(TelegramGroup.class))).thenAnswer(inv -> inv.getArgument(0));

        TelegramGroup result = service.getOrCreateGroup(chat);

        assertThat(result.getTelegramId()).isEqualTo(GROUP_CHAT_ID);
        assertThat(result.getTitle()).isEqualTo("DevOps team");
        assertThat(result.getType()).isEqualTo("supergroup");
        assertThat(result.getIsBlocked()).isFalse();
        assertThat(result.getIsAdmin()).isFalse();
        assertThat(result.getAgentModeEnabled()).isEqualTo(DEFAULT_AGENT_MODE_ENABLED);
        assertThat(result.getCreatedAt()).isNotNull();
        assertThat(result.getLanguageCode()).isEqualTo("en"); // default language on creation
    }

    @Test
    void shouldReturnExistingGroupWithUpdatedMetadataWhenKnownChat() {
        Chat chat = new Chat();
        chat.setId(GROUP_CHAT_ID);
        chat.setTitle("Renamed group");
        chat.setType("supergroup");

        TelegramGroup existing = new TelegramGroup();
        existing.setTelegramId(GROUP_CHAT_ID);
        existing.setTitle("Old title");
        existing.setType("group");
        when(telegramGroupRepository.findByTelegramId(GROUP_CHAT_ID)).thenReturn(Optional.of(existing));
        when(telegramGroupRepository.save(any(TelegramGroup.class))).thenAnswer(inv -> inv.getArgument(0));

        TelegramGroup result = service.getOrCreateGroup(chat);

        assertThat(result).isSameAs(existing);
        assertThat(result.getTitle()).isEqualTo("Renamed group");
        assertThat(result.getType()).isEqualTo("supergroup");
    }

    @Test
    void shouldThrowWhenGetOrCreateGroupReceivesNullChat() {
        assertThatThrownBy(() -> service.getOrCreateGroup(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldNormaliseLanguageCodeAndPersistWhenUpdateLanguageCode() {
        TelegramGroup group = new TelegramGroup();
        group.setTelegramId(GROUP_CHAT_ID);
        when(telegramGroupRepository.findByTelegramId(GROUP_CHAT_ID)).thenReturn(Optional.of(group));
        when(telegramGroupRepository.save(any(TelegramGroup.class))).thenAnswer(inv -> inv.getArgument(0));

        service.updateLanguageCode(GROUP_CHAT_ID, "RU-ru");

        assertThat(group.getLanguageCode()).isEqualTo("ru");
    }

    @Test
    void shouldPersistAgentModeFlagWhenUpdateAgentMode() {
        TelegramGroup group = new TelegramGroup();
        group.setTelegramId(GROUP_CHAT_ID);
        when(telegramGroupRepository.findByTelegramId(GROUP_CHAT_ID)).thenReturn(Optional.of(group));

        service.updateAgentMode(GROUP_CHAT_ID, true);

        assertThat(group.getAgentModeEnabled()).isTrue();
    }

    @Test
    void shouldPersistThinkingModeWhenUpdateThinkingMode() {
        TelegramGroup group = new TelegramGroup();
        group.setTelegramId(GROUP_CHAT_ID);
        when(telegramGroupRepository.findByTelegramId(GROUP_CHAT_ID)).thenReturn(Optional.of(group));

        service.updateThinkingMode(GROUP_CHAT_ID, ThinkingMode.SHOW_ALL);

        assertThat(group.getThinkingMode()).isEqualTo(ThinkingMode.SHOW_ALL);
    }

    @Test
    void shouldCreateAssistantRoleWhenGroupHasNoneYet() {
        TelegramGroup group = new TelegramGroup();
        group.setTelegramId(GROUP_CHAT_ID);
        AssistantRole defaultRole = new AssistantRole();
        defaultRole.setId(7L);
        defaultRole.setVersion(1);
        defaultRole.setContent("default");
        when(telegramGroupRepository.findByTelegramId(GROUP_CHAT_ID)).thenReturn(Optional.of(group));
        when(assistantRoleService.getOrCreateDefaultRole(group, "default content")).thenReturn(defaultRole);

        AssistantRole result = service.getOrCreateAssistantRole(group, "default content");

        assertThat(result).isSameAs(defaultRole);
        assertThat(group.getCurrentAssistantRole()).isSameAs(defaultRole);
    }

    @Test
    void shouldReturnExistingAssistantRoleWithoutCallingRoleServiceWhenGroupAlreadyHasOne() {
        TelegramGroup group = new TelegramGroup();
        group.setTelegramId(GROUP_CHAT_ID);
        AssistantRole existing = new AssistantRole();
        existing.setId(42L);
        existing.setVersion(3);
        existing.setContent("existing");
        group.setCurrentAssistantRole(existing);
        when(telegramGroupRepository.findByTelegramId(GROUP_CHAT_ID)).thenReturn(Optional.of(group));

        AssistantRole result = service.getOrCreateAssistantRole(group, "default content");

        assertThat(result).isSameAs(existing);
    }

    @Test
    void shouldPersistPreferredModelWhenUpdatePreferredModel() {
        TelegramGroup group = new TelegramGroup();
        group.setTelegramId(GROUP_CHAT_ID);
        when(telegramGroupRepository.findByTelegramId(GROUP_CHAT_ID)).thenReturn(Optional.of(group));
        when(telegramGroupRepository.save(any(TelegramGroup.class))).thenAnswer(inv -> inv.getArgument(0));

        service.updatePreferredModel(GROUP_CHAT_ID, "openrouter/auto");

        assertThat(group.getPreferredModelId()).isEqualTo("openrouter/auto");
    }

    @Test
    void shouldPersistMenuVersionHashWhenUpdateMenuVersionHash() {
        TelegramGroup group = new TelegramGroup();
        group.setTelegramId(GROUP_CHAT_ID);
        when(telegramGroupRepository.findByTelegramId(GROUP_CHAT_ID)).thenReturn(Optional.of(group));

        service.updateMenuVersionHash(GROUP_CHAT_ID, "deadbeef");

        assertThat(group.getMenuVersionHash()).isEqualTo("deadbeef");
    }
}
