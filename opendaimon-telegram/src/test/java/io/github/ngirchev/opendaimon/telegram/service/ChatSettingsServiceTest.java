package io.github.ngirchev.opendaimon.telegram.service;

import io.github.ngirchev.opendaimon.common.model.AssistantRole;
import io.github.ngirchev.opendaimon.common.model.ThinkingMode;
import io.github.ngirchev.opendaimon.telegram.model.TelegramGroup;
import io.github.ngirchev.opendaimon.telegram.model.TelegramUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies polymorphic dispatch of {@link ChatSettingsService}: each mutation method
 * must route to {@code TelegramGroupService} for group owners and to
 * {@code TelegramUserService} for user owners, keyed on the subtype's
 * {@code telegramId} (which is {@code chat_id} in both cases).
 */
@ExtendWith(MockitoExtension.class)
class ChatSettingsServiceTest {

    private static final Long USER_TELEGRAM_ID = 100L;
    private static final Long GROUP_CHAT_ID = -1001234567890L;

    @Mock
    private TelegramUserService telegramUserService;
    @Mock
    private TelegramGroupService telegramGroupService;

    private ChatSettingsService service;

    private TelegramUser userOwner;
    private TelegramGroup groupOwner;

    @BeforeEach
    void setUp() {
        service = new ChatSettingsService(telegramUserService, telegramGroupService);
        userOwner = new TelegramUser();
        userOwner.setTelegramId(USER_TELEGRAM_ID);
        groupOwner = new TelegramGroup();
        groupOwner.setTelegramId(GROUP_CHAT_ID);
    }

    @Test
    void shouldDispatchLanguageUpdateToGroupServiceWhenOwnerIsGroup() {
        service.updateLanguageCode(groupOwner, "ru");

        verify(telegramGroupService).updateLanguageCode(GROUP_CHAT_ID, "ru");
        verify(telegramUserService, never()).updateLanguageCode(any(), any());
    }

    @Test
    void shouldDispatchLanguageUpdateToUserServiceWhenOwnerIsUser() {
        service.updateLanguageCode(userOwner, "en");

        verify(telegramUserService).updateLanguageCode(USER_TELEGRAM_ID, "en");
        verify(telegramGroupService, never()).updateLanguageCode(any(), any());
    }

    @Test
    void shouldDispatchAgentModeToGroupServiceWhenOwnerIsGroup() {
        service.updateAgentMode(groupOwner, true);

        verify(telegramGroupService).updateAgentMode(GROUP_CHAT_ID, true);
        verify(telegramUserService, never()).updateAgentMode(any(), eq(true));
    }

    @Test
    void shouldDispatchAgentModeToUserServiceWhenOwnerIsUser() {
        service.updateAgentMode(userOwner, false);

        verify(telegramUserService).updateAgentMode(USER_TELEGRAM_ID, false);
        verify(telegramGroupService, never()).updateAgentMode(any(), eq(false));
    }

    @Test
    void shouldDispatchThinkingModeByOwnerType() {
        service.updateThinkingMode(groupOwner, ThinkingMode.SILENT);
        service.updateThinkingMode(userOwner, ThinkingMode.SHOW_ALL);

        verify(telegramGroupService).updateThinkingMode(GROUP_CHAT_ID, ThinkingMode.SILENT);
        verify(telegramUserService).updateThinkingMode(USER_TELEGRAM_ID, ThinkingMode.SHOW_ALL);
    }

    @Test
    void shouldDispatchAssistantRoleUpdateByOwnerType() {
        service.updateAssistantRole(groupOwner, "group role");

        verify(telegramGroupService).updateAssistantRole(GROUP_CHAT_ID, "group role");
        verify(telegramUserService, never()).updateAssistantRole(any(), any());
    }

    @Test
    void shouldDispatchGetOrCreateAssistantRoleToGroupServiceForGroup() {
        AssistantRole role = new AssistantRole();
        when(telegramGroupService.getOrCreateAssistantRole(groupOwner, "default")).thenReturn(role);

        AssistantRole result = service.getOrCreateAssistantRole(groupOwner, "default");

        assertThat(result).isSameAs(role);
    }

    @Test
    void shouldDispatchGetOrCreateAssistantRoleToUserServiceForUser() {
        AssistantRole role = new AssistantRole();
        when(telegramUserService.getOrCreateAssistantRole(userOwner, "default")).thenReturn(role);

        AssistantRole result = service.getOrCreateAssistantRole(userOwner, "default");

        assertThat(result).isSameAs(role);
    }

    @Test
    void shouldDispatchMenuVersionHashWriteByOwnerType() {
        service.updateMenuVersionHash(groupOwner, "hash-g");
        service.updateMenuVersionHash(userOwner, "hash-u");

        verify(telegramGroupService).updateMenuVersionHash(GROUP_CHAT_ID, "hash-g");
        verify(telegramUserService).updateMenuVersionHash(USER_TELEGRAM_ID, "hash-u");
    }

    @Test
    void shouldReadMenuVersionHashByOwnerType() {
        groupOwner.setMenuVersionHash("gh");
        userOwner.setMenuVersionHash("uh");

        assertThat(service.menuVersionHashOf(groupOwner)).isEqualTo("gh");
        assertThat(service.menuVersionHashOf(userOwner)).isEqualTo("uh");
    }

    @Test
    void shouldDispatchSetPreferredModelToGroupServiceForGroup() {
        service.setPreferredModel(groupOwner, "openrouter/auto");

        verify(telegramGroupService).updatePreferredModel(GROUP_CHAT_ID, "openrouter/auto");
    }

    @Test
    void shouldSetPreferredModelInlineForUserAndTouchTimestamp() {
        service.setPreferredModel(userOwner, "gpt-4o");

        assertThat(userOwner.getPreferredModelId()).isEqualTo("gpt-4o");
        verify(telegramUserService).updateUserActivity(userOwner);
    }

    @Test
    void shouldClearPreferredModelByDelegatingToSetWithNull() {
        service.clearPreferredModel(groupOwner);

        verify(telegramGroupService).updatePreferredModel(GROUP_CHAT_ID, null);
    }

    @Test
    void shouldReturnPreferredModelFromOwnerField() {
        groupOwner.setPreferredModelId("meta/llama-3");

        Optional<String> result = service.getPreferredModel(groupOwner);

        assertThat(result).contains("meta/llama-3");
    }

    @Test
    void shouldReturnEmptyPreferredModelWhenFieldIsBlank() {
        userOwner.setPreferredModelId("   ");

        assertThat(service.getPreferredModel(userOwner)).isEmpty();
    }

    @Test
    void shouldReturnEmptyPreferredModelWhenOwnerIsNull() {
        assertThat(service.getPreferredModel(null)).isEmpty();
    }

    @Test
    void shouldReturnTelegramIdByOwnerType() {
        assertThat(service.telegramIdOf(groupOwner)).isEqualTo(GROUP_CHAT_ID);
        assertThat(service.telegramIdOf(userOwner)).isEqualTo(USER_TELEGRAM_ID);
    }

    @Test
    void shouldThrowWhenOwnerTypeIsUnsupported() {
        io.github.ngirchev.opendaimon.common.model.User stranger =
                new io.github.ngirchev.opendaimon.common.model.User();
        assertThatThrownBy(() -> service.updateLanguageCode(stranger, "ru"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("updateLanguageCode");
    }
}
