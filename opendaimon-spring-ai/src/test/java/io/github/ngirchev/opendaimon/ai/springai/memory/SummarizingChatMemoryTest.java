package io.github.ngirchev.opendaimon.ai.springai.memory;

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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SummarizingChatMemory}.
 */
@ExtendWith(MockitoExtension.class)
class SummarizingChatMemoryTest {

    private static final String CONVERSATION_ID = "conv-1";
    private static final int MAX_MESSAGES = 5;

    private ChatMemoryRepository chatMemoryRepository;
    private SummarizingChatMemory summarizingChatMemory;

    @Mock
    private ConversationThreadRepository conversationThreadRepository;
    @Mock
    private OpenDaimonMessageRepository messageRepository;
    @Mock
    private SummarizationService summarizationService;

    @BeforeEach
    void setUp() {
        chatMemoryRepository = new InMemoryChatMemoryRepository();
        summarizingChatMemory = new SummarizingChatMemory(
                chatMemoryRepository,
                conversationThreadRepository,
                messageRepository,
                summarizationService,
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
        // One message: after removeLast() list is empty, summarization is skipped
        when(messageRepository.findByThreadAndSequenceNumberGreaterThanOrderBySequenceNumberAsc(eq(thread), any()))
                .thenReturn(new ArrayList<>(List.of(createMockMessage())));

        List<Message> result = summarizingChatMemory.get(CONVERSATION_ID);

        verify(conversationThreadRepository, atLeastOnce()).findByThreadKey(CONVERSATION_ID);
        verify(summarizationService, never()).summarizeThread(any(), any());
    }

    @Test
    void whenGetWithMessageCountAtMaxAndSummarizationSucceeds_thenChatMemoryContainsSummary() {
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
        ArrayList<OpenDaimonMessage> dbMessages = new ArrayList<>(List.of(createMockMessage(), createMockMessage()));
        when(messageRepository.findByThreadAndSequenceNumberGreaterThanOrderBySequenceNumberAsc(eq(thread), any()))
                .thenReturn(dbMessages);

        List<Message> result = summarizingChatMemory.get(CONVERSATION_ID);

        assertFalse(result.isEmpty());
        boolean hasSummary = result.stream()
                .filter(m -> m instanceof SystemMessage)
                .anyMatch(m -> ((SystemMessage) m).getText().contains("Summary of previous conversation")
                        && ((SystemMessage) m).getText().contains("Previous talk summary"));
        assertTrue(hasSummary, "Expected a SystemMessage with summary content: " + result);

        ArgumentCaptor<ConversationThread> threadCaptor = ArgumentCaptor.forClass(ConversationThread.class);
        ArgumentCaptor<List<OpenDaimonMessage>> listCaptor = ArgumentCaptor.forClass(List.class);
        verify(summarizationService).summarizeThread(threadCaptor.capture(), listCaptor.capture());
        assertEquals(CONVERSATION_ID, threadCaptor.getValue().getThreadKey());
    }

    @Test
    void whenGetWithMessageCountAtMaxAndSummarizationReturnsEmptySummary_thenNoSummaryMessageAdded() {
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
        ArrayList<OpenDaimonMessage> dbMessages = new ArrayList<>(List.of(createMockMessage(), createMockMessage()));
        when(messageRepository.findByThreadAndSequenceNumberGreaterThanOrderBySequenceNumberAsc(eq(thread), any()))
                .thenReturn(dbMessages);

        List<Message> result = summarizingChatMemory.get(CONVERSATION_ID);

        verify(summarizationService).summarizeThread(any(), any());
        boolean hasSummaryContent = result.stream()
                .filter(m -> m instanceof SystemMessage)
                .anyMatch(m -> ((SystemMessage) m).getText().contains("Summary of previous conversation"));
        assertFalse(hasSummaryContent);
    }

    private static OpenDaimonMessage createMockMessage() {
        OpenDaimonMessage m = new OpenDaimonMessage();
        m.setContent("content");
        return m;
    }
}
