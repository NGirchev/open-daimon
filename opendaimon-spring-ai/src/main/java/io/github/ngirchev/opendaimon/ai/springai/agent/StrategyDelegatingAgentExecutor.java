package io.github.ngirchev.opendaimon.ai.springai.agent;

import io.github.ngirchev.opendaimon.common.agent.AgentExecutor;
import io.github.ngirchev.opendaimon.common.agent.AgentRequest;
import io.github.ngirchev.opendaimon.common.agent.AgentResult;
import io.github.ngirchev.opendaimon.common.agent.AgentStrategy;
import io.github.ngirchev.opendaimon.common.agent.AgentStreamEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Delegates to the appropriate executor based on the requested {@link AgentStrategy}.
 *
 * <p>For {@link AgentStrategy#AUTO}, selects the strategy based on context:
 * <ul>
 *   <li>If tools are available → {@link AgentStrategy#REACT}</li>
 *   <li>If no tools → {@link AgentStrategy#SIMPLE}</li>
 * </ul>
 */
@Slf4j
public class StrategyDelegatingAgentExecutor implements AgentExecutor {

    private final ReActAgentExecutor reactExecutor;
    private final SimpleChainExecutor simpleExecutor;
    private final PlanAndExecuteAgentExecutor planAndExecuteExecutor;
    private final List<ToolCallback> availableTools;

    public StrategyDelegatingAgentExecutor(
            ReActAgentExecutor reactExecutor,
            SimpleChainExecutor simpleExecutor,
            PlanAndExecuteAgentExecutor planAndExecuteExecutor,
            List<ToolCallback> availableTools) {
        this.reactExecutor = reactExecutor;
        this.simpleExecutor = simpleExecutor;
        this.planAndExecuteExecutor = planAndExecuteExecutor;
        this.availableTools = availableTools != null ? availableTools : List.of();
    }

    @Override
    public AgentResult execute(AgentRequest request) {
        AgentStrategy strategy = resolveStrategy(request);
        log.info("Agent strategy resolved: requested={}, resolved={}", request.strategy(), strategy);

        return switch (strategy) {
            case SIMPLE -> simpleExecutor.execute(request);
            case PLAN_AND_EXECUTE -> planAndExecuteExecutor.execute(request);
            case REACT, AUTO -> reactExecutor.execute(request);
        };
    }

    @Override
    public Flux<AgentStreamEvent> executeStream(AgentRequest request) {
        AgentStrategy strategy = resolveStrategy(request);
        log.info("Agent stream strategy resolved: requested={}, resolved={}", request.strategy(), strategy);
        log.info("AGENT_TRACE: StrategyDelegatingAgentExecutor.executeStream entered, strategy={}", strategy);

        return switch (strategy) {
            case SIMPLE -> simpleExecutor.executeStream(request);
            case PLAN_AND_EXECUTE -> planAndExecuteExecutor.executeStream(request);
            case REACT, AUTO -> {
                log.info("AGENT_TRACE: delegating to reactExecutor");
                Flux<AgentStreamEvent> flux = reactExecutor.executeStream(request);
                log.info("AGENT_TRACE: reactExecutor returned Flux");
                yield flux;
            }
        };
    }

    private AgentStrategy resolveStrategy(AgentRequest request) {
        AgentStrategy requested = request.strategy();
        if (requested != AgentStrategy.AUTO) {
            return requested;
        }

        // AUTO selection: if tools are available, use ReAct; otherwise simple chain
        if (availableTools.isEmpty()) {
            return AgentStrategy.SIMPLE;
        }
        return AgentStrategy.REACT;
    }
}
