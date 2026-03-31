package io.github.ngirchev.opendaimon.common.service;

import org.springframework.context.MessageSource;

import java.util.Locale;

import static io.github.ngirchev.opendaimon.common.SupportedLanguages.DEFAULT_LANGUAGE;

/**
 * Resolves user-facing messages by code and language.
 * Uses Spring MessageSource with locale derived from language code (e.g. from User or Accept-Language).
 */
public class MessageLocalizationService {

    private final MessageSource messageSource;

    public MessageLocalizationService(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    /**
     * Resolves message by code for the given language. Falls back to default locale if code is missing.
     *
     * @param code         message key (e.g. common.error.access.denied)
     * @param languageCode user language (e.g. ru, en, uk); null/blank -> default (ru)
     * @param args         optional format arguments
     * @return localized message
     */
    public String getMessage(String code, String languageCode, Object... args) {
        Locale locale = resolveLocale(languageCode);
        return messageSource.getMessage(code, args, code, locale);
    }

    private Locale resolveLocale(String languageCode) {
        if (languageCode == null || languageCode.isBlank()) {
            return Locale.forLanguageTag(DEFAULT_LANGUAGE);
        }
        String lang = languageCode.toLowerCase().split("-")[0];
        return Locale.forLanguageTag(lang);
    }
}
