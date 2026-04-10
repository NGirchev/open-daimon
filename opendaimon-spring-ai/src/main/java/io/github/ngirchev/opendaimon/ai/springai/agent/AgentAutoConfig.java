package io.github.ngirchev.opendaimon.ai.springai.agent;

import io.github.ngirchev.fsm.impl.extended.ExDomainFsm;
import io.github.ngirchev.opendaimon.ai.springai.agent.memory.CompositeAgentMemory;
import io.github.ngirchev.opendaimon.ai.springai.agent.memory.FactExtractor;
import io.github.ngirchev.opendaimon.ai.springai.agent.memory.SemanticAgentMemory;
import io.github.ngirchev.opendaimon.ai.springai.config.SpringAIAutoConfig;
import io.github.ngirchev.opendaimon.ai.springai.tool.HttpApiTool;
import io.github.ngirchev.opendaimon.ai.springai.tool.WebTools;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.Arrays;
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
@Slf4j
@AutoConfiguration
@AutoConfigureAfter(SpringAIAutoConfig.class)
@ConditionalOnProperty(name = "open-daimon.agent.enabled", havingValue = "true")
@EnableConfigurationProperties(AgentProperties.class)
public class AgentAutoConfig {

    /**
     * Resolves the ChatModel to use for agent operations.
     * Prefers OpenAI if available, falls back to Ollama.
     */
    private static ChatModel resolveAgentChatModel(
            ObjectProvider<OpenAiChatModel> openAiProvider,
            ObjectProvider<OllamaChatModel> ollamaProvider) {
        ChatModel model = openAiProvider.getIfAvailable();
        if (model != null) {
            log.info("Agent framework using OpenAI chat model");
            return model;
        }
        model = ollamaProvider.getIfAvailable();
        if (model != null) {
            log.info("Agent framework using Ollama chat model");
            return model;
        }
        throw new IllegalStateException("No ChatModel (OpenAI or Ollama) available for agent framework");
    }

    // --- Agent Memory ---

    @Bean
    @ConditionalOnMissingBean(SemanticAgentMemory.class)
    @ConditionalOnBean(VectorStore.class)
    @ConditionalOnProperty(name = "open-daimon.agent.memory.enabled", havingValue = "true")
    public SemanticAgentMemory semanticAgentMemory(VectorStore vectorStore, AgentProperties properties) {
        return new SemanticAgentMemory(vectorStore, properties.getMemorySimilarityThreshold());
    }

    /**
     * When multiple {@link AgentMemory} beans exist, combines them via {@link CompositeAgentMemory}.
     * Marked {@code @Primary} so other beans (FactExtractor, AgentLoopActions) get the composite.
     */
    @Bean
    @Primary
    @ConditionalOnMissingBean(CompositeAgentMemory.class)
    @ConditionalOnBean(SemanticAgentMemory.class)
    public AgentMemory compositeAgentMemory(List<AgentMemory> memories) {
        if (memories.size() == 1) {
            return memories.getFirst();
        }
        log.info("Agent memory: composing {} memory sources", memories.size());
        return new CompositeAgentMemory(memories);
    }

    // --- Agent Loop ---

    @Bean
    @ConditionalOnMissingBean(FactExtractor.class)
    @ConditionalOnBean(AgentMemory.class)
    public FactExtractor factExtractor(
            ObjectProvider<OpenAiChatModel> openAiProvider,
            ObjectProvider<OllamaChatModel> ollamaProvider,
            AgentMemory agentMemory) {
        return new FactExtractor(resolveAgentChatModel(openAiProvider, ollamaProvider), agentMemory);
    }

    // --- Agent Loop ---

    @Bean
    @ConditionalOnMissingBean(AgentLoopActions.class)
    public SpringAgentLoopActions agentLoopActions(
            ObjectProvider<OpenAiChatModel> openAiProvider,
            ObjectProvider<OllamaChatModel> ollamaProvider,
            ToolCallingManager toolCallingManager,
            List<ToolCallback> agentToolCallbacks,
            ObjectProvider<AgentMemory> agentMemoryProvider,
            ObjectProvider<FactExtractor> factExtractorProvider) {
        ChatModel chatModel = resolveAgentChatModel(openAiProvider, ollamaProvider);
        AgentMemory memory = agentMemoryProvider.getIfAvailable();
        FactExtractor extractor = factExtractorProvider.getIfAvailable();
        return new SpringAgentLoopActions(chatModel, toolCallingManager, agentToolCallbacks, memory, extractor);
    }

    @Bean("agentLoopFsm")
    public ExDomainFsm<AgentContext, AgentState, AgentEvent> agentLoopFsm(
            AgentLoopActions actions) {
        return AgentLoopFsmFactory.create(actions);
    }

    @Bean
    public ReActAgentExecutor reActAgentExecutor(
            @org.springframework.beans.factory.annotation.Qualifier("agentLoopFsm")
            ExDomainFsm<AgentContext, AgentState, AgentEvent> agentFsm) {
        return new ReActAgentExecutor(agentFsm);
    }

    @Bean
    @ConditionalOnMissingBean
    public SimpleChainExecutor simpleChainExecutor(
            ObjectProvider<OpenAiChatModel> openAiProvider,
            ObjectProvider<OllamaChatModel> ollamaProvider) {
        return new SimpleChainExecutor(resolveAgentChatModel(openAiProvider, ollamaProvider));
    }

    @Bean
    @ConditionalOnMissingBean
    public PlanAndExecuteAgentExecutor planAndExecuteAgentExecutor(
            ObjectProvider<OpenAiChatModel> openAiProvider,
            ObjectProvider<OllamaChatModel> ollamaProvider,
            ReActAgentExecutor reactExecutor) {
        return new PlanAndExecuteAgentExecutor(
                resolveAgentChatModel(openAiProvider, ollamaProvider), reactExecutor);
    }

    @Primary
    @Bean
    public StrategyDelegatingAgentExecutor strategyDelegatingAgentExecutor(
            ReActAgentExecutor reactExecutor,
            SimpleChainExecutor simpleExecutor,
            PlanAndExecuteAgentExecutor planAndExecuteExecutor,
            List<ToolCallback> agentToolCallbacks) {
        return new StrategyDelegatingAgentExecutor(reactExecutor, simpleExecutor, planAndExecuteExecutor, agentToolCallbacks);
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

    // --- Agent tool callbacks ---

    @Bean
    @ConditionalOnMissingBean(name = "agentToolCallbacks")
    public List<ToolCallback> agentToolCallbacks(
            ObjectProvider<WebTools> webToolsProvider,
            ObjectProvider<HttpApiTool> httpApiToolProvider) {
        List<ToolCallback> callbacks = new ArrayList<>();
        webToolsProvider.ifAvailable(tools ->
                callbacks.addAll(Arrays.asList(ToolCallbacks.from(tools))));
        httpApiToolProvider.ifAvailable(tool ->
                callbacks.addAll(Arrays.asList(ToolCallbacks.from(tool))));
        log.info("Agent tool callbacks registered: {}", callbacks.size());
        return List.copyOf(callbacks);
    }

    // --- Built-in agent tools ---

    @Bean
    @ConditionalOnMissingBean(HttpApiTool.class)
    @ConditionalOnProperty(name = "open-daimon.agent.tools.http-api.enabled", havingValue = "true")
    public HttpApiTool httpApiTool(WebClient webClient) {
        return new HttpApiTool(webClient);
    }
}
