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
}
