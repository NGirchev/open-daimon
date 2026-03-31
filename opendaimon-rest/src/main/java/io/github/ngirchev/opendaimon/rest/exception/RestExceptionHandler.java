package io.github.ngirchev.opendaimon.rest.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import io.github.ngirchev.opendaimon.bulkhead.exception.AccessDeniedException;
import io.github.ngirchev.opendaimon.common.exception.UserMessageTooLongException;
import io.github.ngirchev.opendaimon.common.service.MessageLocalizationService;

import io.github.ngirchev.opendaimon.common.SupportedLanguages;

import java.util.HashMap;
import java.util.Map;

/**
 * Global exception handler for REST API. Returns localized messages based on Accept-Language.
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class RestExceptionHandler {

    private static final String HEADER_ACCEPT = "Accept";
    private static final String APPLICATION_JSON = "application/json";
    private static final String KEY_MESSAGE = "message";
    private static final String KEY_STATUS = "status";

    private final MessageLocalizationService messageLocalizationService;

    private String languageCode(HttpServletRequest request) {
        return request != null && request.getLocale() != null ? request.getLocale().getLanguage() : SupportedLanguages.DEFAULT_LANGUAGE;
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<Object> handleUnauthorizedException(UnauthorizedException e, HttpServletRequest request) {
        log.warn("Authorization error: {} for request {}", e.getMessage(), request.getRequestURI());
        
        // Check if request is AJAX (from UI) or to UI endpoints
        String acceptHeader = request.getHeader(HEADER_ACCEPT);
        String requestPath = request.getRequestURI();
        boolean isAjaxRequest = acceptHeader != null && acceptHeader.contains(APPLICATION_JSON);
        boolean isUIRequest = requestPath != null && (requestPath.startsWith("/api/v1/session") || requestPath.startsWith("/api/v1/ui"));
        
        log.debug("Request path: {}, Accept: {}, isAjax: {}, isUI: {}", 
                requestPath, acceptHeader, isAjaxRequest, isUIRequest);
        
        // For AJAX or UI requests return JSON with error info and redirect
        if (isAjaxRequest || isUIRequest) {
            Map<String, Object> response = new HashMap<>();
            response.put(KEY_MESSAGE, e.getMessage());
            response.put(KEY_STATUS, HttpStatus.UNAUTHORIZED.value());
            response.put("redirect", "/login");
            log.debug("Returning JSON response with redirect for UI request");
            return ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(response);
        }
        
        // For regular REST API requests return plain text
        log.debug("Returning plain text response for REST API request");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(e.getMessage());
    }

    @ExceptionHandler(UserMessageTooLongException.class)
    public ResponseEntity<Object> handleUserMessageTooLongException(UserMessageTooLongException e, HttpServletRequest request) {
        log.warn("Message exceeds token limit: {}", e.getMessage());
        String message = e.getEstimatedTokens() > 0 && e.getMaxAllowed() > 0
                ? messageLocalizationService.getMessage("common.error.message.too.long", languageCode(request), e.getEstimatedTokens(), e.getMaxAllowed())
                : e.getMessage();
        String acceptHeader = request.getHeader(HEADER_ACCEPT);
        boolean isJson = acceptHeader != null && acceptHeader.contains(APPLICATION_JSON);
        if (isJson) {
            Map<String, Object> response = new HashMap<>();
            response.put(KEY_MESSAGE, message);
            response.put(KEY_STATUS, HttpStatus.BAD_REQUEST.value());
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(response);
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(message);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Object> handleAccessDeniedException(AccessDeniedException e, HttpServletRequest request) {
        log.warn("Access denied: {}", e.getMessage());
        String message = messageLocalizationService.getMessage("common.error.access.denied", languageCode(request));
        String acceptHeader = request.getHeader(HEADER_ACCEPT);
        boolean isAjaxRequest = acceptHeader != null && acceptHeader.contains(APPLICATION_JSON);
        if (isAjaxRequest) {
            Map<String, Object> response = new HashMap<>();
            response.put(KEY_MESSAGE, message);
            response.put(KEY_STATUS, HttpStatus.FORBIDDEN.value());
            return ResponseEntity
                    .status(HttpStatus.FORBIDDEN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(response);
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(message);
    }
}

