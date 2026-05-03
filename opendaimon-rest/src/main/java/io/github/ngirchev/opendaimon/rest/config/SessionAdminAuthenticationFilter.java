package io.github.ngirchev.opendaimon.rest.config;

import io.github.ngirchev.opendaimon.rest.model.RestUser;
import io.github.ngirchev.opendaimon.rest.repository.RestUserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Translates the existing custom HTTP-session auth (userEmail attribute set by
 * {@code UIAuthController}) into a Spring Security authentication with ROLE_ADMIN
 * when the user has isAdmin=true.
 *
 * <p>Runs only for admin-scoped paths to avoid interfering with existing
 * {@code /api/v1/session/**}, {@code /api/v1/ui/**}, {@code /chat} flows.
 */
@Slf4j
@RequiredArgsConstructor
public class SessionAdminAuthenticationFilter extends OncePerRequestFilter {

    private static final String SESSION_EMAIL_KEY = "userEmail";
    private static final String ROLE_ADMIN = "ROLE_ADMIN";

    private final RestUserRepository restUserRepository;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null || !(path.startsWith("/api/v1/admin") || path.equals("/admin"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session == null) {
            filterChain.doFilter(request, response);
            return;
        }
        Object emailAttr = session.getAttribute(SESSION_EMAIL_KEY);
        if (!(emailAttr instanceof String email) || email.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }
        Optional<RestUser> userOpt = restUserRepository.findByEmail(email);
        if (userOpt.isEmpty()) {
            log.debug("Admin path access denied: no rest user for email={}", email);
            filterChain.doFilter(request, response);
            return;
        }
        RestUser user = userOpt.get();
        if (!Boolean.TRUE.equals(user.getIsAdmin())) {
            log.debug("Admin path access denied: user {} is not admin", email);
            filterChain.doFilter(request, response);
            return;
        }
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                email, null, List.of(new SimpleGrantedAuthority(ROLE_ADMIN)));
        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(auth);
        filterChain.doFilter(request, response);
    }
}
