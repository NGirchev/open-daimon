package io.github.ngirchev.opendaimon.it.config;

import io.github.ngirchev.opendaimon.ai.springai.agent.AgentAutoConfig;
import io.github.ngirchev.opendaimon.ai.springai.agent.PlanAndExecuteAgentExecutor;
import io.github.ngirchev.opendaimon.ai.springai.agent.ReActAgentExecutor;
import io.github.ngirchev.opendaimon.ai.springai.agent.SimpleChainExecutor;
import io.github.ngirchev.opendaimon.ai.springai.agent.SpringAgentLoopActions;
import io.github.ngirchev.opendaimon.ai.springai.agent.StrategyDelegatingAgentExecutor;
import io.github.ngirchev.opendaimon.ai.springai.tool.HttpApiTool;
import io.github.ngirchev.opendaimon.common.agent.AgentExecutor;
import io.github.ngirchev.opendaimon.common.agent.AgentLoopActions;
import io.github.ngirchev.opendaimon.common.agent.orchestration.AgentOrchestrator;
import io.github.ngirchev.opendaimon.it.ITTestConfiguration;
import io.github.ngirchev.opendaimon.test.AbstractContainerIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import io.github.ngirchev.opendaimon.ai.springai.retry.SpringAIModelRegistry;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Smoke test that verifies AgentAutoConfig loads and wires all agent beans correctly.
 *
 * <p>Uses a mock ChatModel to avoid requiring real OpenAI/Ollama connections.
 * Verifies the full agent bean graph: loop actions → FSM → executors → handler → orchestrator.
 */
@SpringBootTest(classes = ITTestConfiguration.class)
@ActiveProfiles("test")
@Import({
        AgentAutoConfigSmokeIT.MockChatModelConfig.class,
        AgentAutoConfig.class
})
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=" +
                "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration," +
                "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration," +
                "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration," +
                "org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration," +
                "org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration," +
                "org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration," +
                "org.springframework.ai.model.ollama.autoconfigure.OllamaChatAutoConfiguration," +
                "org.springframework.ai.model.ollama.autoconfigure.OllamaEmbeddingAutoConfiguration," +
                "io.github.ngirchev.opendaimon.ai.springai.config.SpringAIAutoConfig," +
                "io.github.ngirchev.opendaimon.common.storage.config.StorageAutoConfig," +
                "io.github.ngirchev.opendaimon.bulkhead.config.BulkHeadAutoConfig," +
                "io.github.ngirchev.opendaimon.telegram.config.TelegramAutoConfig",
        "open-daimon.agent.enabled=true",
        "open-daimon.agent.max-iterations=5",
        "open-daimon.agent.stream-timeout-seconds=60",
        "open-daimon.common.bulkhead.enabled=false",
        "spring.ai.openai.api-key=mock-key",
        "spring.ai.ollama.base-url=http://localhost:11434"
})
class AgentAutoConfigSmokeIT extends AbstractContainerIT {

    @TestConfiguration
    static class MockChatModelConfig {

        @Bean
        public OpenAiChatModel openAiChatModel() {
            return mock(OpenAiChatModel.class);
        }

        @Bean
        public ToolCallingManager toolCallingManager() {
            return mock(ToolCallingManager.class);
        }

        @Bean
        public SpringAIModelRegistry springAIModelRegistry() {
            return mock(SpringAIModelRegistry.class);
        }

        @Bean
        public ChatMemory chatMemory() {
            return mock(ChatMemory.class);
        }
    }

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("AgentAutoConfig — context loads with all agent beans")
    void contextLoads_allAgentBeans() {
        assertThat(context.getBean(AgentLoopActions.class))
                .isInstanceOf(SpringAgentLoopActions.class);
        assertThat(context.getBean(ReActAgentExecutor.class)).isNotNull();
        assertThat(context.getBean(SimpleChainExecutor.class)).isNotNull();
        assertThat(context.getBean(PlanAndExecuteAgentExecutor.class)).isNotNull();
    }

    @Test
    @DisplayName("AgentAutoConfig — primary executor is StrategyDelegatingAgentExecutor")
    void primaryExecutor_isStrategyDelegating() {
        AgentExecutor executor = context.getBean(AgentExecutor.class);
        assertThat(executor).isInstanceOf(StrategyDelegatingAgentExecutor.class);
    }

    @Test
    @DisplayName("AgentAutoConfig — AgentOrchestrator registered (without persistence)")
    void agentOrchestrator_registeredWithoutPersistence() {
        AgentOrchestrator orchestrator = context.getBean(AgentOrchestrator.class);
        assertThat(orchestrator).isNotNull();
        assertThat(orchestrator.getClass().getSimpleName()).isEqualTo("DefaultAgentOrchestrator");
    }

    @Test
    @DisplayName("AgentAutoConfig — HttpApiTool NOT registered by default (opt-in)")
    void httpApiTool_notRegisteredByDefault() {
        assertThat(context.getBeanNamesForType(HttpApiTool.class))
                .as("HttpApiTool should not be registered without explicit opt-in")
                .isEmpty();
    }
}
