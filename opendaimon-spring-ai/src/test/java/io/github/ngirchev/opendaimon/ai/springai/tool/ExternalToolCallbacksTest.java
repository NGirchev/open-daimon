package io.github.ngirchev.opendaimon.ai.springai.tool;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExternalToolCallbacksTest {

    @Test
    void merge_addsExternalProviderCallbacksAfterBuiltIns() {
        ToolCallback builtIn = toolCallback("fetch_url");
        ToolCallback external = toolCallback("mcp_search");
        List<ToolCallback> callbacks = ExternalToolCallbacks.merge(
                List.of(builtIn),
                providers(provider(external)),
                true);

        assertEquals(List.of("fetch_url", "mcp_search"), toolNames(callbacks));
    }

    @Test
    void merge_ignoresExternalProviderCallbacksWhenDisabled() {
        ToolCallback builtIn = toolCallback("fetch_url");
        ToolCallback external = toolCallback("mcp_search");
        List<ToolCallback> callbacks = ExternalToolCallbacks.merge(
                List.of(builtIn),
                providers(provider(external)),
                false);

        assertEquals(List.of("fetch_url"), toolNames(callbacks));
    }

    @Test
    void merge_keepsFirstCallbackWhenNamesDuplicate() {
        ToolCallback builtIn = toolCallback("fetch_url");
        ToolCallback duplicate = toolCallback("fetch_url");
        List<ToolCallback> callbacks = ExternalToolCallbacks.merge(
                List.of(builtIn),
                providers(provider(duplicate)),
                true);

        assertEquals(1, callbacks.size());
        assertEquals(builtIn, callbacks.getFirst());
    }

    private static List<String> toolNames(List<ToolCallback> callbacks) {
        return callbacks.stream()
                .map(callback -> callback.getToolDefinition().name())
                .toList();
    }

    private static ToolCallbackProvider provider(ToolCallback... callbacks) {
        ToolCallbackProvider provider = mock(ToolCallbackProvider.class);
        when(provider.getToolCallbacks()).thenReturn(callbacks);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ToolCallbackProvider> providers(ToolCallbackProvider... providers) {
        ObjectProvider<ToolCallbackProvider> objectProvider = mock(ObjectProvider.class);
        when(objectProvider.orderedStream()).thenReturn(Stream.of(providers));
        return objectProvider;
    }

    private static ToolCallback toolCallback(String name) {
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
                return "ok";
            }
        };
    }
}
