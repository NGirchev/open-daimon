package io.github.ngirchev.opendaimon.telegram.command.handler.impl;

import io.github.ngirchev.opendaimon.common.ai.AIGateways;
import io.github.ngirchev.opendaimon.common.ai.ModelCapabilities;
import io.github.ngirchev.opendaimon.common.ai.command.AICommand;
import io.github.ngirchev.opendaimon.common.ai.factory.AICommandFactoryRegistry;
import io.github.ngirchev.opendaimon.common.ai.response.AIResponse;
import io.github.ngirchev.opendaimon.common.ai.response.SpringAIStreamResponse;
import io.github.ngirchev.opendaimon.bulkhead.exception.AccessDeniedException;
import io.github.ngirchev.opendaimon.common.exception.DocumentContentNotExtractableException;
import io.github.ngirchev.opendaimon.common.exception.UserMessageTooLongException;
import io.github.ngirchev.opendaimon.common.model.OpenDaimonMessage;
import io.github.ngirchev.opendaimon.common.model.AssistantRole;
import io.github.ngirchev.opendaimon.common.model.ConversationThread;
import io.github.ngirchev.opendaimon.common.service.AIGatewayRegistry;
import io.github.ngirchev.opendaimon.common.service.OpenDaimonMessageService;
import io.github.ngirchev.opendaimon.common.service.MessageLocalizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.telegram.telegrambots.meta.api.objects.Message;
import reactor.core.publisher.Flux;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import io.github.ngirchev.opendaimon.telegram.TelegramBot;
import io.github.ngirchev.opendaimon.common.model.ResponseStatus;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommand;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommandType;
import io.github.ngirchev.opendaimon.telegram.config.TelegramProperties;
import io.github.ngirchev.opendaimon.telegram.model.TelegramUser;
import io.github.ngirchev.opendaimon.telegram.service.TelegramMessageService;
import io.github.ngirchev.opendaimon.telegram.service.TelegramUserService;
import io.github.ngirchev.opendaimon.telegram.service.TelegramUserSessionService;
import io.github.ngirchev.opendaimon.telegram.service.TypingIndicatorService;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.ngirchev.opendaimon.common.ai.LlmParamNames.CHOICES;
import static io.github.ngirchev.opendaimon.common.ai.LlmParamNames.MESSAGE;
import static io.github.ngirchev.opendaimon.common.ai.LlmParamNames.CONTENT;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MessageTelegramCommandHandlerTest {

    private static final Long CHAT_ID = 100L;

    @Mock
    private TelegramBot telegramBot;
    @Mock
    private TypingIndicatorService typingIndicatorService;
    @Mock
    private TelegramUserService telegramUserService;
    @Mock
    private TelegramUserSessionService telegramUserSessionService;
    @Mock
    private TelegramMessageService telegramMessageService;
    @Mock
    private AIGatewayRegistry aiGatewayRegistry;
    @Mock
    private OpenDaimonMessageService messageService;
    @Mock
    private AICommandFactoryRegistry aiCommandFactoryRegistry;
    @Mock
    private io.github.ngirchev.opendaimon.common.service.AIGateway aiGateway;

    private MessageLocalizationService messageLocalizationService;
    private TelegramProperties telegramProperties;
    private MessageTelegramCommandHandler handler;

    @BeforeEach
    void setUp() {
        MessageSource messageSource = new ReloadableResourceBundleMessageSource();
        ((ReloadableResourceBundleMessageSource) messageSource).setBasenames(
                "classpath:messages/common", "classpath:messages/telegram");
        ((ReloadableResourceBundleMessageSource) messageSource).setDefaultEncoding("UTF-8");
        messageLocalizationService = new MessageLocalizationService(messageSource);

        telegramProperties = new TelegramProperties();
        telegramProperties.setToken("test-token");
        telegramProperties.setUsername("test-bot");
        telegramProperties.setMaxMessageLength(4096);

        ObjectProvider<TelegramBot> botProvider = mock(ObjectProvider.class);
        when(botProvider.getObject()).thenReturn(telegramBot);

        handler = new MessageTelegramCommandHandler(botProvider, typingIndicatorService, messageLocalizationService,
                telegramUserService, telegramUserSessionService, telegramMessageService, aiGatewayRegistry,
                messageService, aiCommandFactoryRegistry, telegramProperties);
    }

    @Test
    void canHandle_whenTelegramCommandWithMessageCommand_thenTrue() {
        Update update = new Update();
        Message message = new Message();
        message.setFrom(new User(200L, "user", false));
        update.setMessage(message);

        TelegramCommand command = new TelegramCommand(200L, CHAT_ID, new TelegramCommandType(TelegramCommand.MESSAGE), update);
        assertTrue(handler.canHandle(command));
    }

    @Test
    void canHandle_whenNotTelegramCommand_thenFalse() {
        io.github.ngirchev.opendaimon.common.command.ICommand<io.github.ngirchev.opendaimon.telegram.command.TelegramCommandType> other =
                mock(io.github.ngirchev.opendaimon.common.command.ICommand.class);
        assertFalse(handler.canHandle(other));
    }

    @Test
    void canHandle_whenCommandTypeNull_thenFalse() {
        Update update = new Update();
        update.setMessage(new Message());
        TelegramCommand command = new TelegramCommand(200L, CHAT_ID, null, update);
        assertFalse(handler.canHandle(command));
    }

    @Test
    void canHandle_whenOtherCommand_thenFalse() {
        Update update = new Update();
        update.setMessage(new Message());
        TelegramCommand command = new TelegramCommand(200L, CHAT_ID, new TelegramCommandType(TelegramCommand.ROLE), update);
        assertFalse(handler.canHandle(command));
    }

    @Test
    void handleInner_whenMessageNull_thenSendsError() throws org.telegram.telegrambots.meta.exceptions.TelegramApiException {
        Update update = mock(Update.class);
        when(update.getMessage()).thenReturn(null);
        TelegramCommand command = new TelegramCommand(200L, CHAT_ID, new TelegramCommandType(TelegramCommand.MESSAGE), update, "Hello");

        assertNull(handler.handleInner(command));
        verify(telegramBot).sendErrorMessage(eq(CHAT_ID), anyString(), isNull());
    }

    @Test
    void handleInner_whenUserMessageTooLong_thenSendsErrorMessage() throws Exception {
        Update update = new Update();
        Message message = new Message();
        message.setMessageId(1);
        User from = new User(200L, "user", false);
        message.setFrom(from);
        update.setMessage(message);

        TelegramUser telegramUser = new TelegramUser();
        telegramUser.setTelegramId(200L);
        when(telegramUserService.getOrCreateUser(from)).thenReturn(telegramUser);
        when(telegramUserSessionService.getOrCreateSession(telegramUser)).thenReturn(null);
        when(telegramMessageService.saveUserMessage(any(), any(), anyString(), any(), isNull(), any()))
                .thenThrow(new UserMessageTooLongException(5000, 4000));

        TelegramCommand command = new TelegramCommand(200L, CHAT_ID, new TelegramCommandType(TelegramCommand.MESSAGE), update, "Very long text");
        command.languageCode("en");

        assertNull(handler.handleInner(command));

        verify(telegramBot).sendErrorMessage(eq(CHAT_ID), anyString(), any());
    }

    @Test
    void handleInner_whenDocumentContentNotExtractable_thenSavesErrorAndSendsMessage() throws Exception {
        Update update = new Update();
        Message message = new Message();
        message.setMessageId(1);
        User from = new User(200L, "user", false);
        message.setFrom(from);
        update.setMessage(message);

        TelegramUser telegramUser = new TelegramUser();
        telegramUser.setTelegramId(200L);
        telegramUser.setId(1L);
        AssistantRole role = new AssistantRole();
        role.setId(10L);
        role.setVersion(1);
        role.setContent("Role");
        ConversationThread thread = new ConversationThread();
        thread.setThreadKey("test-thread-key");
        thread.setUser(telegramUser);
        OpenDaimonMessage userMessage = new OpenDaimonMessage();
        userMessage.setUser(telegramUser);
        userMessage.setAssistantRole(role);
        userMessage.setThread(thread);

        when(telegramUserService.getOrCreateUser(from)).thenReturn(telegramUser);
        when(telegramUserSessionService.getOrCreateSession(telegramUser)).thenReturn(null);
        when(telegramMessageService.saveUserMessage(any(), any(), anyString(), any(), isNull(), any())).thenReturn(userMessage);

        AICommand aiCommand = mock(AICommand.class);
        when(aiCommand.modelCapabilities()).thenReturn(Set.of(ModelCapabilities.CHAT));
        when(aiCommandFactoryRegistry.createCommand(any(), any())).thenReturn(aiCommand);
        when(aiGatewayRegistry.getSupportedAiGateways(aiCommand)).thenReturn(List.of(aiGateway));
        when(aiGateway.generateResponse(aiCommand)).thenThrow(new DocumentContentNotExtractableException("Cannot extract text"));

        TelegramCommand command = new TelegramCommand(200L, CHAT_ID, new TelegramCommandType(TelegramCommand.MESSAGE), update, "See attachment");
        command.languageCode("en");

        assertNull(handler.handleInner(command));

        verify(telegramMessageService).saveAssistantErrorMessage(eq(telegramUser), anyString(), anyString(), eq("Role"), isNull());
        verify(telegramBot).sendErrorMessage(eq(CHAT_ID), anyString(), eq(1));
    }

    @Test
    void handleInner_whenGenericException_thenSavesErrorAndSendsMessage() throws Exception {
        Update update = new Update();
        Message message = new Message();
        message.setMessageId(1);
        User from = new User(200L, "user", false);
        message.setFrom(from);
        update.setMessage(message);

        TelegramUser telegramUser = new TelegramUser();
        telegramUser.setTelegramId(200L);
        telegramUser.setId(1L);
        AssistantRole role = new AssistantRole();
        role.setId(10L);
        role.setVersion(1);
        role.setContent("Role");
        ConversationThread thread = new ConversationThread();
        thread.setThreadKey("test-thread-key");
        thread.setUser(telegramUser);
        OpenDaimonMessage userMessage = new OpenDaimonMessage();
        userMessage.setUser(telegramUser);
        userMessage.setAssistantRole(role);
        userMessage.setThread(thread);

        when(telegramUserService.getOrCreateUser(from)).thenReturn(telegramUser);
        when(telegramUserSessionService.getOrCreateSession(telegramUser)).thenReturn(null);
        when(telegramMessageService.saveUserMessage(any(), any(), anyString(), any(), isNull(), any())).thenReturn(userMessage);

        AICommand aiCommand = mock(AICommand.class);
        when(aiCommand.modelCapabilities()).thenReturn(Set.of(ModelCapabilities.CHAT));
        when(aiCommandFactoryRegistry.createCommand(any(), any())).thenReturn(aiCommand);
        when(aiGatewayRegistry.getSupportedAiGateways(aiCommand)).thenReturn(List.of(aiGateway));
        when(aiGateway.generateResponse(aiCommand)).thenThrow(new RuntimeException("Gateway error"));

        TelegramCommand command = new TelegramCommand(200L, CHAT_ID, new TelegramCommandType(TelegramCommand.MESSAGE), update, "Hello");
        command.languageCode("en");

        assertNull(handler.handleInner(command));

        verify(telegramMessageService).saveAssistantErrorMessage(eq(telegramUser), eq("An error occurred while processing the message."), anyString(), eq("Role"), isNull());
        verify(telegramBot).sendErrorMessage(eq(CHAT_ID), eq("An error occurred while processing the message."), eq(1));
    }

    @Test
    void handleInner_whenSuccess_thenSavesAndSendsResponse() throws Exception {
        Update update = new Update();
        Message message = new Message();
        message.setMessageId(1);
        User from = new User(200L, "user", false);
        message.setFrom(from);
        update.setMessage(message);

        TelegramUser telegramUser = new TelegramUser();
        telegramUser.setTelegramId(200L);
        telegramUser.setId(1L);
        ConversationThread thread = new ConversationThread();
        thread.setThreadKey("thread-key-123");
        thread.setUser(telegramUser);
        AssistantRole assistantRole = new AssistantRole();
        assistantRole.setId(10L);
        assistantRole.setVersion(1);
        assistantRole.setContent("You are helpful.");
        OpenDaimonMessage userMessage = new OpenDaimonMessage();
        userMessage.setUser(telegramUser);
        userMessage.setThread(thread);
        userMessage.setAssistantRole(assistantRole);

        when(telegramUserService.getOrCreateUser(from)).thenReturn(telegramUser);
        when(telegramUserSessionService.getOrCreateSession(telegramUser)).thenReturn(null);
        when(telegramMessageService.saveUserMessage(any(), any(), eq("Hello"), any(), isNull(), any())).thenReturn(userMessage);

        AICommand aiCommand = mock(AICommand.class);
        when(aiCommand.modelCapabilities()).thenReturn(Set.of(ModelCapabilities.CHAT));
        when(aiCommandFactoryRegistry.createCommand(any(), any())).thenReturn(aiCommand);
        when(aiGatewayRegistry.getSupportedAiGateways(aiCommand)).thenReturn(List.of(aiGateway));

        Map<String, Object> responseMap = Map.of(
                CHOICES, List.of(Map.of(MESSAGE, Map.of(CONTENT, "Hi there!")))
        );
        AIResponse aiResponse = mock(AIResponse.class);
        when(aiResponse.gatewaySource()).thenReturn(AIGateways.MOCK);
        when(aiResponse.toMap()).thenReturn(responseMap);
        when(aiGateway.generateResponse(aiCommand)).thenReturn(aiResponse);

        OpenDaimonMessage assistantMessage = new OpenDaimonMessage();
        when(telegramMessageService.saveAssistantMessage(eq(telegramUser), eq("Hi there!"), anyString(), eq("You are helpful."), anyInt(), any()))
                .thenReturn(assistantMessage);

        TelegramCommand command = new TelegramCommand(200L, CHAT_ID, new TelegramCommandType(TelegramCommand.MESSAGE), update, "Hello");
        command.languageCode("en");

        assertNull(handler.handleInner(command));

        verify(telegramMessageService).saveAssistantMessage(eq(telegramUser), eq("Hi there!"), anyString(), eq("You are helpful."), anyInt(), any());
        verify(messageService).updateMessageStatus(assistantMessage, ResponseStatus.SUCCESS);
        verify(telegramBot).sendMessage(eq(CHAT_ID), contains("Hi there!"), any());
    }

    @Test
    void getSupportedCommandText_returnsNull() {
        assertNull(handler.getSupportedCommandText("en"));
    }

    @Test
    void handle_whenAccessDeniedException_fromGateway_sendsAccessDeniedMessage() throws Exception {
        Update update = new Update();
        Message message = new Message();
        message.setMessageId(1);
        User from = new User(200L, "user", false);
        message.setFrom(from);
        update.setMessage(message);

        TelegramUser telegramUser = new TelegramUser();
        telegramUser.setTelegramId(200L);
        telegramUser.setId(1L);
        AssistantRole role = new AssistantRole();
        role.setId(10L);
        role.setContent("Role");
        ConversationThread thread = new ConversationThread();
        thread.setThreadKey("tk");
        thread.setUser(telegramUser);
        OpenDaimonMessage userMessage = new OpenDaimonMessage();
        userMessage.setUser(telegramUser);
        userMessage.setAssistantRole(role);
        userMessage.setThread(thread);

        when(telegramUserService.getOrCreateUser(from)).thenReturn(telegramUser);
        when(telegramUserSessionService.getOrCreateSession(telegramUser)).thenReturn(null);
        when(telegramMessageService.saveUserMessage(any(), any(), anyString(), any(), isNull(), any())).thenReturn(userMessage);

        AICommand aiCommand = mock(AICommand.class);
        when(aiCommand.modelCapabilities()).thenReturn(Set.of(ModelCapabilities.CHAT));
        when(aiCommandFactoryRegistry.createCommand(any(), any())).thenReturn(aiCommand);
        when(aiGatewayRegistry.getSupportedAiGateways(aiCommand)).thenReturn(List.of(aiGateway));
        when(aiGateway.generateResponse(aiCommand)).thenThrow(new AccessDeniedException("denied"));

        TelegramCommand command = new TelegramCommand(200L, CHAT_ID, new TelegramCommandType(TelegramCommand.MESSAGE), update, "Hello");
        command.languageCode("en");

        handler.handle(command);

        verify(telegramBot).sendErrorMessage(eq(CHAT_ID), anyString(), any());
    }

    @Test
    void handle_whenTelegramCommandHandlerException_sendsErrorMessage() throws Exception {
        Update update = new Update();
        Message message = new Message();
        message.setMessageId(1);
        User from = new User(200L, "user", false);
        message.setFrom(from);
        update.setMessage(message);

        TelegramUser telegramUser = new TelegramUser();
        telegramUser.setTelegramId(200L);
        telegramUser.setId(1L);
        AssistantRole role = new AssistantRole();
        role.setId(10L);
        role.setContent("Role");
        ConversationThread thread = new ConversationThread();
        thread.setThreadKey("tk");
        thread.setUser(telegramUser);
        OpenDaimonMessage userMessage = new OpenDaimonMessage();
        userMessage.setUser(telegramUser);
        userMessage.setAssistantRole(role);
        userMessage.setThread(thread);

        when(telegramUserService.getOrCreateUser(from)).thenReturn(telegramUser);
        when(telegramUserSessionService.getOrCreateSession(telegramUser)).thenReturn(null);
        when(telegramMessageService.saveUserMessage(any(), any(), anyString(), any(), isNull(), any())).thenReturn(userMessage);

        AICommand aiCommand = mock(AICommand.class);
        when(aiCommand.modelCapabilities()).thenReturn(Set.of(ModelCapabilities.CHAT));
        when(aiCommandFactoryRegistry.createCommand(any(), any())).thenReturn(aiCommand);
        when(aiGatewayRegistry.getSupportedAiGateways(aiCommand)).thenReturn(List.of(aiGateway));

        Map<String, Object> responseMap = Map.of(
                CHOICES, List.of(Map.of(MESSAGE, Map.of(CONTENT, "Hi")))
        );
        AIResponse aiResponse = mock(AIResponse.class);
        when(aiResponse.gatewaySource()).thenReturn(AIGateways.MOCK);
        when(aiResponse.toMap()).thenReturn(responseMap);
        when(aiGateway.generateResponse(aiCommand)).thenReturn(aiResponse);

        when(telegramMessageService.saveAssistantMessage(any(), any(), anyString(), any(), anyInt(), any())).thenReturn(new OpenDaimonMessage());
        doThrow(new org.telegram.telegrambots.meta.exceptions.TelegramApiException("send failed")).when(telegramBot).sendMessage(anyLong(), anyString(), any());

        TelegramCommand command = new TelegramCommand(200L, CHAT_ID, new TelegramCommandType(TelegramCommand.MESSAGE), update, "Hello");
        command.languageCode("en");

        handler.handle(command);

        verify(telegramBot).sendErrorMessage(eq(CHAT_ID), anyString(), any());
    }

    @Test
    void handleInner_whenGatewayReturnsEmptyContent_thenLogsDetailAndSendsI18nError() throws Exception {
        Update update = new Update();
        Message message = new Message();
        message.setMessageId(1);
        User from = new User(200L, "user", false);
        message.setFrom(from);
        update.setMessage(message);

        TelegramUser telegramUser = new TelegramUser();
        telegramUser.setTelegramId(200L);
        telegramUser.setId(1L);
        AssistantRole role = new AssistantRole();
        role.setId(10L);
        role.setContent("Role");
        ConversationThread thread = new ConversationThread();
        thread.setThreadKey("tk");
        thread.setUser(telegramUser);
        OpenDaimonMessage userMessage = new OpenDaimonMessage();
        userMessage.setUser(telegramUser);
        userMessage.setAssistantRole(role);
        userMessage.setThread(thread);

        when(telegramUserService.getOrCreateUser(from)).thenReturn(telegramUser);
        when(telegramUserSessionService.getOrCreateSession(telegramUser)).thenReturn(null);
        when(telegramMessageService.saveUserMessage(any(), any(), anyString(), any(), isNull(), any())).thenReturn(userMessage);

        AICommand aiCommand = mock(AICommand.class);
        when(aiCommand.modelCapabilities()).thenReturn(Set.of(ModelCapabilities.CHAT));
        when(aiCommandFactoryRegistry.createCommand(any(), any())).thenReturn(aiCommand);
        when(aiGatewayRegistry.getSupportedAiGateways(aiCommand)).thenReturn(List.of(aiGateway));

        Map<String, Object> responseMap = Map.of(
                CHOICES, List.of(Map.of(MESSAGE, Map.of(CONTENT, "")))
        );
        AIResponse emptyResponse = mock(AIResponse.class);
        when(emptyResponse.gatewaySource()).thenReturn(AIGateways.MOCK);
        when(emptyResponse.toMap()).thenReturn(responseMap);
        when(aiGateway.generateResponse(aiCommand)).thenReturn(emptyResponse);

        TelegramCommand command = new TelegramCommand(200L, CHAT_ID, new TelegramCommandType(TelegramCommand.MESSAGE), update, "Hello");
        command.languageCode("en");

        assertNull(handler.handleInner(command));

        verify(aiGateway, times(2)).generateResponse(aiCommand);
        verify(telegramMessageService).saveAssistantErrorMessage(eq(telegramUser), anyString(), anyString(), eq("Role"), any());
        verify(telegramBot).sendErrorMessage(eq(CHAT_ID), eq("An error occurred while processing the message."), eq(1));
    }

    @Test
    void handleInner_whenGatewayReturnsEmptyThenContentOnRetry_thenSuccess() throws Exception {
        Update update = new Update();
        Message message = new Message();
        message.setMessageId(1);
        User from = new User(200L, "user", false);
        message.setFrom(from);
        update.setMessage(message);

        TelegramUser telegramUser = new TelegramUser();
        telegramUser.setTelegramId(200L);
        telegramUser.setId(1L);
        AssistantRole role = new AssistantRole();
        role.setId(10L);
        role.setContent("Role");
        ConversationThread thread = new ConversationThread();
        thread.setThreadKey("tk");
        thread.setUser(telegramUser);
        OpenDaimonMessage userMessage = new OpenDaimonMessage();
        userMessage.setUser(telegramUser);
        userMessage.setAssistantRole(role);
        userMessage.setThread(thread);

        when(telegramUserService.getOrCreateUser(from)).thenReturn(telegramUser);
        when(telegramUserSessionService.getOrCreateSession(telegramUser)).thenReturn(null);
        when(telegramMessageService.saveUserMessage(any(), any(), anyString(), any(), isNull(), any())).thenReturn(userMessage);

        AICommand aiCommand = mock(AICommand.class);
        when(aiCommand.modelCapabilities()).thenReturn(Set.of(ModelCapabilities.CHAT));
        when(aiCommandFactoryRegistry.createCommand(any(), any())).thenReturn(aiCommand);
        when(aiGatewayRegistry.getSupportedAiGateways(aiCommand)).thenReturn(List.of(aiGateway));

        Map<String, Object> emptyMap = Map.of(CHOICES, List.of(Map.of(MESSAGE, Map.of(CONTENT, ""))));
        Map<String, Object> successMap = Map.of(CHOICES, List.of(Map.of(MESSAGE, Map.of(CONTENT, "Retry success"))));
        AIResponse emptyResponse = mock(AIResponse.class);
        when(emptyResponse.gatewaySource()).thenReturn(AIGateways.MOCK);
        when(emptyResponse.toMap()).thenReturn(emptyMap);
        AIResponse successResponse = mock(AIResponse.class);
        when(successResponse.gatewaySource()).thenReturn(AIGateways.MOCK);
        when(successResponse.toMap()).thenReturn(successMap);
        when(aiGateway.generateResponse(aiCommand)).thenReturn(emptyResponse).thenReturn(successResponse);

        OpenDaimonMessage assistantMessage = new OpenDaimonMessage();
        when(telegramMessageService.saveAssistantMessage(eq(telegramUser), eq("Retry success"), anyString(), eq("Role"), anyInt(), any()))
                .thenReturn(assistantMessage);

        TelegramCommand command = new TelegramCommand(200L, CHAT_ID, new TelegramCommandType(TelegramCommand.MESSAGE), update, "Hello");
        command.languageCode("en");

        assertNull(handler.handleInner(command));

        verify(aiGateway, times(2)).generateResponse(aiCommand);
        verify(telegramMessageService).saveAssistantMessage(eq(telegramUser), eq("Retry success"), anyString(), eq("Role"), anyInt(), any());
        verify(messageService).updateMessageStatus(assistantMessage, ResponseStatus.SUCCESS);
        verify(telegramBot).sendMessage(eq(CHAT_ID), contains("Retry success"), any());
        verify(telegramBot, never()).sendErrorMessage(anyLong(), anyString(), any());
    }

    @Test
    void handleInner_whenGatewayReturnsSpringAIStreamResponse_thenProcessesStreamAndSends() throws Exception {
        Update update = new Update();
        Message message = new Message();
        message.setMessageId(1);
        User from = new User(200L, "user", false);
        message.setFrom(from);
        update.setMessage(message);

        TelegramUser telegramUser = new TelegramUser();
        telegramUser.setTelegramId(200L);
        telegramUser.setId(1L);
        AssistantRole role = new AssistantRole();
        role.setId(10L);
        role.setContent("Role");
        ConversationThread thread = new ConversationThread();
        thread.setThreadKey("tk");
        thread.setUser(telegramUser);
        OpenDaimonMessage userMessage = new OpenDaimonMessage();
        userMessage.setUser(telegramUser);
        userMessage.setAssistantRole(role);
        userMessage.setThread(thread);

        when(telegramUserService.getOrCreateUser(from)).thenReturn(telegramUser);
        when(telegramUserSessionService.getOrCreateSession(telegramUser)).thenReturn(null);
        when(telegramMessageService.saveUserMessage(any(), any(), anyString(), any(), isNull(), any())).thenReturn(userMessage);

        AICommand aiCommand = mock(AICommand.class);
        when(aiCommand.modelCapabilities()).thenReturn(Set.of(ModelCapabilities.CHAT));
        when(aiCommandFactoryRegistry.createCommand(any(), any())).thenReturn(aiCommand);
        when(aiGatewayRegistry.getSupportedAiGateways(aiCommand)).thenReturn(List.of(aiGateway));

        ChatResponse chatResponse = createChatResponse("Streamed reply");
        SpringAIStreamResponse streamResponse = new SpringAIStreamResponse(Flux.just(chatResponse));
        when(aiGateway.generateResponse(aiCommand)).thenReturn(streamResponse);

        OpenDaimonMessage assistantMessage = new OpenDaimonMessage();
        when(telegramMessageService.saveAssistantMessage(eq(telegramUser), eq("Streamed reply"), anyString(), eq("Role"), anyInt(), any()))
                .thenReturn(assistantMessage);

        TelegramCommand command = new TelegramCommand(200L, CHAT_ID, new TelegramCommandType(TelegramCommand.MESSAGE), update, "Hello");
        command.languageCode("en");

        assertNull(handler.handleInner(command));

        verify(telegramMessageService).saveAssistantMessage(eq(telegramUser), eq("Streamed reply"), anyString(), eq("Role"), anyInt(), any());
        verify(messageService).updateMessageStatus(assistantMessage, ResponseStatus.SUCCESS);
        verify(telegramBot).sendMessage(eq(CHAT_ID), contains("Streamed reply"), any());
    }

    private static ChatResponse createChatResponse(String text) {
        AssistantMessage message = new AssistantMessage(text);
        Generation generation = new Generation(message);
        return ChatResponse.builder()
                .generations(List.of(generation))
                .metadata(ChatResponseMetadata.builder().build())
                .build();
    }
}
