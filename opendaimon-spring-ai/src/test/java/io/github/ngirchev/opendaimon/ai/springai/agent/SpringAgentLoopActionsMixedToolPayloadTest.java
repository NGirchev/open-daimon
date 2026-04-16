package io.github.ngirchev.opendaimon.ai.springai.agent;

import io.github.ngirchev.fsm.impl.extended.ExDomainFsm;
import io.github.ngirchev.opendaimon.common.agent.AgentContext;
import io.github.ngirchev.opendaimon.common.agent.AgentEvent;
import io.github.ngirchev.opendaimon.common.agent.AgentLoopFsmFactory;
import io.github.ngirchev.opendaimon.common.agent.AgentRequest;
import io.github.ngirchev.opendaimon.common.agent.AgentState;
import io.github.ngirchev.opendaimon.common.agent.AgentStreamEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpringAgentLoopActionsMixedToolPayloadTest {

    @Mock
    private ChatModel chatModel;

    @Mock
    private ToolCallingManager toolCallingManager;

    private SpringAgentLoopActions actions;
    private ReActAgentExecutor executor;

    @BeforeEach
    void setUp() {
        actions = new SpringAgentLoopActions(chatModel, toolCallingManager, List.of(), null, null, null);
        ExDomainFsm<AgentContext, AgentState, AgentEvent> fsm = AgentLoopFsmFactory.create(actions);
        executor = new ReActAgentExecutor(fsm);
    }

    @Test
    @DisplayName("think() recovers mixed payload as synthetic tool call for ToolCallingManager")
    void think_mixedPayload_recoveredAsSyntheticToolCall() {
        String mixedPayload = """
                Я получил доступ к двум статьям с полезной информацией.
                http_get
                <arg_key>url</arg_key>
                <arg_value>https://azeynalli1990.medium.com/quarkus-versus-spring-full-comparison-f294803332d0</arg_value>
                </tool_call>
                """;
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse(mixedPayload));
        when(toolCallingManager.executeToolCalls(any(Prompt.class), any(ChatResponse.class)))
                .thenReturn(toolResultWithObservation("tool observation"));

        AgentContext ctx = new AgentContext("Compare Quarkus vs Spring", "conv-1", Map.of(), 10, Set.of());
        actions.think(ctx);

        assertThat(ctx.getCurrentToolName()).isEqualTo("http_get");
        assertThat(ctx.getCurrentToolArguments()).contains("\"url\"");
        assertThat(ctx.getCurrentTextResponse()).isNull();

        actions.executeTool(ctx);

        ArgumentCaptor<ChatResponse> responseCaptor = ArgumentCaptor.forClass(ChatResponse.class);
        verify(toolCallingManager).executeToolCalls(any(Prompt.class), responseCaptor.capture());
        ChatResponse capturedResponse = responseCaptor.getValue();
        assertThat(capturedResponse.hasToolCalls()).isTrue();
        assertThat(capturedResponse.getResult().getOutput().getToolCalls().getFirst().name()).isEqualTo("http_get");
        assertThat(ctx.getToolResult()).isNotNull();
        assertThat(ctx.getToolResult().success()).isTrue();
    }

    @Test
    @DisplayName("executeStream() continues loop after mixed payload and emits clean FINAL_ANSWER")
    void executeStream_mixedPayload_thenFinalAnswer() {
        String mixedPayload = """
                Я получил доступ к двум статьям с полезной информацией.
                web_search
                <arg_key>query</arg_key>
                <arg_value>Spring Boot vs Quarkus benchmark 2026</arg_value>
                </tool_call>
                """;
        when(chatModel.call(any(Prompt.class))).thenReturn(
                chatResponse(mixedPayload),
                chatResponse("Финальный ответ без payload.")
        );
        when(toolCallingManager.executeToolCalls(any(Prompt.class), any(ChatResponse.class)))
                .thenReturn(toolResultWithObservation("benchmark results extracted"));

        AgentRequest request = new AgentRequest("Compare frameworks", "conv-2", Map.of(), 10, Set.of());
        List<AgentStreamEvent> events = executor.executeStream(request).collectList().block();

        assertThat(events).isNotNull();
        assertThat(events.stream().map(AgentStreamEvent::type))
                .contains(AgentStreamEvent.EventType.TOOL_CALL, AgentStreamEvent.EventType.OBSERVATION);
        assertThat(events.stream().map(AgentStreamEvent::type))
                .doesNotContain(AgentStreamEvent.EventType.ERROR);

        AgentStreamEvent terminalEvent = events.getLast();
        assertThat(terminalEvent.type()).isEqualTo(AgentStreamEvent.EventType.FINAL_ANSWER);
        assertThat(terminalEvent.content()).isEqualTo("Финальный ответ без payload.");
        assertThat(terminalEvent.content()).doesNotContain("<arg_key>").doesNotContain("</tool_call>");
    }

    @Test
    @DisplayName("executeStream() with repeated mixed payloads reaches MAX_ITERATIONS instead of ERROR")
    void executeStream_repeatedMixedPayload_reachesMaxIterations() {
        String mixedPayload = """
                Нужна еще одна проверка источников.
                http_get
                <arg_key>url</arg_key>
                <arg_value>https://example.com/benchmarks</arg_value>
                </tool_call>
                """;
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> chatResponse(mixedPayload));
        when(toolCallingManager.executeToolCalls(any(Prompt.class), any(ChatResponse.class)))
                .thenReturn(toolResultWithObservation("tool result chunk"));

        AgentRequest request = new AgentRequest("Loop until limit", "conv-3", Map.of(), 3, Set.of());
        List<AgentStreamEvent> events = executor.executeStream(request).collectList().block();

        assertThat(events).isNotNull();
        assertThat(events.stream().map(AgentStreamEvent::type))
                .doesNotContain(AgentStreamEvent.EventType.ERROR);

        AgentStreamEvent terminalEvent = events.getLast();
        assertThat(terminalEvent.type()).isEqualTo(AgentStreamEvent.EventType.MAX_ITERATIONS);
        assertThat(terminalEvent.content()).doesNotContain("<arg_key>").doesNotContain("</tool_call>");
    }

    private ChatResponse chatResponse(String text) {
        return ChatResponse.builder()
                .metadata(ChatResponseMetadata.builder().model("test-model").build())
                .generations(List.of(new Generation(new AssistantMessage(text))))
                .build();
    }

    private ToolExecutionResult toolResultWithObservation(String observation) {
        return ToolExecutionResult.builder()
                .conversationHistory(List.of(new AssistantMessage(observation)))
                .build();
    }
}
