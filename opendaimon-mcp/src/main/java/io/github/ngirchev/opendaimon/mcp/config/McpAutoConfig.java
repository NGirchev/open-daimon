package io.github.ngirchev.opendaimon.mcp.config;

import io.github.ngirchev.opendaimon.common.config.FeatureToggle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@Slf4j
@AutoConfiguration
@AutoConfigureAfter(name = "org.springframework.ai.mcp.client.common.autoconfigure.McpClientAutoConfiguration")
@ConditionalOnProperty(name = FeatureToggle.Module.MCP_ENABLED, havingValue = "true", matchIfMissing = true)
public class McpAutoConfig {

    @Bean
    public McpRuntimeMarker mcpRuntimeMarker() {
        log.info("OpenDaimon MCP module enabled. External MCP tools will be consumed when Spring AI MCP clients are configured.");
        return new McpRuntimeMarker();
    }

    public static final class McpRuntimeMarker {
    }
}
