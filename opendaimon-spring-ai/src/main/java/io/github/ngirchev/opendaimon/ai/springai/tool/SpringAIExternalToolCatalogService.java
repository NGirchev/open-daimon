package io.github.ngirchev.opendaimon.ai.springai.tool;

import io.github.ngirchev.opendaimon.ai.springai.config.McpToolAccessProperties;
import io.github.ngirchev.opendaimon.common.ai.command.AICommand;
import io.github.ngirchev.opendaimon.common.ai.tool.ExternalToolAccessContext;
import io.github.ngirchev.opendaimon.common.ai.tool.ExternalToolCatalogService;
import io.github.ngirchev.opendaimon.common.ai.tool.ExternalToolDescriptor;
import io.modelcontextprotocol.client.McpSyncClient;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SpringAIExternalToolCatalogService implements ExternalToolCatalogService {

    private final ObjectProvider<ToolCallbackProvider> externalToolCallbackProviders;
    private final ObjectProvider<McpSyncClient> mcpSyncClients;
    private final List<String> configuredMcpConnectionNames;
    private final boolean externalToolsEnabled;
    private final McpToolAccessProperties mcpToolAccessProperties;

    public SpringAIExternalToolCatalogService(
            ObjectProvider<ToolCallbackProvider> externalToolCallbackProviders,
            ObjectProvider<McpSyncClient> mcpSyncClients,
            List<String> configuredMcpConnectionNames,
            boolean externalToolsEnabled,
            McpToolAccessProperties mcpToolAccessProperties) {
        this.externalToolCallbackProviders = externalToolCallbackProviders;
        this.mcpSyncClients = mcpSyncClients;
        this.configuredMcpConnectionNames = configuredMcpConnectionNames != null
                ? List.copyOf(configuredMcpConnectionNames) : List.of();
        this.externalToolsEnabled = externalToolsEnabled;
        this.mcpToolAccessProperties = mcpToolAccessProperties != null
                ? mcpToolAccessProperties : new McpToolAccessProperties();
    }

    @Override
    public List<ExternalToolDescriptor> listAvailableTools(ExternalToolAccessContext context) {
        if (!externalToolsEnabled || externalToolCallbackProviders == null || context == null) {
            return List.of();
        }
        Map<String, ExternalToolDescriptor> toolsByName = new LinkedHashMap<>();
        Map<String, String> sourceByToolName = resolveSourceByToolName();
        Map<String, String> metadata = context.userPriority() != null
                ? Map.of(AICommand.USER_PRIORITY_FIELD, context.userPriority().name())
                : Map.of();
        externalToolCallbackProviders.orderedStream()
                .map(ToolCallbackProvider::getToolCallbacks)
                .filter(callbacks -> callbacks != null && callbacks.length > 0)
                .flatMap(Arrays::stream)
                .filter(callback -> !ExternalToolCallbacks.isBuiltInTool(callback))
                .filter(callback -> ExternalToolCallbacks.isAllowedFor(callback, metadata, mcpToolAccessProperties))
                .forEach(callback -> addDescriptor(toolsByName, callback, sourceByToolName));
        return List.copyOf(toolsByName.values());
    }

    private Map<String, String> resolveSourceByToolName() {
        if (mcpSyncClients == null) {
            return Map.of();
        }
        Map<String, String> sourceByToolName = new LinkedHashMap<>();
        List<McpSyncClient> clients = mcpSyncClients.orderedStream().toList();
        for (int i = 0; i < clients.size(); i++) {
            addClientTools(sourceByToolName, clients.get(i), configuredSourceName(i, clients.size()));
        }
        return sourceByToolName;
    }

    private String configuredSourceName(int clientIndex, int clientCount) {
        if (configuredMcpConnectionNames.size() == clientCount) {
            return configuredMcpConnectionNames.get(clientIndex);
        }
        return singleConfiguredSourceName();
    }

    private String singleConfiguredSourceName() {
        return configuredMcpConnectionNames.size() == 1 ? configuredMcpConnectionNames.getFirst() : null;
    }

    private static void addClientTools(Map<String, String> sourceByToolName, McpSyncClient client, String configuredSourceName) {
        if (client == null) {
            return;
        }
        String resolvedSourceName = configuredSourceName != null ? configuredSourceName : resolveSourceName(client);
        if (resolvedSourceName == null || resolvedSourceName.isBlank()) {
            return;
        }
        ToolCallback[] callbacks = new SyncMcpToolCallbackProvider(client).getToolCallbacks();
        if (callbacks == null) {
            return;
        }
        Arrays.stream(callbacks)
                .map(ToolCallback::getToolDefinition)
                .filter(definition -> definition != null && definition.name() != null && !definition.name().isBlank())
                .forEach(definition -> sourceByToolName.putIfAbsent(definition.name(), resolvedSourceName));
    }

    static String resolveSourceName(McpSyncClient client) {
        if (client.getServerInfo() != null && client.getServerInfo().name() != null
                && !client.getServerInfo().name().isBlank()) {
            return client.getServerInfo().name();
        }
        return client.getClientInfo() != null ? client.getClientInfo().name() : null;
    }

    private void addDescriptor(Map<String, ExternalToolDescriptor> toolsByName, ToolCallback callback,
                               Map<String, String> sourceByToolName) {
        if (callback == null || callback.getToolDefinition() == null) {
            return;
        }
        ToolDefinition definition = callback.getToolDefinition();
        if (definition.name() == null || definition.name().isBlank()) {
            return;
        }
        toolsByName.putIfAbsent(definition.name(),
                new ExternalToolDescriptor(definition.name(), definition.description(), resolveSourceName(definition.name(), sourceByToolName)));
    }

    private String resolveSourceName(String toolName, Map<String, String> sourceByToolName) {
        String sourceName = sourceByToolName.get(toolName);
        if (sourceName != null && !sourceName.isBlank()) {
            return sourceName;
        }
        return singleConfiguredSourceName();
    }
}
