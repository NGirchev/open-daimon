package io.github.ngirchev.opendaimon.ai.springai.agent;

import io.github.ngirchev.opendaimon.bulkhead.service.PriorityRequestExecutor;
import io.github.ngirchev.opendaimon.common.ai.command.AICommand;
import io.github.ngirchev.opendaimon.common.agent.AgentRequest;
import io.github.ngirchev.opendaimon.common.agent.AgentResult;
import io.github.ngirchev.opendaimon.common.agent.AgentState;
import io.github.ngirchev.opendaimon.common.agent.AgentStreamEvent;
import io.github.ngirchev.opendaimon.common.agent.AgentStreamEvent.EventType;
import io.github.ngirchev.opendaimon.common.agent.AgentStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link SimpleChainExecutor} strips raw {@code <tool_call>} XML
 * markup from both the non-streaming and streaming final answer paths.
 *
 * <p>Some OpenRouter models (e.g. {@code z-ai/glm-4.5v}) emit tool-call XML as
 * a training artifact even when no tools are registered. Left unstripped, the
 * markup leaks into the user-visible final answer.
 */
@ExtendWith(MockitoExtension.class)
class SimpleChainExecutorTest {

    @Mock
    private ChatModel chatModel;

    @Mock
    private ChatMemory chatMemory;

    private SimpleChainExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new SimpleChainExecutor(chatModel, chatMemory);
    }

    @Test
    void shouldStripToolCallTagsFromNonStreamingAnswer() {
        String rawText = "The answer is 42. "
                + "<tool_call><name>foo</name>"
                + "<arg_key>x</arg_key><arg_value>y</arg_value></tool_call>"
                + " Have a great day.";
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse(rawText));

        AgentResult result = executor.execute(request("Does not matter"));

        assertThat(result.terminalState()).isEqualTo(AgentState.COMPLETED);
        assertThat(result.finalAnswer())
                .contains("The answer is 42.")
                .contains("Have a great day.")
                .doesNotContain("<tool_call>")
                .doesNotContain("</tool_call>")
                .doesNotContain("<name>")
                .doesNotContain("<arg_key>")
                .doesNotContain("<arg_value>");
    }

    @Test
    void shouldStripToolCallTagsFromStreamingAnswer() {
        String rawText = "Here is the result: everything is fine. "
                + "<tool_call><name>foo</name>"
                + "<arg_key>x</arg_key><arg_value>y</arg_value></tool_call>";
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse(rawText));

        List<AgentStreamEvent> events = executor.executeStream(request("Does not matter"))
                .collectList()
                .block();

        assertThat(events).isNotNull();
        AgentStreamEvent finalAnswer = events.stream()
                .filter(e -> e.type() == EventType.FINAL_ANSWER)
                .findFirst()
                .orElseThrow(() -> new AssertionError("FINAL_ANSWER event not emitted"));
        assertThat(finalAnswer.content())
                .contains("Here is the result: everything is fine.")
                .doesNotContain("<tool_call>")
                .doesNotContain("</tool_call>")
                .doesNotContain("<name>")
                .doesNotContain("<arg_key>")
                .doesNotContain("<arg_value>");
        assertThat(events)
                .extracting(AgentStreamEvent::type)
                .doesNotContain(EventType.ERROR);
    }

    @Test
    void shouldStripThinkTagsAndToolCallTagsTogether() {
        String rawText = "<think>internal reasoning</think>"
                + "Visible answer stays. "
                + "<tool_call><name>foo</name>"
                + "<arg_key>q</arg_key><arg_value>v</arg_value></tool_call>";
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse(rawText));

        AgentResult result = executor.execute(request("Does not matter"));

        assertThat(result.terminalState()).isEqualTo(AgentState.COMPLETED);
        assertThat(result.finalAnswer())
                .contains("Visible answer stays.")
                .doesNotContain("<think>")
                .doesNotContain("</think>")
                .doesNotContain("internal reasoning")
                .doesNotContain("<tool_call>")
                .doesNotContain("</tool_call>")
                .doesNotContain("<arg_key>")
                .doesNotContain("<arg_value>");
    }

    @Test
    void shouldReturnErrorEventWhenModelReturnsEmpty() {
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse(""));

        List<AgentStreamEvent> events = executor.executeStream(request("Does not matter"))
                .collectList()
                .block();

        assertThat(events).isNotNull();
        assertThat(events)
                .extracting(AgentStreamEvent::type)
                .contains(EventType.ERROR)
                .doesNotContain(EventType.FINAL_ANSWER);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldRouteBothExecuteAndExecuteStreamThroughPriorityRequestExecutor() throws Exception {
        PriorityRequestExecutor mockExecutor = mock(PriorityRequestExecutor.class);
        when(mockExecutor.executeRequest(anyLong(), any(Callable.class)))
                .thenAnswer(inv -> ((Callable<?>) inv.getArgument(1)).call());

        SimpleChainExecutor withExecutor = new SimpleChainExecutor(chatModel, chatMemory, mockExecutor);
        AgentRequest requestWithUserId = new AgentRequest(
                "question", "conv-1",
                Map.of(AICommand.USER_ID_FIELD, "42"),
                5, Set.of(), AgentStrategy.SIMPLE);

        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse("answer"));

        // execute path
        AgentResult executeResult = withExecutor.execute(requestWithUserId);
        assertThat(executeResult.terminalState()).isEqualTo(AgentState.COMPLETED);

        // executeStream path
        withExecutor.executeStream(requestWithUserId).collectList().block();

        verify(mockExecutor, org.mockito.Mockito.atLeast(2))
                .executeRequest(anyLong(), any(Callable.class));
    }

    @Test
    void shouldAttachImageMediaToUserMessageWhenAttachmentsHasImage() {
        // Regression guard: SimpleChainExecutor must mirror SpringAgentLoopActions and pass
        // image attachments as Media on the user message. Otherwise vision-capable models
        // routed through the SIMPLE strategy (e.g. caption-only photo with no tools) reach
        // the LLM with text-only prompt and answer "no image was attached".
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(chatResponse("Looks like a cat."));

        executor.execute(requestWithImage("what is this?", "image/png", new byte[]{1, 2, 3}));

        org.mockito.ArgumentCaptor<org.springframework.ai.chat.prompt.Prompt> captor =
                org.mockito.ArgumentCaptor.forClass(org.springframework.ai.chat.prompt.Prompt.class);
        verify(chatModel).call(captor.capture());
        org.springframework.ai.chat.messages.UserMessage userMsg =
                captor.getValue().getInstructions().stream()
                        .filter(m -> m.getMessageType() == org.springframework.ai.chat.messages.MessageType.USER)
                        .map(org.springframework.ai.chat.messages.UserMessage.class::cast)
                        .findFirst()
                        .orElseThrow();
        assertThat(userMsg.getMedia()).hasSize(1);
        assertThat(userMsg.getMedia().getFirst().getMimeType().toString()).isEqualTo("image/png");
    }

    @Test
    void shouldFallBackToPlainUserMessageWhenAttachmentsEmpty() {
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(chatResponse("Hi."));

        executor.execute(request("ping"));

        org.mockito.ArgumentCaptor<org.springframework.ai.chat.prompt.Prompt> captor =
                org.mockito.ArgumentCaptor.forClass(org.springframework.ai.chat.prompt.Prompt.class);
        verify(chatModel).call(captor.capture());
        org.springframework.ai.chat.messages.UserMessage userMsg =
                captor.getValue().getInstructions().stream()
                        .filter(m -> m.getMessageType() == org.springframework.ai.chat.messages.MessageType.USER)
                        .map(org.springframework.ai.chat.messages.UserMessage.class::cast)
                        .findFirst()
                        .orElseThrow();
        assertThat(userMsg.getMedia()).isEmpty();
    }

    private static AgentRequest request(String task) {
        return new AgentRequest(task, "conv-1", Map.of(), 5, Set.of(), AgentStrategy.SIMPLE);
    }

    private static AgentRequest requestWithImage(String task, String mime, byte[] data) {
        io.github.ngirchev.opendaimon.common.model.Attachment attachment =
                new io.github.ngirchev.opendaimon.common.model.Attachment(
                        "photo/1", mime, "photo.png", data.length,
                        io.github.ngirchev.opendaimon.common.model.AttachmentType.IMAGE, data);
        return new AgentRequest(task, "conv-1", Map.of(), 5, Set.of(),
                AgentStrategy.SIMPLE, List.of(attachment));
    }

    private static ChatResponse chatResponse(String text) {
        AssistantMessage msg = new AssistantMessage(text);
        Generation gen = new Generation(msg);
        return new ChatResponse(List.of(gen));
    }
}
