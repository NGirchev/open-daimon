package io.github.ngirchev.opendaimon.ai.springai.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentPromptBuilderTest {

    @Test
    void shouldContainAntiHallucinationDirectiveWhenBuildingSystemPrompt() {
        String systemPrompt = AgentPromptBuilder.buildSystemPrompt();

        assertThat(systemPrompt)
                .as("ReAct system prompt must forbid URL fabrication")
                .contains("NEVER fabricate URLs");
        assertThat(systemPrompt)
                .as("ReAct system prompt must require byte-for-byte URL copying from tool results")
                .contains("copy it byte-for-byte");
    }
}
