package io.github.ngirchev.opendaimon.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import io.github.ngirchev.opendaimon.common.service.MessageLocalizationService;
import io.github.ngirchev.opendaimon.rest.dto.*;
import io.github.ngirchev.opendaimon.rest.exception.UnauthorizedException;
import io.github.ngirchev.opendaimon.rest.service.ChatService;
import io.github.ngirchev.opendaimon.rest.service.RestAuthorizationService;
import io.github.ngirchev.opendaimon.rest.service.model.ChatMessage;
import io.github.ngirchev.opendaimon.rest.service.model.ChatResponse;
import io.github.ngirchev.opendaimon.rest.service.model.ChatSession;

import java.util.List;

/**
 * Controller for chat sessions via UI.
 * Manages sessions and messages.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/session")
@RequiredArgsConstructor
@Tag(name = "Session Controller", description = "Endpoints for chat session interactions")
public class SessionController {

    private final ChatService chatService;
    private final RestAuthorizationService restAuthorizationService;
    private final MessageLocalizationService messageLocalizationService;

    private static final String SESSION_EMAIL_KEY = "userEmail";

    @PostMapping
    @Operation(summary = "Send message to new chat", description = "Creates a new chat session and sends a message")
    public ResponseEntity<ChatResponseDto<String>> sendMessageToNewChat(
            @RequestBody ChatRequestDto request,
            HttpServletRequest httpRequest,
            HttpSession session) {
        String email = getEmailFromSessionOrRequest(session, request.email(), httpRequest.getLocale().getLanguage());
        ChatResponse<String> response = chatService.sendMessageToNewChat(
                        request.message(),
                        restAuthorizationService.authorize(email, httpRequest.getLocale().getLanguage()),
                        httpRequest,
                false);
        return ResponseEntity.ok(toDto(response));
    }

    @PostMapping("/{sessionId}")
    @Operation(summary = "Send message to existing session", description = "Sends a message to an existing chat session")
    public ResponseEntity<ChatResponseDto<String>> sendMessage(
            @PathVariable String sessionId,
            @RequestBody ChatRequestDto request,
            HttpServletRequest httpRequest,
            HttpSession session) {
        String email = getEmailFromSessionOrRequest(session, request.email(), httpRequest.getLocale().getLanguage());
        ChatResponse<String> response = chatService.sendMessage(
                sessionId,
                request.message(),
                restAuthorizationService.authorize(email, httpRequest.getLocale().getLanguage()),
                httpRequest,
                false);
        return ResponseEntity.ok(toDto(response));
    }

//    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
//    public Flux<ServerSentEvent<String>> sendMessageToNewChatStream(
//            @RequestBody ChatRequestDto request,
//            HttpServletRequest httpRequest,
//            HttpSession session) {
//        // TODO: TEMP fake stream — remove after UI streaming is confirmed working
//        String fakeText = "Hello! This is a fake stream test. Each character arrives with 200ms delay.";
//        ServerSentEvent<String> sessionEvent = ServerSentEvent.<String>builder()
//                .event("metadata")
//                .data("{\"sessionId\":\"fake-test-session\"}")
//                .build();
//        Flux<ServerSentEvent<String>> fakeContent = Flux.fromArray(fakeText.split(""))
//                .delayElements(Duration.ofMillis(200))
//                .map(ch -> ServerSentEvent.builder(ch).build());
//        return Flux.concat(Flux.just(sessionEvent), fakeContent);
//    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> sendMessageToNewChatStream(
            @RequestBody ChatRequestDto request,
            HttpServletRequest httpRequest,
            HttpSession session) {
        String email = getEmailFromSessionOrRequest(session, request.email(), httpRequest.getLocale().getLanguage());
        var user = restAuthorizationService.authorize(email, httpRequest.getLocale().getLanguage());
        ChatResponse<Flux<String>> response = chatService.sendMessageToNewChat(request.message(), user, httpRequest, true);
        String sessionId = response.sessionId();
        // Send sessionId in first event with type "metadata"
        ServerSentEvent<String> sessionEvent = ServerSentEvent.<String>builder()
                .event("metadata")
                .data("{\"sessionId\":\"" + sessionId + "\"}")
                .build();
        // Then send message stream with type "message" (or no type for regular content)
        // Do not use delayElements - send data as soon as it arrives
        return Flux.concat(
                Flux.just(sessionEvent),
                response.message()
                        // Convert to SSE (no event type = regular content)
                        .map(ch -> ServerSentEvent.builder(ch).build())
        );
    }

    @PostMapping(value = "/{sessionId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> sendMessageStream(
            @PathVariable String sessionId,
            @RequestBody ChatRequestDto request,
            HttpServletRequest httpRequest,
            HttpSession session) {
        String email = getEmailFromSessionOrRequest(session, request.email(), httpRequest.getLocale().getLanguage());
        var user = restAuthorizationService.authorize(email, httpRequest.getLocale().getLanguage());
        ChatResponse<Flux<String>> response = chatService.sendMessage(sessionId, request.message(), user, httpRequest, true);
        // Do not use delayElements - send data as soon as it arrives
        return response.message()
                // Convert to SSE
                .map(ch -> ServerSentEvent.builder(ch).build());
    }

    @GetMapping
    @Operation(summary = "Get all sessions", description = "Returns list of all chat sessions for the user")
    public ResponseEntity<List<ChatSessionDto>> getSessions(
            @RequestParam(value = "email", required = false) String email,
            HttpSession session,
            HttpServletRequest httpRequest) {
        String userEmail = getEmailFromSessionOrRequest(session, email, httpRequest.getLocale().getLanguage());
        var user = restAuthorizationService.authorize(userEmail, httpRequest.getLocale().getLanguage());
        return ResponseEntity.ok(chatService.getSessions(user).stream()
                .map(SessionController::toDto)
                .toList());
    }

    @GetMapping("/{sessionId}/messages")
    @Operation(summary = "Get session messages", description = "Returns chat history for a specific session")
    public ResponseEntity<ChatHistoryResponseDto> getSessionMessages(
            @PathVariable String sessionId,
            @RequestParam(value = "email", required = false) String email,
            HttpSession session,
            HttpServletRequest httpRequest) {
        String userEmail = getEmailFromSessionOrRequest(session, email, httpRequest.getLocale().getLanguage());
        var user = restAuthorizationService.authorize(userEmail, httpRequest.getLocale().getLanguage());
        List<ChatMessageDto> messages = chatService.getChatHistory(sessionId, user).stream()
                .map(SessionController::toDto)
                .toList();
        return ResponseEntity.ok(new ChatHistoryResponseDto(sessionId, messages));
    }

    @DeleteMapping("/{sessionId}")
    @Operation(summary = "Delete session", description = "Deletes a chat session and all its messages")
    public ResponseEntity<Void> deleteSession(
            @PathVariable String sessionId,
            @RequestParam(value = "email", required = false) String email,
            HttpSession session,
            HttpServletRequest httpRequest) {
        String userEmail = getEmailFromSessionOrRequest(session, email, httpRequest.getLocale().getLanguage());
        var user = restAuthorizationService.authorize(userEmail, httpRequest.getLocale().getLanguage());
        chatService.deleteSession(sessionId, user);
        return ResponseEntity.noContent().build();
    }

    /**
     * Gets email from session or request. Throws UnauthorizedException with localized message if missing.
     */
    private String getEmailFromSessionOrRequest(HttpSession session, String emailFromRequest, String languageCode) {
        if (session != null) {
            try {
                String emailFromSession = (String) session.getAttribute(SESSION_EMAIL_KEY);
                if (emailFromSession != null && !emailFromSession.isBlank()) {
                    return emailFromSession;
                }
            } catch (IllegalStateException e) {
                log.debug("Session is invalid or expired, trying request parameter");
            }
        }
        if (emailFromRequest != null && !emailFromRequest.isBlank()) {
            return emailFromRequest;
        }
        throw new UnauthorizedException(messageLocalizationService.getMessage("rest.auth.email.required", languageCode));
    }

    private static <T> ChatResponseDto<T> toDto(ChatResponse<T> response) {
        return new ChatResponseDto<>(response.message(), response.sessionId());
    }

    private static ChatSessionDto toDto(ChatSession session) {
        return new ChatSessionDto(session.sessionId(), session.name(), session.createdAt());
    }

    private static ChatMessageDto toDto(ChatMessage message) {
        return new ChatMessageDto(message.role(), message.content());
    }
}
