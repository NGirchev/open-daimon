package io.github.ngirchev.opendaimon.rest.config;

import io.github.ngirchev.opendaimon.rest.model.RestUser;
import io.github.ngirchev.opendaimon.rest.repository.RestUserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionAdminAuthenticationFilterTest {

    @Mock
    private RestUserRepository restUserRepository;

    private SessionAdminAuthenticationFilter filter;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new SessionAdminAuthenticationFilter(restUserRepository);
        chain = mock(FilterChain.class);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldSkipForNonAdminPaths() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/session");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(req, resp);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void shouldProceedWithoutAuthWhenNoSession() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/admin/conversations");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(req, resp);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void shouldProceedWithoutAuthWhenEmailMissingInSession() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/admin/conversations");
        req.setSession(new org.springframework.mock.web.MockHttpSession());
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(req, resp);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void shouldProceedWithoutAuthWhenUserNotFound() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/admin/conversations");
        HttpSession session = new org.springframework.mock.web.MockHttpSession();
        session.setAttribute("userEmail", "ghost@test.com");
        req.setSession(session);
        when(restUserRepository.findByEmail("ghost@test.com")).thenReturn(Optional.empty());
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(req, resp);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void shouldProceedWithoutAuthWhenUserNotAdmin() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/admin/conversations");
        HttpSession session = new org.springframework.mock.web.MockHttpSession();
        session.setAttribute("userEmail", "plain@test.com");
        req.setSession(session);
        RestUser user = new RestUser();
        user.setEmail("plain@test.com");
        user.setIsAdmin(false);
        when(restUserRepository.findByEmail("plain@test.com")).thenReturn(Optional.of(user));
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(req, resp);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void shouldSetRoleAdminAuthenticationWhenUserIsAdmin() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/admin/conversations");
        HttpSession session = new org.springframework.mock.web.MockHttpSession();
        session.setAttribute("userEmail", "boss@test.com");
        req.setSession(session);
        RestUser admin = new RestUser();
        admin.setEmail("boss@test.com");
        admin.setIsAdmin(true);
        when(restUserRepository.findByEmail("boss@test.com")).thenReturn(Optional.of(admin));
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(req, resp);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isEqualTo("boss@test.com");
        assertThat(auth.getAuthorities()).anySatisfy(a -> assertThat(a.getAuthority()).isEqualTo("ROLE_ADMIN"));
    }

    @Test
    void shouldSkipForAdminPathThatIsNotUnderPrefix() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/admin-of-nothing");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(req, resp);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
