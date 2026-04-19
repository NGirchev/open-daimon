package io.github.ngirchev.opendaimon.ai.springai.agent;

import io.github.ngirchev.fsm.impl.extended.ExDomainFsm;
import io.github.ngirchev.opendaimon.common.agent.AgentContext;
import io.github.ngirchev.opendaimon.common.agent.AgentEvent;
import io.github.ngirchev.opendaimon.common.agent.AgentLoopFsmFactory;
import io.github.ngirchev.opendaimon.common.agent.AgentRequest;
import io.github.ngirchev.opendaimon.common.agent.AgentStepResult;
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
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

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
        actions = new SpringAgentLoopActions(chatModel, toolCallingManager, List.of(), null, null);
        ExDomainFsm<AgentContext, AgentState, AgentEvent> fsm = AgentLoopFsmFactory.create(actions);
        executor = new ReActAgentExecutor(fsm);
    }

    @Test
    @DisplayName("think() in streaming mode emits FINAL_ANSWER_CHUNK events progressively")
    void think_streamingMode_emitsFinalAnswerChunks() {
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
                chatResponse("Part 1 "),
                chatResponse("Part 2")
        ));

        AgentContext ctx = new AgentContext("Explain", "conv-stream-1", Map.of(), 10, Set.of());
        List<AgentStreamEvent> emittedEvents = new ArrayList<>();
        ctx.setStreamSink(emittedEvents::add);
        SpringAgentLoopActions.markStreamingExecution(ctx);

        actions.think(ctx);

        assertThat(ctx.getCurrentTextResponse()).isEqualTo("Part 1 Part 2");
        List<AgentStreamEvent> finalChunks = emittedEvents.stream()
                .filter(event -> event.type() == AgentStreamEvent.EventType.FINAL_ANSWER_CHUNK)
                .toList();
        assertThat(finalChunks).isNotEmpty();
        String chunkedText = finalChunks.stream()
                .map(AgentStreamEvent::content)
                .collect(Collectors.joining());
        assertThat(chunkedText).isEqualTo("Part 1 Part 2");
    }

    @Test
    @DisplayName("think() in streaming mode deduplicates cumulative chunks")
    void think_streamingMode_deduplicatesCumulativeChunks() {
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
                chatResponse("Отлично, "),
                chatResponse("Отлично, давай создадим"),
                chatResponse("Отлично, давай создадим"),
                chatResponse("Отлично, давай создадим новую историю.")
        ));

        AgentContext ctx = new AgentContext("Write a short tale", "conv-stream-2", Map.of(), 10, Set.of());
        List<AgentStreamEvent> emittedEvents = new ArrayList<>();
        ctx.setStreamSink(emittedEvents::add);
        SpringAgentLoopActions.markStreamingExecution(ctx);

        actions.think(ctx);

        assertThat(ctx.getCurrentTextResponse()).isEqualTo("Отлично, давай создадим новую историю.");
        List<AgentStreamEvent> finalChunks = emittedEvents.stream()
                .filter(event -> event.type() == AgentStreamEvent.EventType.FINAL_ANSWER_CHUNK)
                .toList();
        String chunkedText = finalChunks.stream()
                .map(AgentStreamEvent::content)
                .collect(Collectors.joining());
        assertThat(chunkedText).isEqualTo("Отлично, давай создадим новую историю.");
    }

    @Test
    @DisplayName("think() in streaming mode preserves tool calls emitted before terminal chunk")
    void think_streamingMode_preservesEarlyToolCalls() {
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
                chatResponseWithToolCall(
                        ".",
                        "web_search",
                        "{\"query\":\"Quarkus vs Spring Boot benchmarks 2026\"}"
                ),
                chatResponse(".")
        ));

        AgentContext ctx = new AgentContext("Compare Quarkus and Spring Boot", "conv-stream-3", Map.of(), 10, Set.of());
        List<AgentStreamEvent> emittedEvents = new ArrayList<>();
        ctx.setStreamSink(emittedEvents::add);
        SpringAgentLoopActions.markStreamingExecution(ctx);

        actions.think(ctx);

        assertThat(ctx.getCurrentToolName()).isEqualTo("web_search");
        assertThat(ctx.getCurrentToolArguments()).contains("Quarkus vs Spring Boot benchmarks 2026");
        assertThat(ctx.getCurrentTextResponse()).isNull();
        assertThat(emittedEvents.stream()
                .map(AgentStreamEvent::type)
                .toList())
                .doesNotContain(AgentStreamEvent.EventType.FINAL_ANSWER_CHUNK);
    }

    @Test
    @DisplayName("stream state keeps terminal chunk, text, and early tool calls in one snapshot")
    void streamState_keepsTerminalChunkTextAndEarlyToolCallsTogether() {
        ChatResponse firstChunk = chatResponse("Hello ");
        ChatResponse toolCallChunk = chatResponseWithToolCall(
                "Hello world",
                "web_search",
                "{\"query\":\"OpenDaimon stream race\"}"
        );
        ChatResponse terminalChunk = chatResponse("Hello world!");
        AtomicReference<SpringAgentLoopActions.StreamState> streamState =
                new AtomicReference<>(SpringAgentLoopActions.StreamState.empty());

        SpringAgentLoopActions.updateStreamState(streamState, firstChunk);
        SpringAgentLoopActions.updateStreamState(streamState, toolCallChunk);
        SpringAgentLoopActions.updateStreamState(streamState, terminalChunk);

        SpringAgentLoopActions.StreamState state = streamState.get();
        assertThat(state.lastResponse()).isSameAs(terminalChunk);
        assertThat(state.fullText()).isEqualTo("Hello world!");
        assertThat(state.toolCalls())
                .singleElement()
                .satisfies(toolCall -> {
                    assertThat(toolCall.name()).isEqualTo("web_search");
                    assertThat(toolCall.arguments()).contains("OpenDaimon stream race");
                });
    }

    @Test
    @DisplayName("think() in streaming mode merges accumulated text when terminal chunk has no output")
    void think_streamingMode_terminalChunkWithoutOutput_usesAccumulatedText() {
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
                chatResponse("Hello "),
                chatResponseWithoutOutput()
        ));

        AgentContext ctx = new AgentContext("Say hello", "conv-stream-null-terminal", Map.of(), 10, Set.of());
        SpringAgentLoopActions.markStreamingExecution(ctx);

        actions.think(ctx);

        assertThat(ctx.getCurrentTextResponse()).isEqualTo("Hello");
    }

    @Test
    @DisplayName("executeStream() does not duplicate FINAL_ANSWER_CHUNK when final text was already streamed in think")
    void executeStream_streamingFinalAnswer_notDuplicated() {
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
                chatResponse("Hello "),
                chatResponse("Hello world")
        ));

        AgentRequest request = new AgentRequest("Say hello", "conv-stream-dup", Map.of(), 10, Set.of());
        List<AgentStreamEvent> events = executor.executeStream(request).collectList().block();

        assertThat(events).isNotNull();
        List<AgentStreamEvent> finalChunks = events.stream()
                .filter(event -> event.type() == AgentStreamEvent.EventType.FINAL_ANSWER_CHUNK)
                .toList();
        assertThat(finalChunks).isNotEmpty();

        String chunkedText = finalChunks.stream()
                .map(AgentStreamEvent::content)
                .collect(Collectors.joining());
        assertThat(chunkedText).isEqualTo("Hello world");

        AgentStreamEvent terminal = events.getLast();
        assertThat(terminal.type()).isEqualTo(AgentStreamEvent.EventType.FINAL_ANSWER);
        assertThat(terminal.content()).isEqualTo("Hello world");

        List<AgentStreamEvent> metadataEvents = events.stream()
                .filter(event -> event.type() == AgentStreamEvent.EventType.METADATA)
                .toList();
        assertThat(metadataEvents).hasSize(1);

        int metadataIndex = events.indexOf(metadataEvents.getFirst());
        int firstChunkIndex = events.indexOf(finalChunks.getFirst());
        assertThat(metadataIndex).isLessThan(firstChunkIndex);
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
        assertThat(terminalEvent.content()).contains("Reached the iteration limit of 3.");
        assertThat(terminalEvent.content()).doesNotContain("Here is what I found so far");
        assertThat(terminalEvent.content()).doesNotContain("- http_get:");
        assertThat(terminalEvent.content()).doesNotContain("<arg_key>").doesNotContain("</tool_call>");
    }

    @Test
    @DisplayName("executeTool() extracts observation from ToolResponseMessage response data")
    void executeTool_toolResponseMessage_extractsObservation() {
        String mixedPayload = """
                I need the latest benchmark figures.
                web_search
                <arg_key>query</arg_key>
                <arg_value>Quarkus Spring Boot benchmark</arg_value>
                </tool_call>
                """;
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse(mixedPayload));
        when(toolCallingManager.executeToolCalls(any(Prompt.class), any(ChatResponse.class)))
                .thenReturn(toolResultWithToolResponse("web_search", "{\"hits\":[{\"title\":\"Quarkus\"}]}"));

        AgentContext ctx = new AgentContext("Compare benchmarks", "conv-4", Map.of(), 10, Set.of());
        actions.think(ctx);
        actions.executeTool(ctx);

        assertThat(ctx.getToolResult()).isNotNull();
        assertThat(ctx.getToolResult().success()).isTrue();
        assertThat(ctx.getToolResult().result()).contains("\"hits\"");
    }

    @Test
    @DisplayName("executeTool() uses placeholder when ToolResponseMessage is blank")
    void executeTool_blankToolResponse_usesNoToolOutputPlaceholder() {
        String mixedPayload = """
                I will open the URL.
                fetch_url
                <arg_key>url</arg_key>
                <arg_value>https://example.com</arg_value>
                </tool_call>
                """;
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse(mixedPayload));
        when(toolCallingManager.executeToolCalls(any(Prompt.class), any(ChatResponse.class)))
                .thenReturn(toolResultWithToolResponse("fetch_url", ""));

        AgentContext ctx = new AgentContext("Open URL", "conv-5", Map.of(), 10, Set.of());
        actions.think(ctx);
        actions.executeTool(ctx);

        assertThat(ctx.getToolResult()).isNotNull();
        assertThat(ctx.getToolResult().success()).isTrue();
        assertThat(ctx.getToolResult().result()).isEqualTo("(no tool output)");
    }

    @Test
    @DisplayName("handleMaxIterations() performs final synthesis instead of debug step dump")
    void handleMaxIterations_performsFinalSynthesis() {
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse("Final synthesized answer."));

        AgentContext ctx = new AgentContext("Compare benchmarks", "conv-6", Map.of(), 3, Set.of());
        ctx.recordStep(new AgentStepResult(
                0,
                "Need benchmark sources",
                "web_search",
                "{\"query\":\"quarkus vs spring benchmark\"}",
                "{\"hits\":[{\"title\":\"Benchmarks\"}]}",
                Instant.now()
        ));

        actions.handleMaxIterations(ctx);

        assertThat(ctx.getFinalAnswer()).contains("Reached the iteration limit of 3.");
        assertThat(ctx.getFinalAnswer()).contains("Final synthesized answer.");
        assertThat(ctx.getFinalAnswer()).doesNotContain("Here is what I found so far");
        verify(chatModel).call(any(Prompt.class));
    }

    private ChatResponse chatResponse(String text) {
        return ChatResponse.builder()
                .metadata(ChatResponseMetadata.builder().model("test-model").build())
                .generations(List.of(new Generation(new AssistantMessage(text))))
                .build();
    }

    private ChatResponse chatResponseWithoutOutput() {
        return ChatResponse.builder()
                .metadata(ChatResponseMetadata.builder().model("test-model").build())
                .generations(List.of())
                .build();
    }

    private ChatResponse chatResponseWithToolCall(String text, String toolName, String arguments) {
        AssistantMessage output = AssistantMessage.builder()
                .content(text)
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1",
                        "function",
                        toolName,
                        arguments
                )))
                .build();
        return ChatResponse.builder()
                .metadata(ChatResponseMetadata.builder().model("test-model").build())
                .generations(List.of(new Generation(output)))
                .build();
    }

    private ToolExecutionResult toolResultWithObservation(String observation) {
        return ToolExecutionResult.builder()
                .conversationHistory(List.of(new AssistantMessage(observation)))
                .build();
    }

    private ToolExecutionResult toolResultWithToolResponse(String toolName, String responseData) {
        ToolResponseMessage toolResponseMessage = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("tool-call-1", toolName, responseData)))
                .build();
        return ToolExecutionResult.builder()
                .conversationHistory(List.of(toolResponseMessage))
                .build();
    }
}
