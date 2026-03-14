package io.github.ngirchev.opendaimon.rest.handler;

import io.github.ngirchev.opendaimon.bulkhead.exception.AccessDeniedException;
import io.github.ngirchev.opendaimon.common.ai.ModelCapabilities;
import io.github.ngirchev.opendaimon.common.ai.command.AICommand;
import io.github.ngirchev.opendaimon.common.ai.factory.AICommandFactoryRegistry;
import io.github.ngirchev.opendaimon.common.ai.response.SpringAIResponse;
import io.github.ngirchev.opendaimon.common.ai.response.SpringAIStreamResponse;
import io.github.ngirchev.opendaimon.common.command.ICommand;
import io.github.ngirchev.opendaimon.common.exception.UserMessageTooLongException;
import io.github.ngirchev.opendaimon.common.model.OpenDaimonMessage;
import io.github.ngirchev.opendaimon.common.model.AssistantRole;
import io.github.ngirchev.opendaimon.common.model.ConversationThread;
import io.github.ngirchev.opendaimon.common.model.RequestType;
import io.github.ngirchev.opendaimon.common.service.AIGateway;
import io.github.ngirchev.opendaimon.common.service.OpenDaimonMessageService;
import io.github.ngirchev.opendaimon.common.service.AIGatewayRegistry;
import io.github.ngirchev.opendaimon.common.service.MessageLocalizationService;
import io.github.ngirchev.opendaimon.rest.dto.ChatRequestDto;
import io.github.ngirchev.opendaimon.rest.model.RestUser;
import io.github.ngirchev.opendaimon.rest.service.RestMessageService;
import io.github.ngirchev.opendaimon.rest.service.RestUserService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Optional;
import java.util.Set;

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
class RestChatStreamMessageCommandHandlerTest {

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

    private RestChatStreamMessageCommandHandler handler;

    private RestUser user;
    private OpenDaimonMessage userMessage;
    private ConversationThread thread;
    private AssistantRole assistantRole;
    private AICommand aiCommand;
    private AIGateway aiGateway;

    @BeforeEach
    void setUp() {
        handler = new RestChatStreamMessageCommandHandler(
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
        aiGateway = Mockito.mock(AIGateway.class);
    }

    @Nested
    @DisplayName("canHandle")
    class CanHandle {

        @Test
        void whenRestChatCommandWithStreamType_returnsTrue() {
            RestChatCommand command = new RestChatCommand(
                    new ChatRequestDto("hi", null, null, null), RestChatCommandType.STREAM, request, 1L);
            assertTrue(handler.canHandle(command));
        }

        @Test
        void whenRestChatCommandWithMessageType_returnsFalse() {
            RestChatCommand command = new RestChatCommand(
                    new ChatRequestDto("hi", null, null, null), RestChatCommandType.MESSAGE, request, 1L);
            assertFalse(handler.canHandle(command));
        }

        @Test
        void whenCommandNotRestChatCommand_returnsFalse() {
            @SuppressWarnings("unchecked")
            ICommand<RestChatCommandType> other = Mockito.mock(ICommand.class);
            when(other.commandType()).thenReturn(RestChatCommandType.STREAM);
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
        void whenSuccess_returnsFluxAndSavesAssistantMessageOnComplete() {
            RestChatCommand command = new RestChatCommand(
                    new ChatRequestDto("Hello", null, null, "user@test.com"), RestChatCommandType.STREAM, request, 1L);
            when(restUserService.findById(1L)).thenReturn(Optional.of(user));
            when(restMessageService.saveUserMessage(eq(user), eq("Hello"), eq(RequestType.TEXT), eq(null), eq(request)))
                    .thenReturn(userMessage);
            when(aiCommandFactoryRegistry.createCommand(eq(command), any())).thenReturn(aiCommand);
            when(aiGatewayRegistry.getSupportedAiGateways(aiCommand)).thenReturn(List.of(aiGateway));
            ChatResponse chatResponse = ChatResponse.builder()
                    .generations(List.of(new Generation(new AssistantMessage("streamed"))))
                    .build();
            SpringAIStreamResponse streamResponse = new SpringAIStreamResponse(Flux.just(chatResponse));
            when(aiGateway.generateResponse(aiCommand)).thenReturn(streamResponse);

            Flux<String> result = handler.handle(command);

            List<String> chunks = result.collectList().block();
            assert chunks != null;
            assertEquals("streamed", String.join("", chunks));
            verify(messageService).saveAssistantMessage(eq(user), eq("streamed"), any(), eq("You are helpful."), any(), any());
            verify(messageService).updateMessageStatus(any(), eq(io.github.ngirchev.opendaimon.common.model.ResponseStatus.SUCCESS));
        }

        @Test
        void whenResponseNotSpringAIStream_handleProcessingErrorReturnsIllegalStateAndRethrows() {
            RestChatCommand command = new RestChatCommand(
                    new ChatRequestDto("Hi", null, null, null), RestChatCommandType.STREAM, request, 1L);
            when(restUserService.findById(1L)).thenReturn(Optional.of(user));
            when(restMessageService.saveUserMessage(any(), any(), any(), any(), any())).thenReturn(userMessage);
            when(aiCommandFactoryRegistry.createCommand(eq(command), any())).thenReturn(aiCommand);
            when(aiGatewayRegistry.getSupportedAiGateways(aiCommand)).thenReturn(List.of(aiGateway));
            when(aiGateway.generateResponse(aiCommand)).thenReturn(
                    new SpringAIResponse(
                            ChatResponse.builder().generations(List.of(new Generation(new AssistantMessage("x")))).build()));
            IllegalStateException cause = new IllegalStateException("Expected streaming message");
            when(support.handleProcessingError(eq(command), eq(userMessage), any(), any())).thenReturn(cause);

            IllegalStateException ex = assertThrows(IllegalStateException.class, () -> handler.handle(command).blockLast());
            assertEquals("Expected streaming message", ex.getMessage());
        }

        @Test
        void whenUserNotFound_throwsRuntimeException() {
            RestChatCommand command = new RestChatCommand(
                    new ChatRequestDto("Hi", null, null, null), RestChatCommandType.STREAM, request, 99L);
            when(restUserService.findById(99L)).thenReturn(Optional.empty());
            when(support.getMessageLocalizationService()).thenReturn(messageLocalizationService);
            when(messageLocalizationService.getMessage(eq("rest.user.not.found"), any(), eq(99L))).thenReturn("User not found");

            assertThrows(RuntimeException.class, () -> handler.handle(command).blockLast());
        }

        @Test
        void whenAccessDeniedException_thrownAsIs() {
            RestChatCommand command = new RestChatCommand(
                    new ChatRequestDto("Hi", null, null, null), RestChatCommandType.STREAM, request, 1L);
            when(restUserService.findById(1L)).thenReturn(Optional.of(user));
            when(restMessageService.saveUserMessage(any(), any(), any(), any(), any())).thenReturn(userMessage);
            when(aiCommandFactoryRegistry.createCommand(eq(command), any())).thenReturn(aiCommand);
            when(aiGatewayRegistry.getSupportedAiGateways(aiCommand)).thenReturn(List.of(aiGateway));
            when(aiGateway.generateResponse(aiCommand)).thenThrow(new AccessDeniedException("denied"));

            AccessDeniedException ex = assertThrows(AccessDeniedException.class, () -> handler.handle(command).blockLast());
            assertEquals("denied", ex.getMessage());
        }

        @Test
        void whenUserMessageTooLongException_thrownAsIs() {
            RestChatCommand command = new RestChatCommand(
                    new ChatRequestDto("Hi", null, null, null), RestChatCommandType.STREAM, request, 1L);
            when(restUserService.findById(1L)).thenReturn(Optional.of(user));
            when(restMessageService.saveUserMessage(any(), any(), any(), any(), any())).thenThrow(new UserMessageTooLongException("too long"));

            UserMessageTooLongException ex = assertThrows(UserMessageTooLongException.class, () -> handler.handle(command).blockLast());
            assertEquals("too long", ex.getMessage());
        }

        @Test
        void whenGenericException_callsSupportHandleProcessingErrorAndRethrows() {
            RestChatCommand command = new RestChatCommand(
                    new ChatRequestDto("Hi", null, null, null), RestChatCommandType.STREAM, request, 1L);
            when(restUserService.findById(1L)).thenReturn(Optional.of(user));
            when(restMessageService.saveUserMessage(any(), any(), any(), any(), any())).thenReturn(userMessage);
            when(aiCommandFactoryRegistry.createCommand(eq(command), any())).thenReturn(aiCommand);
            when(aiGatewayRegistry.getSupportedAiGateways(aiCommand)).thenReturn(List.of(aiGateway));
            when(aiGateway.generateResponse(aiCommand)).thenThrow(new RuntimeException("gateway error"));
            when(support.handleProcessingError(eq(command), eq(userMessage), any(), any()))
                    .thenAnswer(inv -> inv.getArgument(3));

            RuntimeException ex = assertThrows(RuntimeException.class, () -> handler.handle(command).blockLast());
            assertEquals("gateway error", ex.getMessage());
            verify(support).handleProcessingError(eq(command), eq(userMessage), any(), any());
        }
    }

    private static AICommand createMockAICommand(Set<ModelCapabilities> capabilities) {
        AICommand cmd = Mockito.mock(AICommand.class);
        when(cmd.modelCapabilities()).thenReturn(capabilities);
        return cmd;
    }
}
