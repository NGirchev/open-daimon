package io.github.ngirchev.opendaimon.ai.springai.agent;

import io.github.ngirchev.opendaimon.common.ai.command.AICommand;
import org.junit.jupiter.api.Test;

import java.util.Map;

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
}
