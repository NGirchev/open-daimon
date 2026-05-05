package io.github.ngirchev.opendaimon.ai.springai.tool;

import io.github.ngirchev.opendaimon.ai.springai.config.McpToolAccessProperties;
import io.github.ngirchev.opendaimon.bulkhead.model.UserPriority;
import io.github.ngirchev.opendaimon.common.ai.tool.ExternalToolAccessContext;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SpringAIExternalToolCatalogServiceTest {

    @Test
    void shouldListAllowedExternalToolsOnly() {
        ObjectProvider<ToolCallbackProvider> providers = providers(
                toolCallback("web_search"),
                toolCallback("read_file"),
                toolCallback("weather_lookup"));
        SpringAIExternalToolCatalogService service = new SpringAIExternalToolCatalogService(
                providers, true, new McpToolAccessProperties());

        var tools = service.listAvailableTools(new ExternalToolAccessContext(1L, UserPriority.REGULAR));

        assertThat(tools)
                .extracting("name")
                .containsExactly("weather_lookup");
    }

    @Test
    void shouldListAdminFilesystemTools() {
        ObjectProvider<ToolCallbackProvider> providers = providers(toolCallback("read_file"));
        SpringAIExternalToolCatalogService service = new SpringAIExternalToolCatalogService(
                providers, true, new McpToolAccessProperties());

        var tools = service.listAvailableTools(new ExternalToolAccessContext(1L, UserPriority.ADMIN));

        assertThat(tools)
                .extracting("name")
                .containsExactly("read_file");
    }

    @Test
    void shouldReturnEmptyWhenExternalToolsDisabled() {
        ObjectProvider<ToolCallbackProvider> providers = providers(toolCallback("weather_lookup"));
        SpringAIExternalToolCatalogService service = new SpringAIExternalToolCatalogService(
                providers, false, new McpToolAccessProperties());

        var tools = service.listAvailableTools(new ExternalToolAccessContext(1L, UserPriority.ADMIN));

        assertThat(tools).isEmpty();
    }

    private static ObjectProvider<ToolCallbackProvider> providers(ToolCallback... callbacks) {
        ToolCallbackProvider provider = mock(ToolCallbackProvider.class);
        when(provider.getToolCallbacks()).thenReturn(callbacks);
        ObjectProvider<ToolCallbackProvider> providers = mock(ObjectProvider.class);
        when(providers.orderedStream()).thenReturn(Stream.of(provider));
        return providers;
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
