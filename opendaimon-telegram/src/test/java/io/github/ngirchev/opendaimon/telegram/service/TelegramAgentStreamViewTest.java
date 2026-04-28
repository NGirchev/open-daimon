package io.github.ngirchev.opendaimon.telegram.service;

import io.github.ngirchev.opendaimon.common.agent.AgentStreamEvent;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommand;
import io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm.MessageHandlerContext;
import io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm.TelegramMessageSender;
import io.github.ngirchev.opendaimon.telegram.config.TelegramProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.objects.Message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TelegramAgentStreamViewTest {

    private static final long CHAT_ID = 12345L;
    private static final int USER_MESSAGE_ID = 10;
    private static final int STATUS_MESSAGE_ID = 20;
    private static final int ANSWER_MESSAGE_ID = 30;

    @Mock private TelegramMessageSender messageSender;
    @Mock private TelegramChatPacer telegramChatPacer;

    private TelegramAgentStreamView view;

    @BeforeEach
    void setUp() throws InterruptedException {
        TelegramProperties properties = new TelegramProperties();
        properties.setMaxMessageLength(4096);
        properties.getAgentStreamView().setFinalDeliveryTimeoutMs(5000);
        lenient().when(telegramChatPacer.tryReserve(anyLong())).thenReturn(true);
        lenient().when(telegramChatPacer.reserve(anyLong(), anyLong())).thenReturn(true);
        view = new TelegramAgentStreamView(messageSender, telegramChatPacer, properties);
    }

    @Test
    @DisplayName("flushFinal should reliably edit cleaned status before sending final answer")
    void shouldReliablyEditCleanedStatusBeforeSendingFinalAnswer() {
        MessageHandlerContext ctx = newContext();
        ctx.setStatusMessageId(STATUS_MESSAGE_ID);
        TelegramAgentStreamModel model = modelWithCleanedFinalAnswer();
        when(messageSender.editHtmlReliable(eq(CHAT_ID), eq(STATUS_MESSAGE_ID), any(), eq(true), eq(5000L)))
                .thenReturn(true);
        when(messageSender.sendHtmlReliableAndGetId(eq(CHAT_ID), any(), eq(USER_MESSAGE_ID), eq(false), eq(5000L)))
                .thenReturn(ANSWER_MESSAGE_ID);

        boolean delivered = view.flushFinal(ctx, model);

        ArgumentCaptor<String> statusCaptor = ArgumentCaptor.forClass(String.class);
        verify(messageSender).editHtmlReliable(
                eq(CHAT_ID), eq(STATUS_MESSAGE_ID), statusCaptor.capture(), eq(true), eq(5000L));
        assertThat(statusCaptor.getValue())
                .contains("🔧 <b>Tool:</b>")
                .doesNotContain("Final answer leaked into status");
        verify(messageSender, never()).editHtml(eq(CHAT_ID), eq(STATUS_MESSAGE_ID), any(), eq(true));
        assertThat(delivered).isTrue();
    }

    @Test
    @DisplayName("flushFinal should delete stale status when final status edit fails")
    void shouldDeleteStaleStatusWhenFinalStatusEditFails() {
        MessageHandlerContext ctx = newContext();
        ctx.setStatusMessageId(STATUS_MESSAGE_ID);
        TelegramAgentStreamModel model = modelWithCleanedFinalAnswer();
        when(messageSender.editHtmlReliable(eq(CHAT_ID), eq(STATUS_MESSAGE_ID), any(), eq(true), eq(5000L)))
                .thenReturn(false);
        when(messageSender.deleteMessage(eq(CHAT_ID), eq(STATUS_MESSAGE_ID))).thenReturn(true);
        when(messageSender.sendHtmlReliableAndGetId(eq(CHAT_ID), any(), eq(USER_MESSAGE_ID), eq(false), eq(5000L)))
                .thenReturn(ANSWER_MESSAGE_ID);

        boolean delivered = view.flushFinal(ctx, model);

        verify(messageSender).deleteMessage(CHAT_ID, STATUS_MESSAGE_ID);
        assertThat(ctx.getStatusMessageId()).isNull();
        assertThat(delivered).isTrue();
    }

    private static TelegramAgentStreamModel modelWithCleanedFinalAnswer() {
        TelegramAgentStreamModel model = new TelegramAgentStreamModel(false, false);
        model.apply(AgentStreamEvent.toolCall("web_search", "{\"query\":\"tickets\"}", 0));
        model.apply(AgentStreamEvent.observation("ok", 0));
        model.apply(AgentStreamEvent.thinking(1));
        model.apply(AgentStreamEvent.partialAnswer("Final answer leaked into status", 1));
        model.apply(AgentStreamEvent.finalAnswer("Final answer leaked into status", 1));
        return model;
    }

    private static MessageHandlerContext newContext() {
        TelegramCommand command = mock(TelegramCommand.class);
        when(command.telegramId()).thenReturn(CHAT_ID);
        Message message = mock(Message.class);
        when(message.getMessageId()).thenReturn(USER_MESSAGE_ID);
        return new MessageHandlerContext(command, message, ignored -> {});
    }
}
