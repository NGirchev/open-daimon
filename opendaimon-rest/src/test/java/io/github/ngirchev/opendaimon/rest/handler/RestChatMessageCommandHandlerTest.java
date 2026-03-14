package io.github.ngirchev.opendaimon.rest.handler;

import io.github.ngirchev.opendaimon.bulkhead.exception.AccessDeniedException;
import io.github.ngirchev.opendaimon.common.ai.AIGateways;
import io.github.ngirchev.opendaimon.common.ai.ModelCapabilities;
import io.github.ngirchev.opendaimon.common.ai.command.AICommand;
import io.github.ngirchev.opendaimon.common.ai.factory.AICommandFactoryRegistry;
import io.github.ngirchev.opendaimon.common.ai.response.AIResponse;
import io.github.ngirchev.opendaimon.common.command.ICommand;
import io.github.ngirchev.opendaimon.common.exception.UserMessageTooLongException;
import io.github.ngirchev.opendaimon.common.model.OpenDaimonMessage;
import io.github.ngirchev.opendaimon.common.model.AssistantRole;
import io.github.ngirchev.opendaimon.common.model.ConversationThread;
import io.github.ngirchev.opendaimon.common.model.RequestType;
import io.github.ngirchev.opendaimon.common.service.AIGateway;
import io.github.ngirchev.opendaimon.common.service.OpenDaimonMessageService;
import io.github.ngirchev.opendaimon.common.service.AIGatewayRegistry;
import io.github.ngirchev.opendaimon.rest.dto.ChatRequestDto;
import io.github.ngirchev.opendaimon.rest.model.RestUser;
import io.github.ngirchev.opendaimon.common.service.MessageLocalizationService;
import io.github.ngirchev.opendaimon.rest.service.RestMessageService;
import io.github.ngirchev.opendaimon.rest.service.RestUserService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static io.github.ngirchev.opendaimon.common.ai.LlmParamNames.CHOICES;
import static io.github.ngirchev.opendaimon.common.ai.LlmParamNames.CONTENT;
import static io.github.ngirchev.opendaimon.common.ai.LlmParamNames.MESSAGE;
import static io.github.ngirchev.opendaimon.common.ai.LlmParamNames.MODEL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RestChatMessageCommandHandlerTest {

    @Mock
    private RestMessageService restMessageService;
    @Mock
    private RestUserService restUserService;
    @Mock
    private OpenDaimonMessageService messageService;
    @Mock
    private AIGatewayRegistry aiGatewayRegistry;
    @Mock
    private AICommandFactoryRegistry aiCommandFactoryRegistry;
    @Mock
    private RestChatHandlerSupport support;
    @Mock
    private MessageLocalizationService messageLocalizationService;
    @Mock
    private HttpServletRequest request;

    private RestChatMessageCommandHandler handler;

    private RestUser user;
    private OpenDaimonMessage userMessage;
    private ConversationThread thread;
    private AssistantRole assistantRole;
    private AICommand aiCommand;
    private AIGateway aiGateway;

    @BeforeEach
    void setUp() {
        handler = new RestChatMessageCommandHandler(
                restMessageService, restUserService, messageService,
                aiGatewayRegistry, aiCommandFactoryRegistry, support);
        user = new RestUser();
        user.setId(1L);
        user.setEmail("user@test.com");
        thread = new ConversationThread();
        thread.setThreadKey("thread-1");
        thread.setUser(user);
        assistantRole = new AssistantRole();
        assistantRole.setId(5L);
        assistantRole.setVersion(1);
        assistantRole.setContent("You are helpful.");
        userMessage = new OpenDaimonMessage();
        userMessage.setUser(user);
        userMessage.setThread(thread);
        userMessage.setAssistantRole(assistantRole);
        aiCommand = createMockAICommand(Set.of(ModelCapabilities.CHAT));
        aiGateway = mock(AIGateway.class);
    }

    @Nested
    @DisplayName("canHandle")
    class CanHandle {

        @Test
        void whenRestChatCommandWithMessageType_returnsTrue() {
            RestChatCommand command = new RestChatCommand(
                    new ChatRequestDto("hi", null, null, null), RestChatCommandType.MESSAGE, request, 1L);
            assertTrue(handler.canHandle(command));
        }

        @Test
        void whenRestChatCommandWithStreamType_returnsFalse() {
            RestChatCommand command = new RestChatCommand(
                    new ChatRequestDto("hi", null, null, null), RestChatCommandType.STREAM, request, 1L);
            assertFalse(handler.canHandle(command));
        }

        @Test
        void whenCommandNotRestChatCommand_returnsFalse() {
            @SuppressWarnings("unchecked")
            ICommand<RestChatCommandType> other = mock(ICommand.class);
            when(other.commandType()).thenReturn(RestChatCommandType.MESSAGE);
            assertFalse(handler.canHandle(other));
        }

        @Test
        void whenCommandTypeNull_returnsFalse() {
            RestChatCommand command = new RestChatCommand(
                    new ChatRequestDto("hi", null, null, null), null, request, 1L);
            assertFalse(handler.canHandle(command));
        }
    }

    @Nested
    @DisplayName("priority")
    class Priority {

        @Test
        void returnsZero() {
            assertEquals(0, handler.priority());
        }
    }

    @Nested
    @DisplayName("handle")
    class Handle {

        @Test
        void whenSuccess_returnsResponseAndSavesAssistantMessage() {
            RestChatCommand command = new RestChatCommand(
                    new ChatRequestDto("Hello", null, null, "user@test.com"), RestChatCommandType.MESSAGE, request, 1L);
            when(restUserService.findById(1L)).thenReturn(Optional.of(user));
            when(restMessageService.saveUserMessage(eq(user), eq("Hello"), eq(RequestType.TEXT), eq(null), eq(request)))
                    .thenReturn(userMessage);
            when(aiCommandFactoryRegistry.createCommand(eq(command), any())).thenReturn(aiCommand);
            when(aiGatewayRegistry.getSupportedAiGateways(aiCommand)).thenReturn(List.of(aiGateway));
            AIResponse aiResponse = mockAIResponseWithContent("AI reply");
            when(aiGateway.generateResponse(aiCommand)).thenReturn(aiResponse);

            String result = handler.handle(command);

            assertEquals("AI reply", result);
            verify(messageService).saveAssistantMessage(eq(user), eq("AI reply"), any(), eq("You are helpful."), any(), any());
            verify(messageService).updateMessageStatus(any(), eq(io.github.ngirchev.opendaimon.common.model.ResponseStatus.SUCCESS));
        }

        @Test
        void whenResponseContentEmpty_savesErrorAndThrowsRuntimeException() {
            RestChatCommand command = new RestChatCommand(
                    new ChatRequestDto("Hi", null, null, null), RestChatCommandType.MESSAGE, request, 1L);
            when(restUserService.findById(1L)).thenReturn(Optional.of(user));
            when(restMessageService.saveUserMessage(any(), any(), any(), any(), any())).thenReturn(userMessage);
            when(aiCommandFactoryRegistry.createCommand(eq(command), any())).thenReturn(aiCommand);
            when(aiGatewayRegistry.getSupportedAiGateways(aiCommand)).thenReturn(List.of(aiGateway));
            AIResponse emptyResponse = mockAIResponseEmptyContent();
            when(aiGateway.generateResponse(aiCommand)).thenReturn(emptyResponse);

            RuntimeException ex = assertThrows(RuntimeException.class, () -> handler.handle(command));
            verify(messageService).saveAssistantErrorMessage(eq(user), any(), any(), eq(assistantRole), any());
        }

        @Test
        void whenUserNotFound_throwsRuntimeException() {
            RestChatCommand command = new RestChatCommand(
                    new ChatRequestDto("Hi", null, null, null), RestChatCommandType.MESSAGE, request, 99L);
            when(restUserService.findById(99L)).thenReturn(Optional.empty());
            when(support.getMessageLocalizationService()).thenReturn(messageLocalizationService);
            when(messageLocalizationService.getMessage(eq("rest.user.not.found"), any(), eq(99L))).thenReturn("User not found");

            assertThrows(RuntimeException.class, () -> handler.handle(command));
        }

        @Test
        void whenAccessDeniedException_thrownAsIs() {
            RestChatCommand command = new RestChatCommand(
                    new ChatRequestDto("Hi", null, null, null), RestChatCommandType.MESSAGE, request, 1L);
            when(restUserService.findById(1L)).thenReturn(Optional.of(user));
            when(restMessageService.saveUserMessage(any(), any(), any(), any(), any())).thenReturn(userMessage);
            when(aiCommandFactoryRegistry.createCommand(eq(command), any())).thenReturn(aiCommand);
            when(aiGatewayRegistry.getSupportedAiGateways(aiCommand)).thenReturn(List.of(aiGateway));
            when(aiGateway.generateResponse(aiCommand)).thenThrow(new AccessDeniedException("denied"));

            AccessDeniedException ex = assertThrows(AccessDeniedException.class, () -> handler.handle(command));
            assertEquals("denied", ex.getMessage());
        }

        @Test
        void whenUserMessageTooLongException_thrownAsIs() {
            RestChatCommand command = new RestChatCommand(
                    new ChatRequestDto("Hi", null, null, null), RestChatCommandType.MESSAGE, request, 1L);
            when(restUserService.findById(1L)).thenReturn(Optional.of(user));
            when(restMessageService.saveUserMessage(any(), any(), any(), any(), any())).thenThrow(new UserMessageTooLongException("too long"));

            UserMessageTooLongException ex = assertThrows(UserMessageTooLongException.class, () -> handler.handle(command));
            assertEquals("too long", ex.getMessage());
        }

        @Test
        void whenGenericException_callsSupportHandleProcessingErrorAndRethrows() {
            RestChatCommand command = new RestChatCommand(
                    new ChatRequestDto("Hi", null, null, null), RestChatCommandType.MESSAGE, request, 1L);
            when(restUserService.findById(1L)).thenReturn(Optional.of(user));
            when(restMessageService.saveUserMessage(any(), any(), any(), any(), any())).thenReturn(userMessage);
            when(aiCommandFactoryRegistry.createCommand(eq(command), any())).thenReturn(aiCommand);
            when(aiGatewayRegistry.getSupportedAiGateways(aiCommand)).thenReturn(List.of(aiGateway));
            when(aiGateway.generateResponse(aiCommand)).thenThrow(new RuntimeException("gateway error"));
            when(support.handleProcessingError(eq(command), eq(userMessage), any(), any()))
                    .thenAnswer(inv -> inv.getArgument(3));

            RuntimeException ex = assertThrows(RuntimeException.class, () -> handler.handle(command));
            assertEquals("gateway error", ex.getMessage());
            verify(support).handleProcessingError(eq(command), eq(userMessage), any(), any());
        }
    }

    private static AICommand createMockAICommand(Set<ModelCapabilities> capabilities) {
        AICommand cmd = mock(AICommand.class);
        when(cmd.modelCapabilities()).thenReturn(capabilities);
        return cmd;
    }

    private static AIResponse mockAIResponseWithContent(String content) {
        AIResponse response = mock(AIResponse.class);
        when(response.gatewaySource()).thenReturn(AIGateways.MOCK);
        when(response.toMap()).thenReturn(Map.of(
                CHOICES, List.of(Map.of(MESSAGE, Map.of(CONTENT, content))),
                MODEL, "test-model"
        ));
        return response;
    }

    private static AIResponse mockAIResponseEmptyContent() {
        AIResponse response = mock(AIResponse.class);
        when(response.gatewaySource()).thenReturn(AIGateways.MOCK);
        when(response.toMap()).thenReturn(Map.of(
                CHOICES, List.of(Map.of(MESSAGE, Map.of(CONTENT, ""))),
                MODEL, "test-model"
        ));
        return response;
    }

    private static <T> T mock(Class<T> clazz) {
        return org.mockito.Mockito.mock(clazz);
    }
}
