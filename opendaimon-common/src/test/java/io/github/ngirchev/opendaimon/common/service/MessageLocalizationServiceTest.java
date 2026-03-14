package io.github.ngirchev.opendaimon.common.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageLocalizationServiceTest {

    @Mock
    private MessageSource messageSource;

    private MessageLocalizationService service;

    @BeforeEach
    void setUp() {
        service = new MessageLocalizationService(messageSource);
    }

    @Nested
    @DisplayName("getMessage")
    class GetMessage {

        @Test
        void whenLanguageCodeProvided_usesItForLocale() {
            when(messageSource.getMessage(eq("common.error"), any(), eq("common.error"), eq(Locale.ENGLISH)))
                    .thenReturn("Error message");

            String result = service.getMessage("common.error", "en");

            assertEquals("Error message", result);
            verify(messageSource).getMessage(eq("common.error"), any(), eq("common.error"), eq(Locale.ENGLISH));
        }

        @Test
        void whenLanguageCodeNull_usesDefaultLocale() {
            Locale defaultLocale = Locale.forLanguageTag("ru");
            when(messageSource.getMessage(eq("key"), any(), eq("key"), eq(defaultLocale))).thenReturn("Fallback");

            String result = service.getMessage("key", null);

            assertEquals("Fallback", result);
            verify(messageSource).getMessage(eq("key"), any(), eq("key"), eq(defaultLocale));
        }

        @Test
        void whenLanguageCodeBlank_usesDefaultLocale() {
            Locale defaultLocale = Locale.forLanguageTag("ru");
            when(messageSource.getMessage(eq("key"), any(), eq("key"), eq(defaultLocale))).thenReturn("Value");

            String result = service.getMessage("key", "   ");

            assertEquals("Value", result);
            verify(messageSource).getMessage(eq("key"), any(), eq("key"), eq(defaultLocale));
        }

        @Test
        void whenArgsProvided_passesThemToMessageSource() {
            when(messageSource.getMessage(eq("greeting"), any(), eq("greeting"), eq(Locale.ENGLISH))).thenReturn("Hello, John");

            String result = service.getMessage("greeting", "en", "John");

            assertEquals("Hello, John", result);
            verify(messageSource).getMessage(eq("greeting"), any(), eq("greeting"), eq(Locale.ENGLISH));
        }

        @Test
        void whenLanguageCodeHasHyphen_usesFirstPartForLocale() {
            when(messageSource.getMessage(eq("key"), any(), eq("key"), eq(Locale.ENGLISH))).thenReturn("Resolved");

            String result = service.getMessage("key", "en-US");

            assertEquals("Resolved", result);
            verify(messageSource).getMessage(eq("key"), any(), eq("key"), eq(Locale.ENGLISH));
        }
    }
}
