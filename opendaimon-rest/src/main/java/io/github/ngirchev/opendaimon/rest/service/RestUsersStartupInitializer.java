package io.github.ngirchev.opendaimon.rest.service;

import io.github.ngirchev.opendaimon.bulkhead.model.UserPriority;
import io.github.ngirchev.opendaimon.rest.config.RestProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * On application startup, ensures all REST users from access config (admin/vip/regular emails)
 * exist in the database with flags set by level. Priority: ADMIN &gt; VIP &gt; REGULAR.
 */
@Slf4j
@RequiredArgsConstructor
public class RestUsersStartupInitializer {

    private final RestUserService restUserService;
    private final RestProperties restProperties;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void onApplicationReady() {
        if (restProperties == null || restProperties.getAccess() == null) {
            log.debug("REST access config not available, skipping REST users startup initialization");
            return;
        }

        Map<String, UserPriority> emailToLevel = collectEmailsWithEffectiveLevel();
        if (emailToLevel.isEmpty()) {
            log.info("REST users startup: no configured emails in access config");
            return;
        }

        log.info("REST users startup: initializing {} user(s) from config (admin/vip/regular emails)", emailToLevel.size());
        int createdOrUpdated = 0;
        for (Map.Entry<String, UserPriority> e : emailToLevel.entrySet()) {
            try {
                restUserService.ensureUserWithLevel(e.getKey(), e.getValue());
                createdOrUpdated++;
            } catch (Exception ex) {
                log.warn("REST users startup: failed to ensure user email='{}', level={}", e.getKey(), e.getValue(), ex);
            }
        }
        log.info("REST users startup: completed, {} user(s) ensured", createdOrUpdated);
    }

    /**
     * Collects all emails from admin, vip, regular with effective level. Priority: ADMIN &gt; VIP &gt; REGULAR.
     */
    private Map<String, UserPriority> collectEmailsWithEffectiveLevel() {
        Map<String, UserPriority> map = new HashMap<>();
        var access = restProperties.getAccess();

        Set<String> adminEmails = safeEmails(access.getAdmin());
        Set<String> vipEmails = safeEmails(access.getVip());
        Set<String> regularEmails = safeEmails(access.getRegular());

        for (String email : adminEmails) {
            if (email != null && !email.isBlank()) {
                map.put(email.trim(), UserPriority.ADMIN);
            }
        }
        for (String email : vipEmails) {
            if (email != null && !email.isBlank()) {
                map.putIfAbsent(email.trim(), UserPriority.VIP);
            }
        }
        for (String email : regularEmails) {
            if (email != null && !email.isBlank()) {
                map.putIfAbsent(email.trim(), UserPriority.REGULAR);
            }
        }
        return map;
    }

    private static Set<String> safeEmails(RestProperties.AccessConfig.LevelConfig level) {
        if (level == null || level.getEmails() == null) {
            return Set.of();
        }
        return level.getEmails().stream()
                .filter(e -> e != null && !e.isBlank())
                .map(String::trim)
                .collect(Collectors.toSet());
    }
}
