package io.github.ngirchev.opendaimon.ai.springai.tool;

import io.github.ngirchev.opendaimon.ai.springai.config.McpToolAccessProperties;
import io.github.ngirchev.opendaimon.common.ai.command.AICommand;
import io.github.ngirchev.opendaimon.common.ai.tool.ExternalToolAccessContext;
import io.github.ngirchev.opendaimon.common.ai.tool.ExternalToolCatalogService;
import io.github.ngirchev.opendaimon.common.ai.tool.ExternalToolDescriptor;
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
    private final boolean externalToolsEnabled;
    private final McpToolAccessProperties mcpToolAccessProperties;

    public SpringAIExternalToolCatalogService(
            ObjectProvider<ToolCallbackProvider> externalToolCallbackProviders,
            boolean externalToolsEnabled,
            McpToolAccessProperties mcpToolAccessProperties) {
        this.externalToolCallbackProviders = externalToolCallbackProviders;
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
        Map<String, String> metadata = context.userPriority() != null
                ? Map.of(AICommand.USER_PRIORITY_FIELD, context.userPriority().name())
                : Map.of();
        externalToolCallbackProviders.orderedStream()
                .map(ToolCallbackProvider::getToolCallbacks)
                .filter(callbacks -> callbacks != null && callbacks.length > 0)
                .flatMap(Arrays::stream)
                .filter(callback -> !ExternalToolCallbacks.isBuiltInTool(callback))
                .filter(callback -> ExternalToolCallbacks.isAllowedFor(callback, metadata, mcpToolAccessProperties))
                .forEach(callback -> addDescriptor(toolsByName, callback));
        return List.copyOf(toolsByName.values());
    }

    private static void addDescriptor(Map<String, ExternalToolDescriptor> toolsByName, ToolCallback callback) {
        if (callback == null || callback.getToolDefinition() == null) {
            return;
        }
        ToolDefinition definition = callback.getToolDefinition();
        if (definition.name() == null || definition.name().isBlank()) {
            return;
        }
        toolsByName.putIfAbsent(definition.name(),
                new ExternalToolDescriptor(definition.name(), definition.description()));
    }
}
