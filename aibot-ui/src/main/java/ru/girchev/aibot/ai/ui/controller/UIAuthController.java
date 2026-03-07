package ru.girchev.aibot.ai.ui.controller;

import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.girchev.aibot.rest.model.RestUser;
import ru.girchev.aibot.rest.service.RestAuthorizationService;
import ru.girchev.aibot.rest.service.RestUserService;

import java.util.Map;

/**
 * Контроллер для авторизации UI пользователей
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ui")
@RequiredArgsConstructor
public class UIAuthController {

    private final RestUserService restUserService;
    
    private static final String SESSION_EMAIL_KEY = "userEmail";
    private static final String SESSION_USER_ID_KEY = "userId";

    @PostMapping("/login")
    public ResponseEntity<Map<String, String>> login(@RequestBody Map<String, String> request, HttpSession session) {
        String email = request.get("email");
        
        if (email == null || email.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Email обязателен"));
        }
        
        try {
            // Получаем или создаем пользователя с таким email
            RestUser user = restUserService.getOrCreateUser(email);
            
            // Сохраняем email и userId в сессии
            session.setAttribute(SESSION_EMAIL_KEY, email);
            session.setAttribute(SESSION_USER_ID_KEY, user.getId());
            
            log.info("User {} successfully authorized via UI", email);

            return ResponseEntity.ok(Map.of(
                    "message", "Успешная авторизация",
                    "email", email
            ));
        } catch (Exception e) {
            log.error("Error authorizing user {}", email, e);
            return ResponseEntity.status(500)
                    .body(Map.of("message", "Ошибка при авторизации: " + e.getMessage()));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(HttpSession session) {
        String email = (String) session.getAttribute(SESSION_EMAIL_KEY);
        session.invalidate();
        log.info("User {} logged out", email);
        return ResponseEntity.ok(Map.of("message", "Успешный выход"));
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> getCurrentUser(HttpSession session) {
        String email = (String) session.getAttribute(SESSION_EMAIL_KEY);
        Long userId = (Long) session.getAttribute(SESSION_USER_ID_KEY);
        
        if (email == null) {
            return ResponseEntity.status(401)
                    .body(Map.of("message", "Не авторизован"));
        }
        
        return ResponseEntity.ok(Map.of(
                "email", email,
                "userId", userId != null ? userId : 0
        ));
    }
}

