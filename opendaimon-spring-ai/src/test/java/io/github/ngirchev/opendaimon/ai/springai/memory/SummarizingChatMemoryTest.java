package io.github.ngirchev.opendaimon.ai.springai.memory;

import io.github.ngirchev.opendaimon.common.model.MessageRole;
import io.github.ngirchev.opendaimon.common.model.OpenDaimonMessage;
import io.github.ngirchev.opendaimon.common.model.ConversationThread;
import io.github.ngirchev.opendaimon.common.repository.OpenDaimonMessageRepository;
import io.github.ngirchev.opendaimon.common.repository.ConversationThreadRepository;
import io.github.ngirchev.opendaimon.common.service.SummarizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.github.ngirchev.opendaimon.common.exception.SummarizationFailedException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SummarizingChatMemory}.
 * Summarization triggers when message count in ChatMemory reaches maxMessages.
 */
@ExtendWith(MockitoExtension.class)
class SummarizingChatMemoryTest {

    private static final String CONVERSATION_ID = "conv-1";
    private static final int MAX_MESSAGES = 5;

    private SummarizingChatMemory summarizingChatMemory;

    @Mock
    private ConversationThreadRepository conversationThreadRepository;
    @Mock
    private OpenDaimonMessageRepository messageRepository;
    @Mock
    private SummarizationService summarizationService;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @BeforeEach
    void setUp() {
        ChatMemoryRepository chatMemoryRepository = new InMemoryChatMemoryRepository();
        summarizingChatMemory = new SummarizingChatMemory(
                chatMemoryRepository,
                conversationThreadRepository,
                messageRepository,
                summarizationService,
                eventPublisher,
                MAX_MESSAGES
        );
    }

    @Test
    void whenGetWithFewerThanMaxMessages_thenReturnsMessagesWithoutSummarization() {
        summarizingChatMemory.add(CONVERSATION_ID, new UserMessage("Hello"));
        summarizingChatMemory.add(CONVERSATION_ID, new AssistantMessage("Hi"));

        List<Message> result = summarizingChatMemory.get(CONVERSATION_ID);

        assertEquals(2, result.size());
        verify(conversationThreadRepository, never()).findByThreadKey(any());
    }

    @Test
    void whenAddSingleMessage_thenDelegateStoresIt() {
        UserMessage msg = new UserMessage("Test");
        summarizingChatMemory.add(CONVERSATION_ID, msg);

        List<Message> result = summarizingChatMemory.get(CONVERSATION_ID);
        assertEquals(1, result.size());
        assertTrue(result.get(0) instanceof UserMessage);
        assertEquals("Test", ((UserMessage) result.get(0)).getText());
    }

    @Test
    void whenAddMessageList_thenDelegateStoresThem() {
        List<Message> messages = List.of(
                new UserMessage("U1"),
                new AssistantMessage("A1")
        );
        summarizingChatMemory.add(CONVERSATION_ID, messages);

        List<Message> result = summarizingChatMemory.get(CONVERSATION_ID);
        assertEquals(2, result.size());
    }

    @Test
    void whenClear_thenDelegateClears() {
        summarizingChatMemory.add(CONVERSATION_ID, new UserMessage("x"));
        summarizingChatMemory.clear(CONVERSATION_ID);

        List<Message> result = summarizingChatMemory.get(CONVERSATION_ID);
        assertTrue(result.isEmpty());
    }

    @Test
    void whenGetWithMessageCountAtMax_thenSummarizationTriggered() {
        // Adding exactly maxMessages messages reaches the threshold
        for (int i = 0; i < MAX_MESSAGES; i++) {
            summarizingChatMemory.add(CONVERSATION_ID, new UserMessage("u" + i));
        }
        when(conversationThreadRepository.findByThreadKey(CONVERSATION_ID)).thenReturn(Optional.empty());

        summarizingChatMemory.get(CONVERSATION_ID);

        verify(conversationThreadRepository).findByThreadKey(CONVERSATION_ID);
    }

    @Test
    void whenGetWithMessageCountBelowMax_thenSummarizationNotTriggered() {
        // Adding maxMessages - 1 messages must not trigger
        for (int i = 0; i < MAX_MESSAGES - 1; i++) {
            summarizingChatMemory.add(CONVERSATION_ID, new UserMessage("u" + i));
        }

        summarizingChatMemory.get(CONVERSATION_ID);

        verify(conversationThreadRepository, never()).findByThreadKey(any());
    }

    @Test
    void whenGetWithMessageCountAtMaxAndThreadNotFound_thenReturnsDelegateMessagesWithoutSummarization() {
        for (int i = 0; i < MAX_MESSAGES; i++) {
            summarizingChatMemory.add(CONVERSATION_ID, new UserMessage("u" + i));
            summarizingChatMemory.add(CONVERSATION_ID, new AssistantMessage("a" + i));
        }
        when(conversationThreadRepository.findByThreadKey(CONVERSATION_ID)).thenReturn(Optional.empty());

        List<Message> result = summarizingChatMemory.get(CONVERSATION_ID);

        // Delegate (MessageWindowChatMemory) keeps only last maxMessages messages
        assertEquals(MAX_MESSAGES, result.size());
        verify(conversationThreadRepository).findByThreadKey(CONVERSATION_ID);
        verify(summarizationService, never()).summarizeThread(any(), any());
    }

    @Test
    void whenGetWithMessageCountAtMaxAndThreadFoundButOnlyOneMessageInDb_thenSummarizationNotCalled() {
        for (int i = 0; i < MAX_MESSAGES; i++) {
            summarizingChatMemory.add(CONVERSATION_ID, new UserMessage("u" + i));
            summarizingChatMemory.add(CONVERSATION_ID, new AssistantMessage("a" + i));
        }
        ConversationThread thread = new ConversationThread();
        thread.setThreadKey(CONVERSATION_ID);
        when(conversationThreadRepository.findByThreadKey(CONVERSATION_ID)).thenReturn(Optional.of(thread));
        // One message: size < 2, partial summarization is skipped
        when(messageRepository.findByThreadAndSequenceNumberGreaterThanOrderBySequenceNumberAsc(eq(thread), any()))
                .thenReturn(new ArrayList<>(List.of(createMockMessage(MessageRole.USER))));

        List<Message> result = summarizingChatMemory.get(CONVERSATION_ID);

        verify(conversationThreadRepository, atLeastOnce()).findByThreadKey(CONVERSATION_ID);
        verify(summarizationService, never()).summarizeThread(any(), any());
    }

    @Test
    void whenGetWithMessageCountAtMaxAndSummarizationSucceeds_thenChatMemoryContainsSummaryAndRecentMessages() {
        for (int i = 0; i < MAX_MESSAGES; i++) {
            summarizingChatMemory.add(CONVERSATION_ID, new UserMessage("u" + i));
            summarizingChatMemory.add(CONVERSATION_ID, new AssistantMessage("a" + i));
        }
        ConversationThread thread = new ConversationThread();
        thread.setThreadKey(CONVERSATION_ID);
        thread.setMessagesAtLastSummarization(null);
        ConversationThread threadWithSummary = new ConversationThread();
        threadWithSummary.setThreadKey(CONVERSATION_ID);
        threadWithSummary.setSummary("Previous talk summary");
        threadWithSummary.setMemoryBullets(List.of("Point one", "Point two"));

        when(conversationThreadRepository.findByThreadKey(CONVERSATION_ID))
                .thenReturn(Optional.of(thread))
                .thenReturn(Optional.of(threadWithSummary));
        // 4 messages in DB: partial summarization splits into 2 to summarize + 2 to keep
        ArrayList<OpenDaimonMessage> dbMessages = new ArrayList<>(List.of(
                createMockMessage(MessageRole.USER),
                createMockMessage(MessageRole.ASSISTANT),
                createMockMessage(MessageRole.USER),
                createMockMessage(MessageRole.ASSISTANT)));
        when(messageRepository.findByThreadAndSequenceNumberGreaterThanOrderBySequenceNumberAsc(eq(thread), any()))
                .thenReturn(dbMessages);

        List<Message> result = summarizingChatMemory.get(CONVERSATION_ID);

        assertFalse(result.isEmpty());
        // Should contain summary SystemMessage
        boolean hasSummary = result.stream()
                .filter(m -> m instanceof SystemMessage)
                .anyMatch(m -> ((SystemMessage) m).getText().contains("Summary of previous conversation")
                        && ((SystemMessage) m).getText().contains("Previous talk summary"));
        assertTrue(hasSummary, "Expected a SystemMessage with summary content: " + result);
        // Should also contain recent messages (the kept half)
        long nonSystemCount = result.stream().filter(m -> !(m instanceof SystemMessage)).count();
        assertEquals(2, nonSystemCount, "Expected 2 recent messages kept after partial summarization");

        // Verify only the first half was passed to summarization
        ArgumentCaptor<List<OpenDaimonMessage>> listCaptor = ArgumentCaptor.forClass(List.class);
        verify(summarizationService).summarizeThread(any(), listCaptor.capture());
        assertEquals(2, listCaptor.getValue().size(), "Expected only half of messages to be summarized");
    }

    @Test
    void whenGetWithMessageCountAtMaxAndSummarizationReturnsEmptySummary_thenNoSummaryMessageAddedButRecentMessagesKept() {
        for (int i = 0; i < MAX_MESSAGES; i++) {
            summarizingChatMemory.add(CONVERSATION_ID, new UserMessage("u" + i));
            summarizingChatMemory.add(CONVERSATION_ID, new AssistantMessage("a" + i));
        }
        ConversationThread thread = new ConversationThread();
        thread.setThreadKey(CONVERSATION_ID);
        ConversationThread threadAfterSummary = new ConversationThread();
        threadAfterSummary.setThreadKey(CONVERSATION_ID);
        threadAfterSummary.setSummary(null);

        when(conversationThreadRepository.findByThreadKey(CONVERSATION_ID))
                .thenReturn(Optional.of(thread))
                .thenReturn(Optional.of(threadAfterSummary));
        ArrayList<OpenDaimonMessage> dbMessages = new ArrayList<>(List.of(
                createMockMessage(MessageRole.USER),
                createMockMessage(MessageRole.ASSISTANT),
                createMockMessage(MessageRole.USER),
                createMockMessage(MessageRole.ASSISTANT)));
        when(messageRepository.findByThreadAndSequenceNumberGreaterThanOrderBySequenceNumberAsc(eq(thread), any()))
                .thenReturn(dbMessages);

        List<Message> result = summarizingChatMemory.get(CONVERSATION_ID);

        verify(summarizationService).summarizeThread(any(), any());
        boolean hasSummaryContent = result.stream()
                .filter(m -> m instanceof SystemMessage)
                .anyMatch(m -> ((SystemMessage) m).getText().contains("Summary of previous conversation"));
        assertFalse(hasSummaryContent);
        // Recent messages should still be kept
        assertEquals(2, result.size(), "Expected 2 recent messages kept even without summary");
    }

    @Test
    void whenSummarizationServiceThrows_thenSummarizationFailedExceptionPropagates() {
        for (int i = 0; i < MAX_MESSAGES; i++) {
            summarizingChatMemory.add(CONVERSATION_ID, new UserMessage("u" + i));
            summarizingChatMemory.add(CONVERSATION_ID, new AssistantMessage("a" + i));
        }
        ConversationThread thread = new ConversationThread();
        thread.setThreadKey(CONVERSATION_ID);
        when(conversationThreadRepository.findByThreadKey(CONVERSATION_ID)).thenReturn(Optional.of(thread));
        when(messageRepository.findByThreadAndSequenceNumberGreaterThanOrderBySequenceNumberAsc(eq(thread), any()))
                .thenReturn(new ArrayList<>(List.of(
                        createMockMessage(MessageRole.USER),
                        createMockMessage(MessageRole.ASSISTANT),
                        createMockMessage(MessageRole.USER),
                        createMockMessage(MessageRole.ASSISTANT))));
        doThrow(new RuntimeException("AI model unavailable"))
                .when(summarizationService).summarizeThread(any(), anyList());

        assertThrows(SummarizationFailedException.class,
                () -> summarizingChatMemory.get(CONVERSATION_ID));
    }

    private static OpenDaimonMessage createMockMessage(MessageRole role) {
        OpenDaimonMessage m = new OpenDaimonMessage();
        m.setRole(role);
        m.setContent("content");
        return m;
    }
}
