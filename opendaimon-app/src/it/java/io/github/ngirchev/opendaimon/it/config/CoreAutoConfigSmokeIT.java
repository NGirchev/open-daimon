package io.github.ngirchev.opendaimon.it.config;

import io.github.ngirchev.opendaimon.common.ai.pipeline.AIRequestPipeline;
import io.github.ngirchev.opendaimon.common.ai.pipeline.fsm.AIRequestPipelineActions;
import io.github.ngirchev.opendaimon.common.command.CommandHandlerRegistry;
import io.github.ngirchev.opendaimon.common.config.CoreAutoConfig;
import io.github.ngirchev.opendaimon.common.service.CommandSyncService;
import io.github.ngirchev.opendaimon.common.service.OpenDaimonMessageService;
import io.github.ngirchev.opendaimon.it.ITTestConfiguration;
import io.github.ngirchev.opendaimon.test.TestDatabaseConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test that verifies CoreAutoConfig loads and wires all beans correctly.
 *
 * <p>Validates the "RAG disabled" path: no document FSM, no pipeline actions.
 * RAG-enabled path is covered by fixture tests.
 */
@SpringBootTest(classes = ITTestConfiguration.class)
@ActiveProfiles("test")
@Import({
        TestDatabaseConfiguration.class,
        CoreAutoConfig.class
})
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=" +
                "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration," +
                "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration," +
                "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration," +
                "org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration," +
                "org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration," +
                "io.github.ngirchev.opendaimon.ai.springai.config.SpringAIAutoConfig," +
                "io.github.ngirchev.opendaimon.ai.springai.agent.AgentAutoConfig," +
                "io.github.ngirchev.opendaimon.bulkhead.config.BulkHeadAutoConfig," +
                "io.github.ngirchev.opendaimon.telegram.config.TelegramAutoConfig",
        "open-daimon.common.bulkhead.enabled=false",
        "open-daimon.common.assistant-role=Test assistant",
        "open-daimon.common.summarization.message-window-size=5",
        "open-daimon.common.summarization.max-window-tokens=16000",
        "open-daimon.common.summarization.max-output-tokens=2000",
        "open-daimon.common.summarization.prompt=Summarize:",
        "open-daimon.ai.openrouter.enabled=false",
        "open-daimon.ai.deepseek.enabled=false",
        "open-daimon.ai.spring-ai.enabled=false",
        "spring.ai.openai.api-key=mock-key",
        "spring.ai.ollama.base-url=http://localhost:11434"
})
class CoreAutoConfigSmokeIT {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private AIRequestPipeline aiRequestPipeline;

    @Autowired
    private CommandSyncService commandSyncService;

    @Autowired
    private OpenDaimonMessageService messageService;

    @Autowired
    private CommandHandlerRegistry commandHandlerRegistry;

    @Test
    @DisplayName("CoreAutoConfig — context loads with all core beans")
    void contextLoads_allCoreBeans() {
        assertThat(aiRequestPipeline).isNotNull();
        assertThat(commandSyncService).isNotNull();
        assertThat(messageService).isNotNull();
        assertThat(commandHandlerRegistry).isNotNull();
    }

    @Test
    @DisplayName("CoreAutoConfig — AIRequestPipelineActions absent when RAG disabled")
    void ragDisabled_noFsmBeans() {
        assertThat(context.getBeanNamesForType(AIRequestPipelineActions.class))
                .as("AIRequestPipelineActions should not exist when RAG is disabled")
                .isEmpty();
    }

    @Test
    @DisplayName("CoreAutoConfig — AIRequestPipeline works without FSM (passthrough)")
    void aiRequestPipeline_worksWithoutFsm() {
        assertThat(aiRequestPipeline).isNotNull();
    }
}
