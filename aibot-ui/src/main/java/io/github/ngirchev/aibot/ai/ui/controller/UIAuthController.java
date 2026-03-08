package io.github.ngirchev.aibot.ai.ui.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import io.github.ngirchev.aibot.common.service.MessageLocalizationService;
import io.github.ngirchev.aibot.rest.model.RestUser;
import io.github.ngirchev.aibot.rest.service.RestUserService;

import java.util.Map;

/**
 * Controller for UI user authentication.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ui")
@RequiredArgsConstructor
public class UIAuthController {

    private final RestUserService restUserService;
    private final MessageLocalizationService messageLocalizationService;

    private static final String SESSION_EMAIL_KEY = "userEmail";
    private static final String SESSION_USER_ID_KEY = "userId";

    private String lang(HttpServletRequest request) {
        return request != null && request.getLocale() != null ? request.getLocale().getLanguage() : "ru";
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, String>> login(@RequestBody Map<String, String> request, HttpSession session, HttpServletRequest httpRequest) {
        String email = request.get("email");
        String languageCode = lang(httpRequest);
        if (email == null || email.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", messageLocalizationService.getMessage("ui.auth.email.required", languageCode)));
        }
        try {
            RestUser user = restUserService.getOrCreateUser(email);
            session.setAttribute(SESSION_EMAIL_KEY, email);
            session.setAttribute(SESSION_USER_ID_KEY, user.getId());
            log.info("User {} successfully authorized via UI", email);
            return ResponseEntity.ok(Map.of(
                    "message", messageLocalizationService.getMessage("ui.auth.success", languageCode),
                    "email", email
            ));
        } catch (Exception e) {
            log.error("Error authorizing user {}", email, e);
            return ResponseEntity.status(500)
                    .body(Map.of("message", messageLocalizationService.getMessage("ui.auth.error", languageCode, e.getMessage())));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(HttpSession session, HttpServletRequest httpRequest) {
        String email = (String) session.getAttribute(SESSION_EMAIL_KEY);
        session.invalidate();
        log.info("User {} logged out", email);
        return ResponseEntity.ok(Map.of("message", messageLocalizationService.getMessage("ui.logout.success", lang(httpRequest))));
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> getCurrentUser(HttpSession session, HttpServletRequest httpRequest) {
        String email = (String) session.getAttribute(SESSION_EMAIL_KEY);
        Long userId = (Long) session.getAttribute(SESSION_USER_ID_KEY);
        if (email == null) {
            return ResponseEntity.status(401)
                    .body(Map.of("message", messageLocalizationService.getMessage("ui.auth.not.authenticated", lang(httpRequest))));
        }
        return ResponseEntity.ok(Map.of(
                "email", email,
                "userId", userId != null ? userId : 0
        ));
    }
}

