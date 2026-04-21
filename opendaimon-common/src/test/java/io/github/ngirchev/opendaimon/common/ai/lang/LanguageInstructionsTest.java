package io.github.ngirchev.opendaimon.common.ai.lang;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LanguageInstructionsTest {

    @Test
    void shouldReturnEnglishNameWhenCodeIsRu() {
        Optional<String> result = LanguageInstructions.displayName("ru");

        assertTrue(result.isPresent());
        assertEquals("Russian", result.get());
    }

    @Test
    void shouldReturnEnglishNameWhenCodeIsBcp47WithRegion() {
        Optional<String> zhHans = LanguageInstructions.displayName("zh-Hans");
        Optional<String> ptBr = LanguageInstructions.displayName("pt-BR");

        assertTrue(zhHans.isPresent());
        assertEquals("Chinese", zhHans.get());

        assertTrue(ptBr.isPresent());
        assertEquals("Portuguese", ptBr.get());
    }

    @Test
    void shouldReturnEnglishNameForLessCommonLanguages() {
        Optional<String> uk = LanguageInstructions.displayName("uk");
        Optional<String> ja = LanguageInstructions.displayName("ja");

        assertTrue(uk.isPresent());
        assertEquals("Ukrainian", uk.get());

        assertTrue(ja.isPresent());
        assertEquals("Japanese", ja.get());
    }

    @Test
    void shouldReturnEmptyWhenCodeIsNull() {
        assertTrue(LanguageInstructions.displayName(null).isEmpty());
    }

    @Test
    void shouldReturnEmptyWhenCodeIsBlank() {
        assertTrue(LanguageInstructions.displayName("").isEmpty());
        assertTrue(LanguageInstructions.displayName("   ").isEmpty());
    }

    @Test
    void shouldFallbackToCodeWhenUnresolvable() {
        // JDK always resolves forLanguageTag to at least a Locale with the language subtag as display name.
        // For a private-use tag like "xxx", getDisplayLanguage returns "xxx" — the code itself.
        Optional<String> result = LanguageInstructions.displayName("xxx");

        assertTrue(result.isPresent());
        assertEquals("xxx", result.get());
    }
}
