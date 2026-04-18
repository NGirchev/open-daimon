package io.github.ngirchev.opendaimon.ai.springai.agent;

import io.github.ngirchev.opendaimon.common.agent.AgentContext;
import io.github.ngirchev.opendaimon.common.agent.AgentStepResult;
import io.github.ngirchev.opendaimon.common.ai.command.AICommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SpringAgentLoopActions#handleMaxIterations} now makes a tool-less LLM call to
 * ask the model to summarize the step history and answer directly; on failure it falls
 * back to the StringBuilder digest so the user still receives a non-empty final answer.
 */
class SpringAgentLoopActionsMaxIterationsTest {

    private ChatModel chatModel;
    private SpringAgentLoopActions actions;
    private AgentContext ctx;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        ToolCallingManager toolCallingManager = mock(ToolCallingManager.class);
        actions = new SpringAgentLoopActions(
                chatModel, toolCallingManager, List.of(), null);
        ctx = new AgentContext("What's the BTC price?", "conv-1", Map.of(), 5, Set.of());
        ctx.recordStep(new AgentStepResult(
                0, "I should search", "web_search",
                "{\"q\":\"btc\"}", "BTC is $50,000", Instant.now()));
    }

    @Test
    void shouldCallChatModelWithoutToolsAndSetFinalAnswer() {
        ChatResponse response = new ChatResponse(List.of(
                new Generation(new AssistantMessage("BTC is currently $50,000 based on the search result."))
        ));
        when(chatModel.call(any(Prompt.class))).thenReturn(response);

        actions.handleMaxIterations(ctx);

        assertThat(ctx.getFinalAnswer())
                .isEqualTo("BTC is currently $50,000 based on the search result.");
        verify(chatModel).call(any(Prompt.class));
    }

    @Test
    void shouldFallBackToStringBuilderWhenSummaryLlmCallFails() {
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("LLM unavailable"));

        actions.handleMaxIterations(ctx);

        // Fallback digest is non-null and references the step history + iteration limit.
        String answer = ctx.getFinalAnswer();
        assertThat(answer).isNotBlank();
        assertThat(answer).contains("maximum number of iterations");
        assertThat(answer).contains("web_search");
        assertThat(answer).contains("BTC is $50,000");
    }

    @Test
    void shouldFallBackWhenLlmReturnsBlankContent() {
        // Empty content → callSummaryModelWithoutTools throws IllegalStateException,
        // caller catches it and falls back to the StringBuilder digest.
        ChatResponse emptyResponse = new ChatResponse(List.of(
                new Generation(new AssistantMessage(""))
        ));
        when(chatModel.call(any(Prompt.class))).thenReturn(emptyResponse);

        actions.handleMaxIterations(ctx);

        assertThat(ctx.getFinalAnswer()).contains("maximum number of iterations");
    }

    @Test
    void shouldFallBackWhenSummaryReturnsUnclosedThinkOnly() {
        // Repro for the Telegram bug (20:49–20:51): summary model returned only an unclosed
        // "<think>…" block with no prose. stripThinkTags sees "<think>" without a matching
        // "</think>" → returns text up to start of tag → empty. stripToolCallTags keeps
        // it empty → callSummaryModelWithoutTools throws IllegalStateException →
        // handleMaxIterations catches → must invoke buildFallbackSummary so
        // ctx.getFinalAnswer() is non-blank and Telegram can render the MAX_ITERATIONS
        // response. If this test fails, the fallback chain is broken somewhere between
        // buildFallbackSummary and ctx.setFinalAnswer.
        ChatResponse openThinkOnly = new ChatResponse(List.of(
                new Generation(new AssistantMessage("<think>reasoning but no closing tag and no prose"))
        ));
        when(chatModel.call(any(Prompt.class))).thenReturn(openThinkOnly);

        actions.handleMaxIterations(ctx);

        String answer = ctx.getFinalAnswer();
        assertThat(answer)
                .as("MAX_ITERATIONS must always produce a non-empty fallback answer, "
                        + "even when the summary model returns only an unclosed <think> block")
                .isNotBlank();
        assertThat(answer).startsWith("I reached the maximum number of iterations");
    }

    @Test
    void shouldIncludeLanguageInstructionInSummaryPromptWhenLanguageCodeInMetadata() {
        AgentContext ruCtx = new AgentContext(
                "What's the BTC price?", "conv-ru",
                Map.of(AICommand.LANGUAGE_CODE_FIELD, "ru"), 5, Set.of());
        ruCtx.recordStep(new AgentStepResult(
                0, "I should search", "web_search",
                "{\"q\":\"btc\"}", "BTC is $50,000", Instant.now()));

        ChatResponse response = new ChatResponse(List.of(
                new Generation(new AssistantMessage("BTC стоит $50,000."))
        ));
        when(chatModel.call(any(Prompt.class))).thenReturn(response);

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        actions.handleMaxIterations(ruCtx);
        verify(chatModel).call(promptCaptor.capture());

        Prompt capturedPrompt = promptCaptor.getValue();
        boolean hasRussianInstruction = capturedPrompt.getInstructions().stream()
                .filter(m -> m instanceof SystemMessage)
                .map(m -> ((SystemMessage) m).getText())
                .anyMatch(text -> text.contains("Russian"));
        assertThat(hasRussianInstruction)
                .as("SystemMessage must contain 'Russian' language instruction when languageCode=ru")
                .isTrue();
    }
}
