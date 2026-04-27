package io.github.ngirchev.opendaimon.telegram.service;

import io.github.ngirchev.opendaimon.common.agent.AgentStreamEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TelegramAgentStreamModelTest {

    @Test
    @DisplayName("should keep partial answer as status candidate until final answer confirms it")
    void shouldKeepPartialAnswerAsStatusCandidateUntilFinalAnswerConfirmsIt() {
        TelegramAgentStreamModel model = new TelegramAgentStreamModel(false, false);

        model.apply(AgentStreamEvent.partialAnswer("Quick reply", 0));

        assertThat(model.statusHtml()).contains("<i>Quick reply</i>");
        assertThat(model.hasConfirmedAnswer()).isFalse();

        model.apply(AgentStreamEvent.finalAnswer("Quick reply", 0));

        assertThat(model.hasConfirmedAnswer()).isTrue();
        assertThat(model.answerHtml()).contains("Quick reply");
    }

    @Test
    @DisplayName("should fold pre-tool partial text into status and clear candidate when a tool call arrives")
    void shouldFoldPreToolPartialTextIntoStatusAndClearCandidateWhenToolCallArrives() {
        TelegramAgentStreamModel model = new TelegramAgentStreamModel(false, true);

        model.apply(AgentStreamEvent.partialAnswer("I should search first.", 0));
        model.apply(AgentStreamEvent.toolCall("web_search", "{\"query\":\"telegram limits\"}", 0));
        model.apply(AgentStreamEvent.observation("result body", 0));

        assertThat(model.statusHtml())
                .contains("<i>I should search first.</i>")
                .contains("🔧 <b>Tool:</b>")
                .contains("telegram limits")
                .contains("📋 Tool result received");
        assertThat(model.hasCandidateText()).isFalse();
        assertThat(model.hasConfirmedAnswer()).isFalse();
        assertThat(model.isToolCallSeenThisIteration()).isTrue();
    }

    @Test
    @DisplayName("should clear trailing partial overlay from status when answer is confirmed")
    void shouldClearTrailingPartialOverlayFromStatusWhenAnswerIsConfirmed() {
        // Reproduces the "На ос" duplication bug: agent finishes a tool round, streams a
        // partial of the final answer into the status overlay, then FINAL_ANSWER arrives.
        // The status must not retain the partial fragment alongside the new answer message.
        TelegramAgentStreamModel model = new TelegramAgentStreamModel(false, false);
        model.apply(AgentStreamEvent.thinking(0));
        model.apply(AgentStreamEvent.toolCall("web_search", "{\"query\":\"tickets\"}", 0));
        model.apply(AgentStreamEvent.observation("ok", 0));
        model.apply(AgentStreamEvent.thinking(1));
        model.apply(AgentStreamEvent.partialAnswer("На ос", 1));

        assertThat(model.statusHtml()).contains("<i>На ос</i>");

        model.apply(AgentStreamEvent.finalAnswer("На основе поиска…", 1));

        assertThat(model.statusHtml())
                .as("partial overlay must be stripped once the answer is confirmed")
                .doesNotContain("На ос")
                .doesNotContain("<i></i>");
        assertThat(model.isStatusDirty())
                .as("flushFinal must re-render the cleaned status to Telegram")
                .isTrue();
        assertThat(model.answerHtml()).contains("На основе поиска");
    }

    @Test
    @DisplayName("should keep history intact when only an overlay-free terminal arrives")
    void shouldKeepHistoryIntactWhenOnlyAnOverlayFreeTerminalArrives() {
        // No partial chunks were ever streamed in the final iteration — the trailing line
        // is the "💭 Thinking..." marker, not an overlay. confirmAnswer must NOT touch it.
        TelegramAgentStreamModel model = new TelegramAgentStreamModel(false, false);
        model.apply(AgentStreamEvent.thinking(0));
        model.apply(AgentStreamEvent.toolCall("web_search", "{\"query\":\"x\"}", 0));
        model.apply(AgentStreamEvent.observation("ok", 0));
        model.apply(AgentStreamEvent.thinking(1));

        String beforeConfirm = model.statusHtml();
        model.apply(AgentStreamEvent.finalAnswer("Final answer", 1));

        assertThat(model.statusHtml())
                .as("status without partial overlay must survive confirmation untouched")
                .isEqualTo(beforeConfirm);
    }

    @Test
    @DisplayName("should not clear status when post-tool partial was never rendered as overlay")
    void shouldNotClearStatusWhenPostToolPartialWasNeverRenderedAsOverlay() {
        // Once a tool call was seen in the iteration, partial chunks are no longer
        // rendered as a status overlay. The terminal cleanup must therefore keep the
        // completed tool and observation transcript intact.
        TelegramAgentStreamModel model = new TelegramAgentStreamModel(false, false);
        model.apply(AgentStreamEvent.toolCall("web_search", "{\"query\":\"x\"}", 0));
        model.apply(AgentStreamEvent.observation("ok", 0));
        model.apply(AgentStreamEvent.partialAnswer("Final after tool", 0));

        String beforeConfirm = model.statusHtml();
        assertThat(beforeConfirm)
                .contains("🔧 <b>Tool:</b>")
                .contains("📋 Tool result received")
                .doesNotContain("Final after tool");

        model.apply(AgentStreamEvent.finalAnswer("Final after tool", 0));

        assertThat(model.statusHtml()).isEqualTo(beforeConfirm);
        assertThat(model.answerHtml()).contains("Final after tool");
    }

    @Test
    @DisplayName("should leave completion marker when status was entirely overlay")
    void shouldLeaveCompletionMarkerWhenStatusWasEntirelyOverlay() {
        // First-iteration straight-to-answer: partial chunk overwrites the initial
        // "💭 Thinking..." line, then FINAL arrives. Stripping leaves an empty status —
        // Telegram rejects empty edits, so the model substitutes a "✅" marker.
        TelegramAgentStreamModel model = new TelegramAgentStreamModel(false, false);
        model.apply(AgentStreamEvent.partialAnswer("Quick", 0));

        assertThat(model.statusHtml()).contains("<i>Quick</i>");

        model.apply(AgentStreamEvent.finalAnswer("Quick reply", 0));

        assertThat(model.statusHtml())
                .doesNotContain("Quick")
                .isEqualTo("✅");
    }

    @Test
    @DisplayName("should treat same event sequence provider-neutrally for OpenRouter and Ollama")
    void shouldTreatSameEventSequenceProviderNeutrallyForOpenRouterAndOllama() {
        TelegramAgentStreamModel openRouter = replayProviderNeutralSequence();
        TelegramAgentStreamModel ollama = replayProviderNeutralSequence();

        assertThat(openRouter.statusHtml()).isEqualTo(ollama.statusHtml());
        assertThat(openRouter.answerHtml()).isEqualTo(ollama.answerHtml());
    }

    private TelegramAgentStreamModel replayProviderNeutralSequence() {
        TelegramAgentStreamModel model = new TelegramAgentStreamModel(false, false);
        model.apply(AgentStreamEvent.thinking(0));
        model.apply(AgentStreamEvent.partialAnswer("Need a tool.", 0));
        model.apply(AgentStreamEvent.toolCall("web_search", "{\"query\":\"x\"}", 0));
        model.apply(AgentStreamEvent.observation("ok", 0));
        model.apply(AgentStreamEvent.thinking(1));
        model.apply(AgentStreamEvent.partialAnswer("Final text", 1));
        model.apply(AgentStreamEvent.finalAnswer("Final text", 1));
        return model;
    }

    @Test
    @DisplayName("should render bold markdown inside the partial-answer overlay")
    void shouldRenderBoldMarkdownInPartialOverlay() {
        // Reproducer: in production a partial chunk like "...платформа - **SoldOut Tickets**..."
        // surfaced in the status overlay with literal asterisks because TelegramHtmlEscaper
        // only escapes <, >, & and leaves * untouched. The overlay must run the escaped
        // text through AIUtils.convertEscapedMarkdownToHtml so **bold** becomes <b>bold</b>.
        TelegramAgentStreamModel model = new TelegramAgentStreamModel(false, false);

        model.apply(AgentStreamEvent.partialAnswer("Платформа - **SoldOut Tickets** работает", 0));

        assertThat(model.statusHtml())
                .contains("<b>SoldOut Tickets</b>")
                .doesNotContain("**SoldOut");
    }

    @Test
    @DisplayName("should not orphan markdown markers when overlay tail is truncated mid-pair")
    void shouldNotOrphanMarkdownMarkersWhenTailIsTruncated() {
        // When candidateEscaped exceeds CANDIDATE_TAIL_LIMIT (400) and the raw cut would land
        // inside a `**bold**` pair, the orphan `**` survives the markdown regex. The overlay
        // must shift the cut forward to the next word boundary so no half-pair leaks through.
        TelegramAgentStreamModel model = new TelegramAgentStreamModel(false, false);
        String filler = "А".repeat(390);

        model.apply(AgentStreamEvent.partialAnswer(filler + " **SoldOut Tickets** хвост", 0));

        assertThat(model.statusHtml()).doesNotContain("**");
    }

    @Test
    @DisplayName("should start overlay tail on a word boundary, not in the middle of a word")
    void shouldStartOverlayTailOnWordBoundary() {
        // Reproducer for the visible "ае платформа..." regression: the raw byte cut at
        // length-400 landed inside «универсальная», leaving a "ае" fragment. The fix walks
        // the cut forward to the next whitespace so the overlay always starts on a whole word.
        TelegramAgentStreamModel model = new TelegramAgentStreamModel(false, false);
        String filler = "слово ".repeat(80);

        model.apply(AgentStreamEvent.partialAnswer(filler + "финал", 0));

        assertThat(model.statusHtml()).doesNotContainPattern("<i>(?:ло|ов)во ");
    }
}
