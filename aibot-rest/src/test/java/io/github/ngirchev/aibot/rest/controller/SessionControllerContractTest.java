package io.github.ngirchev.aibot.rest.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ngirchev.aibot.bulkhead.exception.AccessDeniedException;
import io.github.ngirchev.aibot.common.exception.UserMessageTooLongException;
import io.github.ngirchev.aibot.common.service.MessageLocalizationService;
import io.github.ngirchev.aibot.rest.RestTestConfiguration;
import io.github.ngirchev.aibot.rest.dto.ChatMessageDto;
import io.github.ngirchev.aibot.rest.dto.ChatRequestDto;
import io.github.ngirchev.aibot.rest.dto.ChatResponseDto;
import io.github.ngirchev.aibot.rest.dto.ChatSessionDto;
import io.github.ngirchev.aibot.rest.exception.RestExceptionHandler;
import io.github.ngirchev.aibot.rest.exception.UnauthorizedException;
import io.github.ngirchev.aibot.rest.model.RestUser;
import io.github.ngirchev.aibot.rest.service.ChatService;
import io.github.ngirchev.aibot.rest.service.RestAuthorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Flux;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SessionController.class)
@ContextConfiguration(classes = RestTestConfiguration.class)
@Import({SessionController.class, RestExceptionHandler.class})
class SessionControllerContractTest {

    private static final String BASE_URL = "/api/v1/session";
    private static final String TEST_EMAIL = "user@test.com";
    private static final String SESSION_ID = "session-123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ChatService chatService;

    @MockBean
    private RestAuthorizationService restAuthorizationService;

    @MockBean
    private MessageLocalizationService messageLocalizationService;

    private RestUser restUser;

    @BeforeEach
    void setUp() {
        restUser = mock(RestUser.class);
        when(restUser.getId()).thenReturn(1L);
        when(restUser.getEmail()).thenReturn(TEST_EMAIL);
        when(messageLocalizationService.getMessage(eq("rest.auth.email.required"), anyString())).thenReturn("Email is required");
    }

    private String toJson(ChatRequestDto dto) throws Exception {
        return objectMapper.writeValueAsString(dto);
    }

    @Nested
    @DisplayName("POST /api/v1/session - new chat")
    class PostNewChat {

        @Test
        @DisplayName("returns 200 and JSON with message and sessionId when authorized")
        void whenAuthorized_returns200AndResponseDto() throws Exception {
            ChatRequestDto request = new ChatRequestDto("Hello", null, null, TEST_EMAIL);
            ChatResponseDto<String> response = new ChatResponseDto<>("AI reply", SESSION_ID);

            when(restAuthorizationService.authorize(eq(TEST_EMAIL), anyString())).thenReturn(restUser);
            doReturn(response).when(chatService).sendMessageToNewChat(eq("Hello"), eq(restUser), any(), eq(false));

            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(request)))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.message").value("AI reply"))
                    .andExpect(jsonPath("$.sessionId").value(SESSION_ID));
        }

        @Test
        @DisplayName("returns 401 when email missing and no session")
        void whenNoEmail_returns401() throws Exception {
            ChatRequestDto request = new ChatRequestDto("Hello", null, null, null);

            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .content(toJson(request)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.message").exists())
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.redirect").value("/login"));
        }

        @Test
        @DisplayName("returns 401 when authorize throws UnauthorizedException")
        void whenAuthorizeThrows_returns401() throws Exception {
            ChatRequestDto request = new ChatRequestDto("Hello", null, null, TEST_EMAIL);
            when(restAuthorizationService.authorize(eq(TEST_EMAIL), anyString()))
                    .thenThrow(new UnauthorizedException("User not found"));

            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .content(toJson(request)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value("User not found"))
                    .andExpect(jsonPath("$.status").value(401));
        }
    }

    @Nested
    @DisplayName("POST /api/v1/session/{sessionId} - message to existing session")
    class PostExistingSession {

        @Test
        @DisplayName("returns 200 and JSON with message and sessionId when authorized")
        void whenAuthorized_returns200AndResponseDto() throws Exception {
            ChatRequestDto request = new ChatRequestDto("Follow-up", null, null, TEST_EMAIL);
            ChatResponseDto<String> response = new ChatResponseDto<>("AI reply", SESSION_ID);

            when(restAuthorizationService.authorize(eq(TEST_EMAIL), anyString())).thenReturn(restUser);
            doReturn(response).when(chatService).sendMessage(eq(SESSION_ID), eq("Follow-up"), eq(restUser), any(), eq(false));

            mockMvc.perform(post(BASE_URL + "/" + SESSION_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(request)))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.message").value("AI reply"))
                    .andExpect(jsonPath("$.sessionId").value(SESSION_ID));
        }

        @Test
        @DisplayName("returns 401 when email missing")
        void whenNoEmail_returns401() throws Exception {
            ChatRequestDto request = new ChatRequestDto("Hi", null, null, null);

            mockMvc.perform(post(BASE_URL + "/" + SESSION_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .content(toJson(request)))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("GET /api/v1/session - list sessions")
    class GetSessions {

        @Test
        @DisplayName("returns 200 and JSON array of sessions when authorized")
        void whenAuthorized_returns200AndSessionList() throws Exception {
            List<ChatSessionDto> sessions = List.of(
                    new ChatSessionDto("s1", "Chat 1", OffsetDateTime.now()),
                    new ChatSessionDto("s2", "Chat 2", OffsetDateTime.now())
            );
            when(restAuthorizationService.authorize(eq(TEST_EMAIL), anyString())).thenReturn(restUser);
            when(chatService.getSessions(restUser)).thenReturn(sessions);

            mockMvc.perform(get(BASE_URL).param("email", TEST_EMAIL))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[0].sessionId").value("s1"))
                    .andExpect(jsonPath("$[0].name").value("Chat 1"))
                    .andExpect(jsonPath("$[0].createdAt").exists())
                    .andExpect(jsonPath("$[1].sessionId").value("s2"));
        }

        @Test
        @DisplayName("returns 401 when email missing")
        void whenNoEmail_returns401() throws Exception {
            mockMvc.perform(get(BASE_URL).accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("GET /api/v1/session/{sessionId}/messages - chat history")
    class GetSessionMessages {

        @Test
        @DisplayName("returns 200 and JSON with sessionId and messages when authorized")
        void whenAuthorized_returns200AndHistory() throws Exception {
            List<ChatMessageDto> messages = List.of(
                    new ChatMessageDto("USER", "Hello"),
                    new ChatMessageDto("ASSISTANT", "Hi there")
            );
            when(restAuthorizationService.authorize(eq(TEST_EMAIL), anyString())).thenReturn(restUser);
            when(chatService.getChatHistory(SESSION_ID, restUser)).thenReturn(messages);

            mockMvc.perform(get(BASE_URL + "/" + SESSION_ID + "/messages").param("email", TEST_EMAIL))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.sessionId").value(SESSION_ID))
                    .andExpect(jsonPath("$.messages.length()").value(2))
                    .andExpect(jsonPath("$.messages[0].role").value("USER"))
                    .andExpect(jsonPath("$.messages[0].content").value("Hello"))
                    .andExpect(jsonPath("$.messages[1].role").value("ASSISTANT"))
                    .andExpect(jsonPath("$.messages[1].content").value("Hi there"));
        }

        @Test
        @DisplayName("returns 401 when email missing")
        void whenNoEmail_returns401() throws Exception {
            mockMvc.perform(get(BASE_URL + "/" + SESSION_ID + "/messages").accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("DELETE /api/v1/session/{sessionId}")
    class DeleteSession {

        @Test
        @DisplayName("returns 204 when authorized")
        void whenAuthorized_returns204() throws Exception {
            when(restAuthorizationService.authorize(eq(TEST_EMAIL), anyString())).thenReturn(restUser);

            mockMvc.perform(delete(BASE_URL + "/" + SESSION_ID).param("email", TEST_EMAIL))
                    .andExpect(status().isNoContent())
                    .andExpect(content().string(""));

            verify(chatService).deleteSession(SESSION_ID, restUser);
        }

        @Test
        @DisplayName("returns 401 when email missing")
        void whenNoEmail_returns401() throws Exception {
            mockMvc.perform(delete(BASE_URL + "/" + SESSION_ID).accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("RestExceptionHandler - 400 UserMessageTooLongException")
    class ExceptionHandler400 {

        @Test
        @DisplayName("returns 400 and JSON with message and status when Accept application/json")
        void whenJsonAccept_returns400Json() throws Exception {
            ChatRequestDto request = new ChatRequestDto("Hello", null, null, TEST_EMAIL);
            when(restAuthorizationService.authorize(eq(TEST_EMAIL), anyString())).thenReturn(restUser);
            doThrow(new UserMessageTooLongException(5000, 4000))
                    .when(chatService).sendMessageToNewChat(anyString(), eq(restUser), any(), eq(false));
            when(messageLocalizationService.getMessage(anyString(), anyString(), any(), any())).thenReturn("Message too long");

            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .content(toJson(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.message").exists())
                    .andExpect(jsonPath("$.status").value(400));
        }
    }

    @Nested
    @DisplayName("RestExceptionHandler - 403 AccessDeniedException")
    class ExceptionHandler403 {

        @Test
        @DisplayName("returns 403 and JSON with message and status when Accept application/json")
        void whenJsonAccept_returns403Json() throws Exception {
            ChatRequestDto request = new ChatRequestDto("Hello", null, null, TEST_EMAIL);
            when(restAuthorizationService.authorize(eq(TEST_EMAIL), anyString())).thenReturn(restUser);
            doThrow(new AccessDeniedException("Access denied"))
                    .when(chatService).sendMessageToNewChat(anyString(), eq(restUser), any(), eq(false));
            when(messageLocalizationService.getMessage(anyString(), anyString())).thenReturn("Access denied");

            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .content(toJson(request)))
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.message").exists())
                    .andExpect(jsonPath("$.status").value(403));
        }
    }

    @Nested
    @DisplayName("POST /api/v1/session/stream - SSE new chat")
    class PostStreamNewChat {

        @Test
        @DisplayName("returns 200 and text/event-stream with metadata and data events when authorized")
        void whenAuthorized_returnsSseStream() throws Exception {
            ChatRequestDto request = new ChatRequestDto("Hello", null, null, TEST_EMAIL);
            Flux<String> flux = Flux.just("Hello", " ", "world");
            ChatResponseDto<Flux<String>> response = new ChatResponseDto<>(flux, SESSION_ID);

            when(restAuthorizationService.authorize(eq(TEST_EMAIL), anyString())).thenReturn(restUser);
            doReturn(response).when(chatService).sendMessageToNewChat(eq("Hello"), eq(restUser), any(), eq(true));

            MvcResult mvcResult = mockMvc.perform(post(BASE_URL + "/stream")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(request)))
                    .andExpect(request().asyncStarted())
                    .andReturn();

            MvcResult result = mockMvc.perform(asyncDispatch(mvcResult))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                    .andReturn();

            String body = result.getResponse().getContentAsString();
            // Basic contract: first event is metadata with sessionId, then data chunks
            // We do not assert exact formatting to keep test stable, only key markers.
            assertTrue(body.contains("event:metadata"));
            assertTrue(body.contains("{\"sessionId\":\"" + SESSION_ID + "\""));
            assertTrue(body.contains("data:Hello"));
            assertTrue(body.contains("data:world"));
        }

        @Test
        @DisplayName("returns 401 when email missing")
        void whenNoEmail_returns401() throws Exception {
            ChatRequestDto request = new ChatRequestDto("Hello", null, null, null);

            mockMvc.perform(post(BASE_URL + "/stream")
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .content(toJson(request)))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("POST /api/v1/session/{sessionId}/stream - SSE existing session")
    class PostStreamExistingSession {

        @Test
        @DisplayName("returns 200 and text/event-stream when authorized")
        void whenAuthorized_returnsSseStream() throws Exception {
            ChatRequestDto request = new ChatRequestDto("More", null, null, TEST_EMAIL);
            Flux<String> flux = Flux.just("Response");
            ChatResponseDto<Flux<String>> response = new ChatResponseDto<>(flux, SESSION_ID);

            when(restAuthorizationService.authorize(eq(TEST_EMAIL), anyString())).thenReturn(restUser);
            doReturn(response).when(chatService).sendMessage(eq(SESSION_ID), eq("More"), eq(restUser), any(), eq(true));

            MvcResult mvcResult = mockMvc.perform(post(BASE_URL + "/" + SESSION_ID + "/stream")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(request)))
                    .andExpect(request().asyncStarted())
                    .andReturn();

            mockMvc.perform(asyncDispatch(mvcResult))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM));
        }
    }
}
