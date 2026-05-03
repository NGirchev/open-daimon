package io.github.ngirchev.opendaimon.common.ai.lang;

import java.util.Locale;
import java.util.Optional;

/**
 * Utility for resolving human-readable language names from ISO 639 / BCP 47 codes.
 * Uses {@link Locale#getDisplayLanguage(Locale)} so the full JDK language table is supported,
 * not a hardcoded subset.
 */
public final class LanguageInstructions {

    private LanguageInstructions() {
    }

    /**
     * Resolves an English display name for the given language code.
     *
     * @param languageCode ISO 639 / BCP 47 code (e.g. "ru", "zh-Hans", "pt-BR")
     * @return display name in English (e.g. "Russian"), or the original code if the JDK
     *         cannot resolve it, or {@link Optional#empty()} if the input is null or blank
     */
    public static Optional<String> displayName(String languageCode) {
        if (languageCode == null || languageCode.isBlank()) {
            return Optional.empty();
        }
        Locale locale = Locale.forLanguageTag(languageCode);
        String name = locale.getDisplayLanguage(Locale.ENGLISH);
        if (name == null || name.isBlank()) {
            return Optional.of(languageCode);
        }
        return Optional.of(name);
    }
}
