package io.github.ngirchev.opendaimon.telegram.service;

import io.github.ngirchev.opendaimon.common.model.User;
import io.github.ngirchev.opendaimon.telegram.model.TelegramGroup;
import io.github.ngirchev.opendaimon.telegram.model.TelegramUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.objects.Chat;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatSettingsOwnerResolverTest {

    private static final Long PRIVATE_CHAT_ID = 42L;
    private static final Long GROUP_CHAT_ID = -1001234567890L;

    @Mock
    private TelegramUserService telegramUserService;
    @Mock
    private TelegramGroupService telegramGroupService;

    private ChatSettingsOwnerResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ChatSettingsOwnerResolver(telegramUserService, telegramGroupService);
    }

    @Test
    void shouldReturnTelegramUserWhenChatIsPrivate() {
        Chat chat = new Chat();
        chat.setType("private");
        org.telegram.telegrambots.meta.api.objects.User invoker =
                new org.telegram.telegrambots.meta.api.objects.User(PRIVATE_CHAT_ID, "alice", false);
        TelegramUser expected = new TelegramUser();
        when(telegramUserService.getOrCreateUser(invoker)).thenReturn(expected);

        User owner = resolver.resolveForChat(chat, invoker);

        assertThat(owner).isSameAs(expected);
        verify(telegramGroupService, never()).getOrCreateGroup(any());
    }

    @Test
    void shouldReturnTelegramGroupWhenChatIsGroup() {
        Chat chat = new Chat();
        chat.setId(GROUP_CHAT_ID);
        chat.setType("group");
        TelegramGroup expected = new TelegramGroup();
        when(telegramGroupService.getOrCreateGroup(chat)).thenReturn(expected);

        User owner = resolver.resolveForChat(chat,
                new org.telegram.telegrambots.meta.api.objects.User(1L, "bob", false));

        assertThat(owner).isSameAs(expected);
        verify(telegramUserService, never()).getOrCreateUser(any());
    }

    @Test
    void shouldReturnTelegramGroupWhenChatIsSupergroup() {
        Chat chat = new Chat();
        chat.setId(GROUP_CHAT_ID);
        chat.setType("supergroup");
        TelegramGroup expected = new TelegramGroup();
        when(telegramGroupService.getOrCreateGroup(chat)).thenReturn(expected);

        User owner = resolver.resolveForChat(chat,
                new org.telegram.telegrambots.meta.api.objects.User(1L, "bob", false));

        assertThat(owner).isSameAs(expected);
    }

    @Test
    void shouldFallBackToUserWhenChatIsNull() {
        org.telegram.telegrambots.meta.api.objects.User invoker =
                new org.telegram.telegrambots.meta.api.objects.User(PRIVATE_CHAT_ID, "alice", false);
        TelegramUser expected = new TelegramUser();
        when(telegramUserService.getOrCreateUser(invoker)).thenReturn(expected);

        User owner = resolver.resolveForChat(null, invoker);

        assertThat(owner).isSameAs(expected);
    }

    @Test
    void shouldReturnGroupFromFindByChatIdWhenIdIsNegative() {
        TelegramGroup expected = new TelegramGroup();
        when(telegramGroupService.findByChatId(GROUP_CHAT_ID)).thenReturn(Optional.of(expected));

        Optional<User> result = resolver.findByChatId(GROUP_CHAT_ID);

        assertThat(result).containsSame(expected);
        verify(telegramUserService, never()).findByTelegramId(any());
    }

    @Test
    void shouldReturnUserFromFindByChatIdWhenIdIsPositive() {
        TelegramUser expected = new TelegramUser();
        when(telegramUserService.findByTelegramId(PRIVATE_CHAT_ID)).thenReturn(Optional.of(expected));

        Optional<User> result = resolver.findByChatId(PRIVATE_CHAT_ID);

        assertThat(result).containsSame(expected);
        verify(telegramGroupService, never()).findByChatId(any());
    }

    @Test
    void shouldReturnEmptyWhenFindByChatIdReceivesNull() {
        assertThat(resolver.findByChatId(null)).isEmpty();
    }
}
