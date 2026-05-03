package io.github.ngirchev.opendaimon.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Probe endpoint used by the UI to decide whether to show the "Admin" link.
 * Returns 200 only after passing {@code ROLE_ADMIN}; any other caller gets 401/403
 * handled by Spring Security defaults.
 */
@RestController
@RequestMapping("/api/v1/admin/me")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin Me Controller", description = "Check current user has admin access")
public class AdminMeController {

    private static final String SESSION_EMAIL_KEY = "userEmail";

    @GetMapping
    @Operation(summary = "Return admin identity for the current session")
    public ResponseEntity<Map<String, Object>> me(HttpSession session) {
        Object email = session != null ? session.getAttribute(SESSION_EMAIL_KEY) : null;
        return ResponseEntity.ok(Map.of(
                "email", email != null ? email.toString() : "",
                "isAdmin", Boolean.TRUE
        ));
    }
}
