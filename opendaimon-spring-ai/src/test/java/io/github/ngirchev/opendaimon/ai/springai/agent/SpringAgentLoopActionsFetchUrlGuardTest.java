package io.github.ngirchev.opendaimon.ai.springai.agent;

import io.github.ngirchev.opendaimon.bulkhead.model.UserPriority;
import io.github.ngirchev.opendaimon.common.ai.command.AICommand;
import io.github.ngirchev.opendaimon.common.agent.AgentContext;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SpringAgentLoopActionsFetchUrlGuardTest {

    @Test
    void shouldShortCircuitPreviouslyFailedFetchUrl() {
        AtomicInteger calls = new AtomicInteger();
        ToolCallback fetchUrl = fetchUrlCallback(arguments -> {
            calls.incrementAndGet();
            return "HTTP error 403 Forbidden";
        });
        SpringAgentLoopActions actions = actionsWith(fetchUrl);
        AgentContext ctx = context();
        ToolCallback guarded = actions.resolveEffectiveTools(ctx).getFirst();
        String arguments = "{\"url\":\"https://medium.com/blocked-article\"}";

        String first = guarded.call(arguments);
        String second = guarded.call(arguments);

        assertThat(first).isEqualTo("HTTP error 403 Forbidden");
        assertThat(second).startsWith("Error: previously_failed_url");
        assertThat(calls).hasValue(1);
    }

    @Test
    void shouldShortCircuitHostAfterTwoNonTransientFailures() {
        AtomicInteger calls = new AtomicInteger();
        ToolCallback fetchUrl = fetchUrlCallback(arguments -> {
            calls.incrementAndGet();
            return "HTTP error 403 Forbidden";
        });
        SpringAgentLoopActions actions = actionsWith(fetchUrl);
        AgentContext ctx = context();
        ToolCallback guarded = actions.resolveEffectiveTools(ctx).getFirst();

        String first = guarded.call("{\"url\":\"https://medium.com/one\"}");
        String second = guarded.call("{\"url\":\"https://medium.com/two\"}");
        String third = guarded.call("{\"url\":\"https://medium.com/three\"}");

        assertThat(first).isEqualTo("HTTP error 403 Forbidden");
        assertThat(second).isEqualTo("HTTP error 403 Forbidden");
        assertThat(third).startsWith("Error: host_unreadable");
        assertThat(calls).hasValue(2);
    }

    @Test
    void shouldNotPoisonUrlOrHostAfterSuccessfulFetch() {
        AtomicInteger calls = new AtomicInteger();
        ToolCallback fetchUrl = fetchUrlCallback(arguments -> {
            calls.incrementAndGet();
            return "Fetched page content";
        });
        SpringAgentLoopActions actions = actionsWith(fetchUrl);
        AgentContext ctx = context();
        ToolCallback guarded = actions.resolveEffectiveTools(ctx).getFirst();
        String arguments = "{\"url\":\"https://example.com/article\"}";

        String first = guarded.call(arguments);
        String second = guarded.call(arguments);

        assertThat(first).isEqualTo("Fetched page content");
        assertThat(second).isEqualTo("Fetched page content");
        assertThat(calls).hasValue(2);
    }

    @Test
    void shouldExposeExternalToolsOnlyForAdminContext() {
        ToolCallback builtIn = fetchUrlCallback(arguments -> "ok");
        ToolCallback external = toolCallback("read_file", arguments -> "secret");
        ToolCallback sharedExternal = toolCallback("weather_lookup", arguments -> "sunny");
        SpringAgentLoopActions actions = actionsWith(builtIn, external);

        assertThat(actions.resolveEffectiveTools(context()))
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("fetch_url");
        assertThat(actions.resolveEffectiveTools(adminContext()))
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("fetch_url", "read_file");

        SpringAgentLoopActions sharedActions = actionsWith(builtIn, sharedExternal);
        assertThat(sharedActions.resolveEffectiveTools(regularContext()))
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("fetch_url", "weather_lookup");
    }

    private static SpringAgentLoopActions actionsWith(ToolCallback... callbacks) {
        return new SpringAgentLoopActions(
                mock(ChatModel.class),
                mock(ToolCallingManager.class),
                List.of(callbacks),
                null,
                Duration.ofSeconds(30));
    }

    private static AgentContext context() {
        return new AgentContext("task", "conversation", Map.of(), 5, Set.of());
    }

    private static AgentContext adminContext() {
        return new AgentContext("task", "conversation",
                Map.of(AICommand.USER_PRIORITY_FIELD, UserPriority.ADMIN.name()), 5, Set.of());
    }

    private static AgentContext regularContext() {
        return new AgentContext("task", "conversation",
                Map.of(AICommand.USER_PRIORITY_FIELD, UserPriority.REGULAR.name()), 5, Set.of());
    }

    private static ToolCallback fetchUrlCallback(Function<String, String> behavior) {
        return toolCallback("fetch_url", behavior);
    }

    private static ToolCallback toolCallback(String name, Function<String, String> behavior) {
        ToolDefinition definition = ToolDefinition.builder()
                .name(name)
                .description(name + " description")
                .inputSchema("{\"type\":\"object\"}")
                .build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                return behavior.apply(toolInput);
            }
        };
    }
}
