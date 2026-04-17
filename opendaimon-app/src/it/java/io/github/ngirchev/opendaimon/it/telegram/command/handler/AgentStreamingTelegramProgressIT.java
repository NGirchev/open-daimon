package io.github.ngirchev.opendaimon.it.telegram.command.handler;

import io.github.ngirchev.opendaimon.common.agent.AgentExecutor;
import io.github.ngirchev.opendaimon.common.agent.AgentStreamEvent;
import io.github.ngirchev.opendaimon.common.model.ConversationThread;
import io.github.ngirchev.opendaimon.common.model.MessageRole;
import io.github.ngirchev.opendaimon.common.model.OpenDaimonMessage;
import io.github.ngirchev.opendaimon.common.repository.ConversationThreadRepository;
import io.github.ngirchev.opendaimon.common.repository.OpenDaimonMessageRepository;
import io.github.ngirchev.opendaimon.it.ITTestConfiguration;
import io.github.ngirchev.opendaimon.telegram.TelegramBot;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommand;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommandType;
import io.github.ngirchev.opendaimon.telegram.command.handler.impl.MessageTelegramCommandHandler;
import io.github.ngirchev.opendaimon.telegram.model.TelegramUser;
import io.github.ngirchev.opendaimon.telegram.repository.TelegramUserRepository;
import io.github.ngirchev.opendaimon.telegram.service.TelegramBotRegistrar;
import io.github.ngirchev.opendaimon.test.AbstractContainerIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = ITTestConfiguration.class, properties = {
        "open-daimon.agent.enabled=false",
        "open-daimon.agent.max-iterations=10",
        "open-daimon.telegram.max-message-length=4096",
        "open-daimon.telegram.file-upload.enabled=false",
        "open-daimon.telegram.file-upload.max-file-size-mb=20",
        "open-daimon.telegram.file-upload.supported-image-types=jpeg,png,gif,webp",
        "open-daimon.telegram.file-upload.supported-document-types=pdf"
})
@ActiveProfiles("integration-test")
class AgentStreamingTelegramProgressIT extends AbstractContainerIT {

    private static final Long CHAT_ID = 350009010L;

    @Autowired
    private MessageTelegramCommandHandler messageHandler;

    @Autowired
    private TelegramUserRepository telegramUserRepository;

    @Autowired
    private ConversationThreadRepository threadRepository;

    @Autowired
    private OpenDaimonMessageRepository messageRepository;

    @MockitoBean
    private TelegramBot telegramBot;

    @MockitoBean
    private TelegramBotRegistrar telegramBotRegistrar;

    @MockitoBean
    private AgentExecutor agentExecutor;

    @BeforeEach
    void setUp() throws TelegramApiException {
        messageRepository.deleteAll();
        threadRepository.deleteAll();
        telegramUserRepository.deleteAll();

        reset(telegramBot, agentExecutor);

        when(telegramBot.sendMessageAndGetId(anyLong(), anyString(), any(), eq(true))).thenReturn(900);
        doNothingCompat();
    }

    @Test
    @DisplayName("Agent streaming updates one progress message and edits a dedicated final-answer message")
    void streamingProgress_isEditedAndFinalAnswerIsStreamedByEdits() throws TelegramApiException {
        when(telegramBot.sendMessageAndGetId(anyLong(), anyString(), any(), eq(true)))
                .thenReturn(900, 901);
        when(agentExecutor.executeStream(any())).thenReturn(Flux.just(
                AgentStreamEvent.thinking(0),
                AgentStreamEvent.toolCall("web_search", "spring boot latest", 0),
                AgentStreamEvent.observation("No result", 0),
                AgentStreamEvent.metadata("test-model", 1),
                AgentStreamEvent.finalAnswerChunk("Final answer ", 1),
                AgentStreamEvent.finalAnswerChunk("for user.", 1),
                AgentStreamEvent.finalAnswer("Final answer for user.", 1)
        ));

        TelegramCommand command = createMessageCommand(CHAT_ID, 100, "Какая последняя версия Spring Boot?");

        messageHandler.handle(command);

        verify(telegramBot, times(2))
                .sendMessageAndGetId(eq(CHAT_ID), anyString(), eq(100), eq(true));
        ArgumentCaptor<String> progressEdits = ArgumentCaptor.forClass(String.class);
        verify(telegramBot, atLeastOnce())
                .editMessageHtml(eq(CHAT_ID), eq(900), progressEdits.capture(), eq(true));
        assertThat(progressEdits.getAllValues()).isNotEmpty();
        assertThat(progressEdits.getAllValues().getLast())
                .doesNotContain("Thinking")
                .contains("web_search")
                .contains("No result");

        ArgumentCaptor<String> finalEdits = ArgumentCaptor.forClass(String.class);
        verify(telegramBot, atLeastOnce())
                .editMessageHtml(eq(CHAT_ID), eq(901), finalEdits.capture(), eq(true));
        assertThat(finalEdits.getAllValues().getLast()).contains("Final answer for user.");

        verify(telegramBot, never())
                .sendMessage(eq(CHAT_ID), anyString(), eq(100), any(ReplyKeyboard.class));
        verify(telegramBot, never())
                .sendMessage(eq(CHAT_ID), contains("Final answer for user."), eq(100), isNull());

        TelegramUser user = telegramUserRepository.findByTelegramId(CHAT_ID)
                .orElseThrow(() -> new IllegalStateException("User should exist"));
        ConversationThread thread = threadRepository.findMostRecentActiveThread(user)
                .orElseThrow(() -> new IllegalStateException("Thread should exist"));
        List<OpenDaimonMessage> assistantMessages = messageRepository
                .findByThreadAndRoleOrderBySequenceNumberAsc(thread, MessageRole.ASSISTANT);

        assertThat(assistantMessages).isNotEmpty();
        assertThat(assistantMessages.getLast().getContent()).isEqualTo("Final answer for user.");
    }

    @Test
    @DisplayName("Agent streaming deletes progress message when it only contains transient thinking")
    void streamingProgress_thinkingOnly_isDeletedBeforeFinalAnswer() throws TelegramApiException {
        when(agentExecutor.executeStream(any())).thenReturn(Flux.just(
                AgentStreamEvent.thinking(0),
                AgentStreamEvent.thinking("Analyzing request", 0),
                AgentStreamEvent.finalAnswer("Final answer for user.", 0)
        ));

        TelegramCommand command = createMessageCommand(CHAT_ID, 100, "Скажи кратко");

        messageHandler.handle(command);

        verify(telegramBot, times(1))
                .sendMessageAndGetId(eq(CHAT_ID), anyString(), eq(100), eq(true));
        verify(telegramBot, atLeastOnce())
                .editMessageHtml(eq(CHAT_ID), eq(900), contains("Analyzing request"), eq(true));
        verify(telegramBot, times(1))
                .deleteMessage(eq(CHAT_ID), eq(900));

        boolean finalViaSendMessageAndGetId = false;
        try {
            verify(telegramBot, atLeastOnce())
                    .sendMessageAndGetId(eq(CHAT_ID), contains("Final answer for user."), eq(100));
            finalViaSendMessageAndGetId = true;
        } catch (AssertionError ignored) {
            // Final answer may be sent via sendMessage(...) instead.
        }

        boolean finalViaSendMessage = false;
        try {
            verify(telegramBot, atLeastOnce())
                    .sendMessage(eq(CHAT_ID), contains("Final answer for user."), eq(100), isNull());
            finalViaSendMessage = true;
        } catch (AssertionError ignored) {
            // Alternative path is sendMessageAndGetId(...)
        }

        assertThat(finalViaSendMessageAndGetId || finalViaSendMessage).isTrue();
    }

    private void doNothingCompat() throws TelegramApiException {
        doNothing().when(telegramBot).showTyping(anyLong());
        doNothing().when(telegramBot).sendMessage(anyLong(), anyString(), any(), any(ReplyKeyboard.class));
        doNothing().when(telegramBot).sendMessage(anyLong(), anyString(), any());
        doNothing().when(telegramBot).sendErrorMessage(anyLong(), anyString(), any());
        doNothing().when(telegramBot).editMessageHtml(anyLong(), any(), anyString(), anyBoolean());
        doNothing().when(telegramBot).editMessageHtml(anyLong(), any(), anyString());
        doNothing().when(telegramBot).deleteMessage(anyLong(), any());
    }

    private TelegramCommand createMessageCommand(Long chatId, int messageId, String text) {
        Update update = new Update();

        User from = new User();
        from.setId(chatId);
        from.setUserName("agent-stream-user-" + chatId);
        from.setFirstName("Agent");
        from.setLastName("Stream");
        from.setLanguageCode("ru");

        Message message = new Message();
        message.setMessageId(messageId);
        Chat chat = new Chat();
        chat.setId(chatId);
        message.setChat(chat);
        message.setFrom(from);
        message.setText(text);
        update.setMessage(message);

        TelegramCommand command = new TelegramCommand(
                null,
                chatId,
                new TelegramCommandType(TelegramCommand.MESSAGE),
                update,
                text,
                false,
                List.of()
        );
        command.languageCode("ru");
        return command;
    }
}
