package io.github.ngirchev.opendaimon.ai.ui.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Controller for UI pages
 */
@Controller
public class PageController {

    private static final String SESSION_EMAIL_KEY = "userEmail";

    @GetMapping("/")
    public String index(HttpSession session) {
        // Check if user is authenticated
        String email = (String) session.getAttribute(SESSION_EMAIL_KEY);
        if (email == null || email.isBlank()) {
            return "redirect:/login";
        }
        return "redirect:/chat";
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @GetMapping("/chat")
    public String chat(HttpSession session) {
        // Check if user is authenticated
        String email = (String) session.getAttribute(SESSION_EMAIL_KEY);
        if (email == null || email.isBlank()) {
            return "redirect:/login";
        }
        return "chat";
    }
}

