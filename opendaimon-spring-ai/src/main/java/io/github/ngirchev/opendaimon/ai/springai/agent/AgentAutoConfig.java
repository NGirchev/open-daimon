package io.github.ngirchev.opendaimon.ai.springai.agent;

import io.github.ngirchev.fsm.impl.extended.ExDomainFsm;
import io.github.ngirchev.opendaimon.bulkhead.service.PriorityRequestExecutor;
import io.github.ngirchev.opendaimon.common.config.FeatureToggle;
import io.github.ngirchev.opendaimon.ai.springai.config.SpringAIAutoConfig;
import io.github.ngirchev.opendaimon.ai.springai.retry.SpringAIModelRegistry;
import io.github.ngirchev.opendaimon.ai.springai.tool.HttpApiTool;
import io.github.ngirchev.opendaimon.ai.springai.tool.UrlLivenessChecker;
import io.github.ngirchev.opendaimon.ai.springai.tool.WebTools;
import io.github.ngirchev.opendaimon.common.agent.AgentContext;
import io.github.ngirchev.opendaimon.common.agent.AgentEvent;
import io.github.ngirchev.opendaimon.common.agent.AgentExecutor;
import io.github.ngirchev.opendaimon.common.agent.AgentLoopActions;
import io.github.ngirchev.opendaimon.common.agent.AgentLoopFsmFactory;
import io.github.ngirchev.opendaimon.common.agent.AgentState;
import io.github.ngirchev.opendaimon.common.agent.orchestration.AgentOrchestrator;
import io.github.ngirchev.opendaimon.common.agent.persistence.AgentExecutionRepository;
import org.springframework.ai.chat.memory.ChatMemory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Auto-configuration for the agent framework.
 *
 * <p>Activated when {@code open-daimon.agent.enabled=true}.
 * Registers the ReAct agent executor with FSM-based loop, Spring AI integration,
 * and auto-discovered tools. Long-term memory is provided by
 * {@link ChatMemory} (wired separately in {@link SpringAIAutoConfig}) which
 * already performs rolling summarization of the conversation history — no
 * additional agent-level fact extraction layer is required.
 *
 * <p>All beans use {@code @ConditionalOnMissingBean} so they can be overridden
 * by application-specific configurations.
 */
@Slf4j
@AutoConfiguration
@AutoConfigureAfter(SpringAIAutoConfig.class)
@ConditionalOnProperty(name = FeatureToggle.Module.AGENT_ENABLED, havingValue = "true")
@EnableConfigurationProperties(AgentProperties.class)
public class AgentAutoConfig {

    /**
     * Delegating ChatModel that resolves the best available model from the
     * {@link SpringAIModelRegistry} on each call. Agent executors receive this
     * bean as {@code ChatModel} — same model routing as the normal chat flow.
     */
    @Bean
    @ConditionalOnMissingBean(DelegatingAgentChatModel.class)
    public DelegatingAgentChatModel delegatingAgentChatModel(
            SpringAIModelRegistry registry,
            ObjectProvider<OllamaChatModel> ollamaProvider,
            ObjectProvider<OpenAiChatModel> openAiProvider) {
        return new DelegatingAgentChatModel(registry, ollamaProvider, openAiProvider);
    }

    // --- Agent Loop ---

    @Bean
    @ConditionalOnMissingBean(AgentLoopActions.class)
    public SpringAgentLoopActions agentLoopActions(
            DelegatingAgentChatModel agentChatModel,
            ToolCallingManager toolCallingManager,
            List<ToolCallback> agentToolCallbacks,
            ObjectProvider<ChatMemory> chatMemoryProvider,
            ObjectProvider<UrlLivenessChecker> urlLivenessCheckerProvider,
            PriorityRequestExecutor priorityRequestExecutor,
            AgentProperties agentProperties) {
        Duration streamTimeout = Duration.ofSeconds(agentProperties.getStreamTimeoutSeconds());
        return new SpringAgentLoopActions(
                agentChatModel,
                toolCallingManager,
                agentToolCallbacks,
                chatMemoryProvider.getIfAvailable(),
                streamTimeout,
                urlLivenessCheckerProvider.getIfAvailable(),
                priorityRequestExecutor);
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
            DelegatingAgentChatModel agentChatModel,
            ObjectProvider<ChatMemory> chatMemoryProvider,
            PriorityRequestExecutor priorityRequestExecutor) {
        return new SimpleChainExecutor(agentChatModel, chatMemoryProvider.getIfAvailable(),
                priorityRequestExecutor);
    }

    @Bean
    @ConditionalOnMissingBean
    public PlanAndExecuteAgentExecutor planAndExecuteAgentExecutor(
            DelegatingAgentChatModel agentChatModel,
            ReActAgentExecutor reactExecutor) {
        return new PlanAndExecuteAgentExecutor(agentChatModel, reactExecutor);
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
    @ConditionalOnProperty(name = FeatureToggle.Feature.AGENT_HTTP_API_TOOL_ENABLED, havingValue = "true")
    public HttpApiTool httpApiTool(
            @Qualifier("webToolsWebClient") WebClient webClient) {
        return new HttpApiTool(webClient);
    }
}
