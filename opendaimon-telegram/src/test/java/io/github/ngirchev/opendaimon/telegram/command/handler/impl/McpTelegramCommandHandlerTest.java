package io.github.ngirchev.opendaimon.telegram.command.handler.impl;

import io.github.ngirchev.opendaimon.bulkhead.model.UserPriority;
import io.github.ngirchev.opendaimon.bulkhead.service.IUserPriorityService;
import io.github.ngirchev.opendaimon.common.ai.tool.ExternalToolAccessContext;
import io.github.ngirchev.opendaimon.common.ai.tool.ExternalToolCatalogService;
import io.github.ngirchev.opendaimon.common.ai.tool.ExternalToolDescriptor;
import io.github.ngirchev.opendaimon.common.ai.tool.ExternalToolSourceDescriptor;
import io.github.ngirchev.opendaimon.common.service.MessageLocalizationService;
import io.github.ngirchev.opendaimon.telegram.TelegramBot;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommand;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommandType;
import io.github.ngirchev.opendaimon.telegram.service.TypingIndicatorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class McpTelegramCommandHandlerTest {

    private static final long USER_ID = 2L;
    private static final long CHAT_ID = 42L;

    @Mock private ObjectProvider<TelegramBot> telegramBotProvider;
    @Mock private TypingIndicatorService typingIndicatorService;
    @Mock private MessageLocalizationService messageLocalizationService;
    @Mock private ObjectProvider<ExternalToolCatalogService> catalogProvider;
    @Mock private ExternalToolCatalogService catalogService;
    @Mock private IUserPriorityService userPriorityService;

    private McpTelegramCommandHandler handler;

    @BeforeEach
    void setUp() {
        when(messageLocalizationService.getMessage(eq("telegram.command.mcp.desc"), anyString()))
                .thenReturn("/mcp - list available MCP tools");
        when(messageLocalizationService.getMessage(eq("telegram.mcp.unavailable"), anyString()))
                .thenReturn("MCP tools are not configured.");
        when(messageLocalizationService.getMessage(eq("telegram.mcp.empty"), anyString(), any()))
                .thenAnswer(inv -> "No MCP tools are available for " + inv.getArgument(2));
        when(messageLocalizationService.getMessage(eq("telegram.mcp.header"), anyString(), any()))
                .thenAnswer(inv -> "Available MCP tools for " + inv.getArgument(2) + ":");
        handler = new McpTelegramCommandHandler(
                telegramBotProvider,
                typingIndicatorService,
                messageLocalizationService,
                catalogProvider,
                userPriorityService);
    }

    @Test
    void canHandleMcpCommand() {
        assertThat(handler.canHandle(command(TelegramCommand.MCP))).isTrue();
        assertThat(handler.canHandle(command(TelegramCommand.MODEL))).isFalse();
    }

    @Test
    void shouldReturnUnavailableWhenCatalogMissing() {
        when(catalogProvider.getIfAvailable()).thenReturn(null);

        String response = handler.handleInner(command(TelegramCommand.MCP));

        assertThat(response).isEqualTo("MCP tools are not configured.");
    }

    @Test
    void shouldReturnEmptyWhenUserHasNoAllowedTools() {
        when(catalogProvider.getIfAvailable()).thenReturn(catalogService);
        when(userPriorityService.getUserPriority(USER_ID)).thenReturn(UserPriority.REGULAR);
        when(catalogService.listAvailableToolSources(new ExternalToolAccessContext(USER_ID, UserPriority.REGULAR)))
                .thenReturn(List.of());

        String response = handler.handleInner(command(TelegramCommand.MCP));

        assertThat(response).contains("REGULAR");
    }

    @Test
    void shouldRenderAvailableToolsEscaped() {
        when(catalogProvider.getIfAvailable()).thenReturn(catalogService);
        when(userPriorityService.getUserPriority(USER_ID)).thenReturn(UserPriority.ADMIN);
        when(catalogService.listAvailableToolSources(new ExternalToolAccessContext(USER_ID, UserPriority.ADMIN)))
                .thenReturn(List.of(new ExternalToolSourceDescriptor("filesystem",
                        List.of(new ExternalToolDescriptor("read_file", "Read <files>")))));

        String response = handler.handleInner(command(TelegramCommand.MCP));

        assertThat(response)
                .contains("Available MCP tools for ADMIN:")
                .contains("• filesystem - read_file")
                .doesNotContain("Read &lt;files&gt;");
    }

    @Test
    void shouldRenderToolNamesOnly() {
        when(catalogProvider.getIfAvailable()).thenReturn(catalogService);
        when(userPriorityService.getUserPriority(USER_ID)).thenReturn(UserPriority.ADMIN);
        when(catalogService.listAvailableToolSources(new ExternalToolAccessContext(USER_ID, UserPriority.ADMIN)))
                .thenReturn(List.of(new ExternalToolSourceDescriptor("custom-server",
                        List.of(new ExternalToolDescriptor(
                                "custom_tool",
                                "First second third fourth fifth sixth seventh eighth ninth")))));

        String response = handler.handleInner(command(TelegramCommand.MCP));

        assertThat(response)
                .contains("• custom-server - custom_tool")
                .doesNotContain("First second");
    }

    @Test
    void shouldKeepResponseBelowTelegramLimit() {
        when(catalogProvider.getIfAvailable()).thenReturn(catalogService);
        when(userPriorityService.getUserPriority(USER_ID)).thenReturn(UserPriority.ADMIN);
        List<ExternalToolDescriptor> tools = java.util.stream.IntStream.range(0, 500)
                .mapToObj(i -> new ExternalToolDescriptor(
                        "very_long_external_tool_name_" + i,
                        "Very long description ".repeat(100),
                        "server"))
                .toList();
        when(catalogService.listAvailableToolSources(new ExternalToolAccessContext(USER_ID, UserPriority.ADMIN)))
                .thenReturn(List.of(new ExternalToolSourceDescriptor("server", tools)));

        String response = handler.handleInner(command(TelegramCommand.MCP));

        assertThat(response)
                .hasSizeLessThan(4096)
                .contains("...");
    }

    @Test
    void shouldProvideStartMenuDescription() {
        assertThat(handler.getSupportedCommandText("en"))
                .isEqualTo("/mcp - list available MCP tools");
    }

    private static TelegramCommand command(String commandText) {
        TelegramCommand command = new TelegramCommand(
                USER_ID,
                CHAT_ID,
                new TelegramCommandType(commandText),
                mock(Update.class));
        command.languageCode("en");
        return command;
    }
}
