package io.github.ngirchev.opendaimon.ai.springai.tool;

import io.github.ngirchev.opendaimon.bulkhead.model.UserPriority;
import io.github.ngirchev.opendaimon.ai.springai.config.McpToolAccessProperties;
import io.github.ngirchev.opendaimon.common.ai.command.AICommand;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
public final class ExternalToolCallbacks {

    public static final Set<String> BUILT_IN_TOOL_NAMES = Set.of(
            "web_search",
            "fetch_url",
            "http_get",
            "http_post");

    private ExternalToolCallbacks() {
    }

    public static List<ToolCallback> merge(
            List<ToolCallback> builtInCallbacks,
            ObjectProvider<ToolCallbackProvider> providers,
            boolean externalToolsEnabled) {
        Map<String, ToolCallback> callbacksByName = new LinkedHashMap<>();
        addCallbacks(callbacksByName, builtInCallbacks);
        if (externalToolsEnabled && providers != null) {
            providers.orderedStream()
                    .map(ToolCallbackProvider::getToolCallbacks)
                    .filter(callbacks -> callbacks != null && callbacks.length > 0)
                    .flatMap(Arrays::stream)
                    .forEach(callback -> addCallback(callbacksByName, callback));
        }
        return List.copyOf(callbacksByName.values());
    }

    public static boolean isBuiltInTool(ToolCallback callback) {
        return callback != null
                && callback.getToolDefinition() != null
                && BUILT_IN_TOOL_NAMES.contains(callback.getToolDefinition().name());
    }

    public static boolean isAdmin(Map<String, String> metadata) {
        return UserPriority.ADMIN == resolvePriority(metadata);
    }

    public static UserPriority resolvePriority(Map<String, String> metadata) {
        if (metadata == null) {
            return null;
        }
        String raw = metadata.get(AICommand.USER_PRIORITY_FIELD);
        if (raw == null) {
            return null;
        }
        try {
            return UserPriority.valueOf(raw);
        } catch (IllegalArgumentException e) {
            log.warn("Unknown userPriority in MCP tool access metadata: {}", raw);
            return null;
        }
    }

    public static boolean isAllowedFor(ToolCallback callback, Map<String, String> metadata, McpToolAccessProperties accessProperties) {
        if (isBuiltInTool(callback)) {
            return true;
        }
        if (callback == null || callback.getToolDefinition() == null) {
            return false;
        }
        McpToolAccessProperties properties = accessProperties != null ? accessProperties : new McpToolAccessProperties();
        return properties.isAllowed(callback.getToolDefinition().name(), resolvePriority(metadata));
    }

    private static void addCallbacks(Map<String, ToolCallback> callbacksByName, List<ToolCallback> callbacks) {
        if (callbacks == null || callbacks.isEmpty()) {
            return;
        }
        callbacks.forEach(callback -> addCallback(callbacksByName, callback));
    }

    private static void addCallback(Map<String, ToolCallback> callbacksByName, ToolCallback callback) {
        if (callback == null || callback.getToolDefinition() == null || callback.getToolDefinition().name() == null) {
            return;
        }
        String name = callback.getToolDefinition().name();
        ToolCallback previous = callbacksByName.putIfAbsent(name, callback);
        if (previous != null && previous != callback) {
            log.warn("Ignoring duplicate tool callback '{}'. Keeping the first registered callback.", name);
        }
    }
}
