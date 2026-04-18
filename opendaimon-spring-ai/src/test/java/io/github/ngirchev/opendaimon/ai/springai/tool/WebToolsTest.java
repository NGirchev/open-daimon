package io.github.ngirchev.opendaimon.ai.springai.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebToolsTest {

    @Mock
    private WebClient webClient;

    @Mock
    private WebClient.RequestHeadersUriSpec getSpec;

    @Mock
    private WebClient.RequestBodySpec postSpec;

    @Mock
    private WebClient.RequestHeadersSpec getRequestHeadersSpec;

    @Mock
    private WebClient.RequestHeadersSpec postRequestHeadersSpec;

    @Mock
    private WebClient.ResponseSpec responseSpec;

    private WebTools webTools;

    @BeforeEach
    void setUp() {
        webTools = new WebTools(webClient, "test-key", "https://serper.dev/search");
    }

    @Test
    void webSearch_whenApiKeyBlank_returnsEmptyResult() {
        WebTools noKeyTools = new WebTools(webClient, "   ", "https://example.com");
        var result = noKeyTools.webSearch("query");
        assertNotNull(result);
        assertEquals("query", result.query());
        assertTrue(result.hits().isEmpty());
        verify(webClient, never()).post();
    }

    @Test
    void webSearch_whenApiKeyNull_returnsEmptyResult() {
        WebTools noKeyTools = new WebTools(webClient, null, "https://example.com");
        var result = noKeyTools.webSearch("query");
        assertNotNull(result);
        assertTrue(result.hits().isEmpty());
    }

    @Test
    void fetchUrl_returnsCleanedText() {
        when(webClient.get()).thenReturn(getSpec);
        when(getSpec.uri(anyString())).thenReturn(getRequestHeadersSpec);
        when(getRequestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(eq(String.class)))
                .thenReturn(Mono.just("<html><head></head><body><p>Hello world</p></body></html>").timeout(Duration.ofSeconds(6)));

        String result = webTools.fetchUrl("https://example.com");

        assertNotNull(result);
        assertTrue(result.contains("Hello world"));
    }

    @Test
    void fetchUrl_whenResponseEmpty_returnsEmptyString() {
        when(webClient.get()).thenReturn(getSpec);
        when(getSpec.uri(anyString())).thenReturn(getRequestHeadersSpec);
        when(getRequestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(eq(String.class))).thenReturn(Mono.just("").timeout(Duration.ofSeconds(6)));

        String result = webTools.fetchUrl("https://empty.com");

        assertEquals("", result);
    }

    @Test
    void shouldReturnHttpErrorStringWhenUpstreamReturns403() {
        // WebClient pipeline bubbles up a 403 — fetchUrl must now return a structured
        // "HTTP error <code> <status>" string so the Spring agent layer maps it to
        // AppendObservation(FAILED, ...) instead of "📋 Tool result received".
        WebClientResponseException forbidden = WebClientResponseException.create(
                HttpStatus.FORBIDDEN.value(), "Forbidden", null, null, null);
        when(webClient.get()).thenReturn(getSpec);
        when(getSpec.uri(anyString())).thenReturn(getRequestHeadersSpec);
        when(getRequestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(eq(String.class)))
                .thenReturn(Mono.error(forbidden));

        String result = webTools.fetchUrl("https://researchgate.net/blocked");

        assertEquals("HTTP error 403 Forbidden", result);
    }

    @Test
    void shouldReturnErrorStringWhenUpstreamThrowsGenericException() {
        // Any non-WebClientResponseException bubbling up — connect timeout, DNS
        // failure, etc. — must be surfaced as "Error: <message>" so the textual-
        // failure heuristic picks it up.
        when(webClient.get()).thenReturn(getSpec);
        when(getSpec.uri(anyString())).thenReturn(getRequestHeadersSpec);
        when(getRequestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(eq(String.class)))
                .thenReturn(Mono.error(new RuntimeException("boom")));

        String result = webTools.fetchUrl("https://down.example.com");

        assertEquals("Error: boom", result);
    }

    @Test
    void shouldReturnEmptyStringWhenResponseBodyIsBlank() {
        // 200 OK with empty body is not an error — the tool returns "" and the agent
        // layer maps it to "📋 No result" through the regular success-observation path.
        when(webClient.get()).thenReturn(getSpec);
        when(getSpec.uri(anyString())).thenReturn(getRequestHeadersSpec);
        when(getRequestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(eq(String.class)))
                .thenReturn(Mono.just("   ").timeout(Duration.ofSeconds(6)));

        String result = webTools.fetchUrl("https://blank.example.com");

        assertEquals("", result);
    }

    @Test
    void shouldReturnEmptyStringWhenUrlInvalid() {
        // URL validation is a pre-flight check — no network call is made at all.
        String result = webTools.fetchUrl("ftp://example.com/resource");

        assertEquals("", result);
        verify(webClient, never()).get();
    }
}
