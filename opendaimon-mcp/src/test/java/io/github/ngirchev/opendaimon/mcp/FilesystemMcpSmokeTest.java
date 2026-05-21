package io.github.ngirchev.opendaimon.mcp;

import io.github.ngirchev.opendaimon.mcp.config.McpAutoConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.mcp.client.common.autoconfigure.McpClientAutoConfiguration;
import org.springframework.ai.mcp.client.common.autoconfigure.McpToolCallbackAutoConfiguration;
import org.springframework.ai.mcp.client.common.autoconfigure.StdioTransportAutoConfiguration;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FilesystemMcpSmokeTest {

    @TempDir
    Path sandbox;

    @Test
    void filesystemMcpListsFilesFromSandbox() throws Exception {
        Files.writeString(sandbox.resolve("alpha.txt"), "alpha");
        Files.createDirectory(sandbox.resolve("nested"));
        Files.writeString(sandbox.resolve("nested/beta.txt"), "beta");

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        McpAutoConfig.class,
                        StdioTransportAutoConfiguration.class,
                        McpClientAutoConfiguration.class,
                        McpToolCallbackAutoConfiguration.class))
                .withPropertyValues(
                        "open-daimon.mcp.enabled=true",
                        "spring.ai.mcp.client.enabled=true",
                        "spring.ai.mcp.client.type=SYNC",
                        "spring.ai.mcp.client.request-timeout=30s",
                        "spring.ai.mcp.client.stdio.connections.filesystem.command=npx",
                        "spring.ai.mcp.client.stdio.connections.filesystem.args[0]=-y",
                        "spring.ai.mcp.client.stdio.connections.filesystem.args[1]=@modelcontextprotocol/server-filesystem",
                        "spring.ai.mcp.client.stdio.connections.filesystem.args[2]=" + sandbox.toAbsolutePath())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ToolCallbackProvider provider = context.getBean(ToolCallbackProvider.class);
                    List<ToolCallback> callbacks = Arrays.asList(provider.getToolCallbacks());
                    List<String> toolNames = callbacks.stream()
                            .map(callback -> callback.getToolDefinition().name())
                            .toList();
                    System.out.println("Filesystem MCP tools: " + toolNames);

                    ToolCallback listDirectory = callbacks.stream()
                            .filter(callback -> callback.getToolDefinition().name().endsWith("list_directory"))
                            .findFirst()
                            .orElseThrow(() -> new AssertionError("list_directory tool not found: " + toolNames));

                    String result = listDirectory.call("{\"path\":\"" + sandbox.toAbsolutePath() + "\"}");
                    System.out.println("Filesystem MCP list_directory result: " + result);

                    assertThat(result).contains("alpha.txt", "nested");
                });
    }
}
