package io.github.ngirchev.opendaimon.ai.springai.agent;

import io.github.ngirchev.opendaimon.common.ai.command.AICommand;
import io.github.ngirchev.opendaimon.common.agent.AgentContext;
import io.github.ngirchev.opendaimon.common.agent.AgentStepResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AgentPromptBuilderTest {

    @Test
    void shouldAppendLanguageInstructionWhenMetadataHasLanguageCode() {
        Map<String, String> metadata = Map.of(AICommand.LANGUAGE_CODE_FIELD, "ru");

        String result = AgentPromptBuilder.buildSystemPrompt(metadata);

        assertThat(result)
                .contains("Respond in Russian (ru)")
                .contains("INCLUDING intermediate thoughts");
    }

    @Test
    void shouldReturnBaseSystemPromptWithoutLanguageWhenMetadataIsNull() {
        String result = AgentPromptBuilder.buildSystemPrompt(null);

        assertThat(result)
                .contains("You are an AI agent that solves tasks step by step")
                .contains("you MUST provide all required parameters")
                .doesNotContain("Respond in");
    }

    @Test
    void shouldAppendToolCallingInstructionAlways() {
        String result = AgentPromptBuilder.buildSystemPrompt(Map.of());

        assertThat(result).contains("you MUST provide all required parameters");
    }

    @Test
    void shouldAppendLanguageInstructionToInitialUserMessage() {
        AgentContext ctx = new AgentContext(
                "сделай cat для файла",
                "thread-1",
                Map.of(AICommand.LANGUAGE_CODE_FIELD, "ru"),
                3,
                Set.of());

        String result = AgentPromptBuilder.buildUserMessage(ctx);

        assertThat(result)
                .contains("сделай cat для файла")
                .contains("Answer language: Russian (ru)")
                .contains("even if earlier conversation turns used another language");
    }

    @Test
    void shouldAppendLanguageInstructionToFollowUpUserMessageWithStepHistory() {
        AgentContext ctx = new AgentContext(
                "сделай cat для файла",
                "thread-1",
                Map.of(AICommand.LANGUAGE_CODE_FIELD, "ru"),
                3,
                Set.of());
        ctx.recordStep(new AgentStepResult(
                0,
                "Calling tool",
                "list_directory",
                "{\"path\":\"/app/mcp-filesystem\"}",
                "[FILE] conversation_history.md",
                Instant.now()));

        String result = AgentPromptBuilder.buildUserMessage(ctx);

        assertThat(result)
                .contains("Original task: сделай cat для файла")
                .contains("Previous steps:")
                .contains("Answer language: Russian (ru)");
    }
}
