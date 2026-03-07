package ru.girchev.aibot.rest.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ru.girchev.aibot.bulkhead.exception.AccessDeniedException;
import ru.girchev.aibot.common.exception.UserMessageTooLongException;

import java.util.HashMap;
import java.util.Map;

/**
 * Глобальный обработчик исключений для REST API
 */
@Slf4j
@RestControllerAdvice
public class RestExceptionHandler {

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<Object> handleUnauthorizedException(UnauthorizedException e, HttpServletRequest request) {
        log.warn("Authorization error: {} for request {}", e.getMessage(), request.getRequestURI());
        
        // Проверяем, является ли запрос AJAX запросом (от UI) или запросом к UI endpoints
        String acceptHeader = request.getHeader("Accept");
        String requestPath = request.getRequestURI();
        boolean isAjaxRequest = acceptHeader != null && acceptHeader.contains("application/json");
        boolean isUIRequest = requestPath != null && (requestPath.startsWith("/api/v1/session") || requestPath.startsWith("/api/v1/ui"));
        
        log.debug("Request path: {}, Accept: {}, isAjax: {}, isUI: {}", 
                requestPath, acceptHeader, isAjaxRequest, isUIRequest);
        
        // Для AJAX запросов или UI запросов возвращаем JSON с информацией об ошибке и редиректе
        if (isAjaxRequest || isUIRequest) {
            Map<String, Object> response = new HashMap<>();
            response.put("message", e.getMessage());
            response.put("status", HttpStatus.UNAUTHORIZED.value());
            response.put("redirect", "/login");
            log.debug("Returning JSON response with redirect for UI request");
            return ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(response);
        }
        
        // Для обычных REST API запросов возвращаем plain text
        log.debug("Returning plain text response for REST API request");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(e.getMessage());
    }

    @ExceptionHandler(UserMessageTooLongException.class)
    public ResponseEntity<Object> handleUserMessageTooLongException(UserMessageTooLongException e, HttpServletRequest request) {
        log.warn("Message exceeds token limit: {}", e.getMessage());
        String acceptHeader = request.getHeader("Accept");
        boolean isJson = acceptHeader != null && acceptHeader.contains("application/json");
        if (isJson) {
            Map<String, Object> response = new HashMap<>();
            response.put("message", e.getMessage());
            response.put("status", HttpStatus.BAD_REQUEST.value());
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(response);
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Object> handleAccessDeniedException(AccessDeniedException e, HttpServletRequest request) {
        log.warn("Access denied: {}", e.getMessage());
        
        // Проверяем, является ли запрос AJAX запросом (от UI)
        String acceptHeader = request.getHeader("Accept");
        boolean isAjaxRequest = acceptHeader != null && acceptHeader.contains("application/json");
        
        // Для AJAX запросов возвращаем JSON с информацией об ошибке
        if (isAjaxRequest) {
            Map<String, Object> response = new HashMap<>();
            response.put("message", e.getMessage());
            response.put("status", HttpStatus.FORBIDDEN.value());
            return ResponseEntity
                    .status(HttpStatus.FORBIDDEN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(response);
        }
        
        // Для обычных REST API запросов возвращаем plain text
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
    }
}

