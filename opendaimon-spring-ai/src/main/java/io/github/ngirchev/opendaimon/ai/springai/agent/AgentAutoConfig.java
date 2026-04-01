package io.github.ngirchev.opendaimon.ai.springai.agent;

import io.github.ngirchev.fsm.impl.extended.ExDomainFsm;
import io.github.ngirchev.opendaimon.ai.springai.agent.memory.SemanticAgentMemory;
import io.github.ngirchev.opendaimon.ai.springai.config.SpringAIAutoConfig;
import io.github.ngirchev.opendaimon.ai.springai.tool.HttpApiTool;
import io.github.ngirchev.opendaimon.common.agent.AgentContext;
import io.github.ngirchev.opendaimon.common.agent.AgentEvent;
import io.github.ngirchev.opendaimon.common.agent.AgentCommandHandler;
import io.github.ngirchev.opendaimon.common.agent.AgentExecutor;
import io.github.ngirchev.opendaimon.common.agent.AgentLoopActions;
import io.github.ngirchev.opendaimon.common.agent.AgentLoopFsmFactory;
import io.github.ngirchev.opendaimon.common.agent.AgentState;
import io.github.ngirchev.opendaimon.common.agent.memory.AgentMemory;
import io.github.ngirchev.opendaimon.common.agent.orchestration.AgentOrchestrator;
import io.github.ngirchev.opendaimon.common.agent.persistence.AgentExecutionRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

/**
 * Auto-configuration for the agent framework.
 *
 * <p>Activated when {@code open-daimon.agent.enabled=true}.
 * Registers the ReAct agent executor with FSM-based loop, Spring AI integration,
 * auto-discovered tools, and optional semantic memory.
 *
 * <p>All beans use {@code @ConditionalOnMissingBean} so they can be overridden
 * by application-specific configurations.
 */
@AutoConfiguration
@AutoConfigureAfter(SpringAIAutoConfig.class)
@ConditionalOnProperty(name = "open-daimon.agent.enabled", havingValue = "true")
@EnableConfigurationProperties(AgentProperties.class)
public class AgentAutoConfig {

    // --- Agent Memory ---

    @Bean
    @ConditionalOnMissingBean(AgentMemory.class)
    @ConditionalOnBean(VectorStore.class)
    @ConditionalOnProperty(name = "open-daimon.agent.memory.enabled", havingValue = "true")
    public SemanticAgentMemory semanticAgentMemory(VectorStore vectorStore, AgentProperties properties) {
        return new SemanticAgentMemory(vectorStore, properties.getMemorySimilarityThreshold());
    }

    // --- Agent Loop ---

    @Bean
    @ConditionalOnMissingBean(AgentLoopActions.class)
    public SpringAgentLoopActions agentLoopActions(
            ChatModel chatModel,
            ToolCallingManager toolCallingManager,
            ObjectProvider<List<ToolCallback>> toolCallbacksProvider,
            ObjectProvider<AgentMemory> agentMemoryProvider) {
        List<ToolCallback> callbacks = toolCallbacksProvider.getIfAvailable(List::of);
        AgentMemory memory = agentMemoryProvider.getIfAvailable();
        return new SpringAgentLoopActions(chatModel, toolCallingManager, callbacks, memory);
    }

    @Bean
    @ConditionalOnMissingBean
    public ExDomainFsm<AgentContext, AgentState, AgentEvent> agentLoopFsm(
            AgentLoopActions actions) {
        return AgentLoopFsmFactory.create(actions);
    }

    @Bean
    @ConditionalOnMissingBean(AgentExecutor.class)
    public ReActAgentExecutor reActAgentExecutor(
            ExDomainFsm<AgentContext, AgentState, AgentEvent> agentFsm) {
        return new ReActAgentExecutor(agentFsm);
    }

    // --- Command Handler ---

    @Bean
    @ConditionalOnMissingBean(AgentCommandHandler.class)
    public AgentCommandHandler agentCommandHandler(
            AgentExecutor agentExecutor, AgentProperties properties) {
        return new AgentCommandHandler(agentExecutor, properties.getMaxIterations());
    }

    // --- Orchestration ---

    @Bean
    @ConditionalOnMissingBean(AgentOrchestrator.class)
    public AgentOrchestrator agentOrchestrator(
            AgentExecutor agentExecutor,
            AgentProperties properties,
            ObjectProvider<AgentExecutionRepository> repositoryProvider) {
        DefaultAgentOrchestrator core = new DefaultAgentOrchestrator(
                agentExecutor, properties.getMaxIterations());
        AgentExecutionRepository repository = repositoryProvider.getIfAvailable();
        if (repository != null) {
            return new PersistingAgentOrchestrator(core, repository);
        }
        return core;
    }

    // --- Built-in agent tools ---

    @Bean
    @ConditionalOnMissingBean(HttpApiTool.class)
    @ConditionalOnProperty(name = "open-daimon.agent.tools.http-api.enabled", havingValue = "true", matchIfMissing = true)
    public HttpApiTool httpApiTool(WebClient webClient) {
        return new HttpApiTool(webClient);
    }
}
