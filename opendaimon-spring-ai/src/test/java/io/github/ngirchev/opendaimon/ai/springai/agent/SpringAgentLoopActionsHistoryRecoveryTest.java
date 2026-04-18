package io.github.ngirchev.opendaimon.ai.springai.agent;

import io.github.ngirchev.opendaimon.common.agent.AgentContext;
import io.github.ngirchev.opendaimon.common.model.ConversationThread;
import io.github.ngirchev.opendaimon.common.model.MessageRole;
import io.github.ngirchev.opendaimon.common.model.OpenDaimonMessage;
import io.github.ngirchev.opendaimon.common.repository.ConversationThreadRepository;
import io.github.ngirchev.opendaimon.common.repository.OpenDaimonMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpringAgentLoopActionsHistoryRecoveryTest {

    @Mock
    private ChatModel chatModel;

    @Mock
    private ToolCallingManager toolCallingManager;

    @Mock
    private ChatMemory chatMemory;

    @Mock
    private ConversationThreadRepository conversationThreadRepository;

    @Mock
    private OpenDaimonMessageRepository openDaimonMessageRepository;

    private SpringAgentLoopActions actions;

    @BeforeEach
    void setUp() {
        actions = new SpringAgentLoopActions(
                chatModel,
                toolCallingManager,
                List.of(),
                null,
                null,
                chatMemory,
                conversationThreadRepository,
                openDaimonMessageRepository
        );
    }

    @Test
    @DisplayName("think() restores conversation history from primary store when ChatMemory is empty")
    void think_whenChatMemoryEmpty_restoresFromPrimaryStore() {
        String conversationId = "thread-restore-1";
        when(chatMemory.get(conversationId)).thenReturn(List.of());
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse("Current answer"));

        ConversationThread thread = new ConversationThread();
        thread.setThreadKey(conversationId);
        thread.setSummary("User asks for concise technical answers.");
        thread.setMemoryBullets(List.of("Prefers Java examples"));

        OpenDaimonMessage previousUser = new OpenDaimonMessage();
        previousUser.setRole(MessageRole.USER);
        previousUser.setContent("What is Quarkus?");

        OpenDaimonMessage previousAssistant = new OpenDaimonMessage();
        previousAssistant.setRole(MessageRole.ASSISTANT);
        previousAssistant.setContent("Quarkus is a cloud-native Java framework.");

        when(conversationThreadRepository.findByThreadKey(conversationId)).thenReturn(Optional.of(thread));
        when(openDaimonMessageRepository.findByThreadOrderBySequenceNumberAsc(thread))
                .thenReturn(List.of(previousUser, previousAssistant));

        AgentContext ctx = new AgentContext("And how is it different from Spring Boot?", conversationId, Map.of(), 10, Set.of());
        actions.think(ctx);

        verify(chatMemory).add(eq(conversationId), anyList());

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        List<Message> promptMessages = promptCaptor.getValue().getInstructions();

        boolean hasRestoredUser = promptMessages.stream()
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .anyMatch(message -> "What is Quarkus?".equals(message.getText()));
        boolean hasRestoredAssistant = promptMessages.stream()
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast)
                .anyMatch(message -> "Quarkus is a cloud-native Java framework.".equals(message.getText()));

        assertThat(hasRestoredUser).isTrue();
        assertThat(hasRestoredAssistant).isTrue();
    }

    @Test
    @DisplayName("handleMaxIterations() saves synthesized answer to ChatMemory")
    void handleMaxIterations_savesTurnToChatMemory() {
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse("Synthesized final answer"));

        AgentContext ctx = new AgentContext(
                "Compare Spring Boot and Quarkus in one paragraph",
                "thread-max-1",
                Map.of(),
                3,
                Set.of()
        );

        actions.handleMaxIterations(ctx);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> savedMessagesCaptor = (ArgumentCaptor<List<Message>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(List.class);
        verify(chatMemory).add(eq("thread-max-1"), savedMessagesCaptor.capture());

        List<Message> savedMessages = savedMessagesCaptor.getValue();
        assertThat(savedMessages).hasSize(2);
        assertThat(savedMessages.get(0)).isInstanceOf(UserMessage.class);
        assertThat(savedMessages.get(1)).isInstanceOf(AssistantMessage.class);
        assertThat(savedMessages.get(1).getText()).contains("Synthesized final answer");
    }

    private ChatResponse chatResponse(String text) {
        return ChatResponse.builder()
                .metadata(ChatResponseMetadata.builder().model("test-model").build())
                .generations(List.of(new Generation(new AssistantMessage(text))))
                .build();
    }
}
