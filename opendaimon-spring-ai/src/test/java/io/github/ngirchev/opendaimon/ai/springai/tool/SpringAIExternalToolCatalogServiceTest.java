package io.github.ngirchev.opendaimon.ai.springai.tool;

import io.github.ngirchev.opendaimon.ai.springai.config.McpToolAccessProperties;
import io.github.ngirchev.opendaimon.bulkhead.model.UserPriority;
import io.github.ngirchev.opendaimon.common.ai.tool.ExternalToolAccessContext;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
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
                providers, mcpClients(), List.of(), true, new McpToolAccessProperties());

        var tools = service.listAvailableTools(new ExternalToolAccessContext(1L, UserPriority.REGULAR));

        assertThat(tools)
                .extracting("name")
                .containsExactly("weather_lookup");
    }

    @Test
    void shouldListAdminFilesystemTools() {
        ObjectProvider<ToolCallbackProvider> providers = providers(toolCallback("read_file"));
        SpringAIExternalToolCatalogService service = new SpringAIExternalToolCatalogService(
                providers, mcpClients(), List.of(), true, new McpToolAccessProperties());

        var tools = service.listAvailableTools(new ExternalToolAccessContext(1L, UserPriority.ADMIN));

        assertThat(tools)
                .extracting("name")
                .containsExactly("read_file");
    }

    @Test
    void shouldUseMcpServerIdentifierAsSourceName() {
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.getServerInfo()).thenReturn(new McpSchema.Implementation("filesystem-server", "1.0.0"));
        when(client.getClientInfo()).thenReturn(new McpSchema.Implementation("open-daimon", "1.0.0"));

        String sourceName = SpringAIExternalToolCatalogService.resolveSourceName(client);

        assertThat(sourceName).isEqualTo("filesystem-server");
    }

    @Test
    void shouldFallbackToMcpClientIdentifierWhenServerIdentifierMissing() {
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.getClientInfo()).thenReturn(new McpSchema.Implementation("filesystem", "1.0.0"));

        String sourceName = SpringAIExternalToolCatalogService.resolveSourceName(client);

        assertThat(sourceName).isEqualTo("filesystem");
    }

    @Test
    void shouldUseSingleConfiguredConnectionNameAsSourceFallback() {
        ObjectProvider<ToolCallbackProvider> providers = providers(toolCallback("read_file"));
        SpringAIExternalToolCatalogService service = new SpringAIExternalToolCatalogService(
                providers, mcpClients(), List.of("filesystem"), true, new McpToolAccessProperties());

        var tools = service.listAvailableTools(new ExternalToolAccessContext(1L, UserPriority.ADMIN));

        assertThat(tools)
                .extracting("sourceName")
                .containsExactly("filesystem");
    }

    @Test
    void shouldUseConfiguredConnectionNamesByMcpClientOrder() {
        ObjectProvider<ToolCallbackProvider> providers = providers(
                toolCallback("read_file"),
                toolCallback("weather_lookup"));
        SpringAIExternalToolCatalogService service = new SpringAIExternalToolCatalogService(
                providers,
                mcpClients(
                        mcpClient("read_file"),
                        mcpClient("weather_lookup")),
                List.of("filesystem", "weather"),
                true,
                new McpToolAccessProperties());

        var tools = service.listAvailableTools(new ExternalToolAccessContext(1L, UserPriority.ADMIN));

        assertThat(tools)
                .extracting("name", "sourceName")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("read_file", "filesystem"),
                        org.assertj.core.groups.Tuple.tuple("weather_lookup", "weather"));
    }

    @Test
    void shouldReturnEmptyWhenExternalToolsDisabled() {
        ObjectProvider<ToolCallbackProvider> providers = providers(toolCallback("weather_lookup"));
        SpringAIExternalToolCatalogService service = new SpringAIExternalToolCatalogService(
                providers, mcpClients(), List.of(), false, new McpToolAccessProperties());

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

    private static ObjectProvider<McpSyncClient> mcpClients() {
        return mcpClients(new McpSyncClient[0]);
    }

    private static ObjectProvider<McpSyncClient> mcpClients(McpSyncClient... mcpClients) {
        ObjectProvider<McpSyncClient> clients = mock(ObjectProvider.class);
        when(clients.orderedStream()).thenReturn(Stream.of(mcpClients));
        return clients;
    }

    private static McpSyncClient mcpClient(String toolName) {
        McpSyncClient client = mock(McpSyncClient.class);
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(toolName)
                .description(toolName + " description")
                .inputSchema(new McpSchema.JsonSchema("object", java.util.Map.of(), List.of(), false, null, null))
                .build();
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(tool), null));
        return client;
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
