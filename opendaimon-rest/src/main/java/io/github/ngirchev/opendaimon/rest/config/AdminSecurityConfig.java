package io.github.ngirchev.opendaimon.rest.config;

import io.github.ngirchev.opendaimon.rest.repository.RestUserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Minimal Spring Security config targeted at the admin panel only.
 * Preserves the existing open-by-default behaviour for non-admin endpoints
 * (REST chat, UI pages) — this was an explicit design choice to avoid breaking
 * the custom session-based auth already used elsewhere.
 */
@Configuration
@EnableMethodSecurity
public class AdminSecurityConfig {

    @Bean
    public SessionAdminAuthenticationFilter sessionAdminAuthenticationFilter(
            RestUserRepository restUserRepository) {
        return new SessionAdminAuthenticationFilter(restUserRepository);
    }

    @Bean
    public SecurityFilterChain adminSecurityFilterChain(
            HttpSecurity http,
            SessionAdminAuthenticationFilter sessionAdminAuthenticationFilter) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .addFilterBefore(sessionAdminAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/admin/**", "/admin").hasRole("ADMIN")
                        .anyRequest().permitAll()
                )
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable());
        return http.build();
    }
}
