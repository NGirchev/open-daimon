package io.github.ngirchev.opendaimon.ai.springai.agent;

import io.github.ngirchev.opendaimon.common.agent.AgentRequest;
import io.github.ngirchev.opendaimon.common.agent.AgentStreamEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SimpleChainExecutorTest {

    @Mock
    private ChatModel chatModel;

    private SimpleChainExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new SimpleChainExecutor(chatModel, null);
    }

    @Test
    @DisplayName("executeStream() emits chunk, metadata, and final answer")
    void executeStream_emitsChunkMetadataAndFinalAnswer() {
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chatResponse("Hello world", "test-model")));

        AgentRequest request = new AgentRequest("Say hello", "conv-simple-1", Map.of(), 1, Set.of());
        List<AgentStreamEvent> events = executor.executeStream(request).collectList().block();

        assertThat(events).isNotNull();
        assertThat(events.stream().map(AgentStreamEvent::type))
                .containsExactly(
                        AgentStreamEvent.EventType.THINKING,
                        AgentStreamEvent.EventType.FINAL_ANSWER_CHUNK,
                        AgentStreamEvent.EventType.METADATA,
                        AgentStreamEvent.EventType.FINAL_ANSWER
                );
        String streamedAnswer = events.stream()
                .filter(event -> event.type() == AgentStreamEvent.EventType.FINAL_ANSWER_CHUNK)
                .map(AgentStreamEvent::content)
                .collect(Collectors.joining());
        assertThat(streamedAnswer).isEqualTo("Hello world");
        assertThat(events.getLast().content()).isEqualTo("Hello world");
    }

    private static ChatResponse chatResponse(String text, String modelName) {
        return ChatResponse.builder()
                .metadata(ChatResponseMetadata.builder().model(modelName).build())
                .generations(List.of(new Generation(new AssistantMessage(text))))
                .build();
    }
}
