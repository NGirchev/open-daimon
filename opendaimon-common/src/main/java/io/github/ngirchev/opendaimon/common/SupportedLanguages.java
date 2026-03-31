package io.github.ngirchev.opendaimon.common;

import java.util.Set;

/**
 * Global language constants used across modules for validation, defaults, and menu rendering.
 */
public final class SupportedLanguages {

    public static final String RU = "ru";
    public static final String EN = "en";
    public static final String DEFAULT_LANGUAGE = EN;
    public static final Set<String> SUPPORTED_LANGUAGES = Set.of(RU, EN);

    private SupportedLanguages() {
    }
}
