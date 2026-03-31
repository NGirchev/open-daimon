package io.github.ngirchev.opendaimon.rest.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ngirchev.opendaimon.common.SupportedLanguages;
import io.github.ngirchev.opendaimon.common.ai.ModelCapabilities;
import io.github.ngirchev.opendaimon.common.model.OpenDaimonMessage;
import io.github.ngirchev.opendaimon.common.model.AssistantRole;
import io.github.ngirchev.opendaimon.common.model.ConversationThread;
import io.github.ngirchev.opendaimon.common.service.OpenDaimonMessageService;
import io.github.ngirchev.opendaimon.common.service.MessageLocalizationService;
import io.github.ngirchev.opendaimon.rest.dto.ChatRequestDto;
import io.github.ngirchev.opendaimon.rest.model.RestUser;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RestChatHandlerSupportTest {

    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private MessageLocalizationService messageLocalizationService;
    @Mock
    private OpenDaimonMessageService messageService;

    private RestChatHandlerSupport support;

    @BeforeEach
    void setUp() {
        support = new RestChatHandlerSupport(objectMapper, messageLocalizationService, messageService);
    }

    @Nested
    @DisplayName("getRequestLanguage")
    class GetRequestLanguage {

        @Test
        void whenRequestHasLocale_returnsLanguageCode() {
            HttpServletRequest request = mockRequestWithLocale(Locale.ENGLISH);
            RestChatCommand command = new RestChatCommand(new ChatRequestDto("hi", null, null, null), RestChatCommandType.MESSAGE, request, 1L);

            assertEquals("en", RestChatHandlerSupport.getRequestLanguage(command));
        }

        @Test
        void whenRequestNull_returnsDefaultLanguage() {
            RestChatCommand command = new RestChatCommand(new ChatRequestDto("hi", null, null, null), RestChatCommandType.MESSAGE, null, 1L);

            assertEquals(SupportedLanguages.DEFAULT_LANGUAGE, RestChatHandlerSupport.getRequestLanguage(command));
        }

        @Test
        void whenLocaleNull_returnsDefaultLanguage() {
            HttpServletRequest request = mockRequestWithLocale(null);
            RestChatCommand command = new RestChatCommand(new ChatRequestDto("hi", null, null, null), RestChatCommandType.MESSAGE, request, 1L);

            assertEquals(SupportedLanguages.DEFAULT_LANGUAGE, RestChatHandlerSupport.getRequestLanguage(command));
        }
    }

    @Nested
    @DisplayName("buildMetadata")
    class BuildMetadata {

        @Test
        void putsThreadKeyAssistantRoleIdUserIdAndRole() {
            ConversationThread thread = new ConversationThread();
            thread.setThreadKey("thread-1");
            Map<String, String> metadata = RestChatHandlerSupport.buildMetadata(thread, "You are helpful.", 5L, 10L);

            assertEquals("thread-1", metadata.get("threadKey"));
            assertEquals("5", metadata.get("assistantRoleId"));
            assertEquals("10", metadata.get("userId"));
            assertEquals("You are helpful.", metadata.get("role"));
        }
    }

    @Nested
    @DisplayName("createErrorMetadata")
    class CreateErrorMetadata {

        @Test
        void containsModelErrorTypeErrorMessageAndTimestamp() {
            Exception ex = new RuntimeException("Something failed");
            Set<ModelCapabilities> caps = Set.of(ModelCapabilities.CHAT);
            Map<String, Object> metadata = RestChatHandlerSupport.createErrorMetadata(caps, ex);

            assertEquals("[CHAT]", metadata.get("model"));
            assertEquals("RuntimeException", metadata.get("errorType"));
            assertEquals("Something failed", metadata.get("errorMessage"));
            assertNotNull(metadata.get("timestamp"));
            assertTrue(metadata.get("timestamp") instanceof Long);
        }
    }

    @Nested
    @DisplayName("serializeToJson")
    class SerializeToJson {

        @Test
        void whenMapValid_returnsJsonString() throws JsonProcessingException {
            Map<String, Object> map = Map.of("key", "value");
            when(objectMapper.writeValueAsString(map)).thenReturn("{\"key\":\"value\"}");

            assertEquals("{\"key\":\"value\"}", support.serializeToJson(map));
        }

        @Test
        void whenMapNull_returnsNull() {
            assertNull(support.serializeToJson(null));
        }

        @Test
        void whenMapEmpty_returnsNull() {
            assertNull(support.serializeToJson(Map.of()));
        }

        @Test
        void whenWriteValueThrows_returnsNull() throws JsonProcessingException {
            Map<String, Object> map = Map.of("x", "y");
            when(objectMapper.writeValueAsString(map)).thenThrow(new RuntimeException("serialization failed"));

            assertNull(support.serializeToJson(map));
        }
    }

    @Nested
    @DisplayName("handleProcessingError")
    class HandleProcessingError {

        @Test
        void whenUserMessageNotNull_savesAssistantErrorMessageAndReturnsRuntimeException() throws JsonProcessingException {
            HttpServletRequest request = mockRequestWithLocale(Locale.ENGLISH);
            RestChatCommand command = new RestChatCommand(new ChatRequestDto("hi", null, null, null), RestChatCommandType.MESSAGE, request, 1L);
            RestUser user = new RestUser();
            AssistantRole role = new AssistantRole();
            role.setContent("Role content");
            OpenDaimonMessage userMessage = new OpenDaimonMessage();
            userMessage.setUser(user);
            userMessage.setAssistantRole(role);
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
            when(messageLocalizationService.getMessage(eq("rest.error.processing"), eq("en"), any())).thenReturn("Error: fail");
            Exception cause = new RuntimeException("fail");

            RuntimeException result = support.handleProcessingError(command, userMessage, Set.of(ModelCapabilities.CHAT), cause);

            assertEquals("Error: fail", result.getMessage());
            assertEquals(cause, result.getCause());
            verify(messageService).saveAssistantErrorMessage(eq(user), eq("Error: fail"), eq("[CHAT]"), eq("Role content"), eq("{}"));
        }

        @Test
        void whenUserMessageNull_doesNotCallSaveAssistantErrorMessage() {
            RestChatCommand command = new RestChatCommand(new ChatRequestDto("hi", null, null, null), RestChatCommandType.MESSAGE, null, 1L);
            when(messageLocalizationService.getMessage(eq("rest.error.processing"), eq(SupportedLanguages.DEFAULT_LANGUAGE), any())).thenReturn("Error");

            RuntimeException result = support.handleProcessingError(command, null, Set.of(), new RuntimeException("x"));

            assertEquals("Error", result.getMessage());
        }

        @Test
        void whenModelCapabilitiesEmpty_usesChatInMetadata() throws JsonProcessingException {
            when(objectMapper.writeValueAsString(any())).thenAnswer(inv -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = inv.getArgument(0);
                assertEquals("[CHAT]", m.get("model"));
                return "{}";
            });
            when(messageLocalizationService.getMessage(any(), any(), any())).thenReturn("Err");
            RestChatCommand command = new RestChatCommand(new ChatRequestDto("h", null, null, null), RestChatCommandType.MESSAGE, null, 1L);

            support.handleProcessingError(command, null, Set.of(), new IllegalStateException("x"));
        }
    }

    private static HttpServletRequest mockRequestWithLocale(Locale locale) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getLocale()).thenReturn(locale);
        return request;
    }
}
