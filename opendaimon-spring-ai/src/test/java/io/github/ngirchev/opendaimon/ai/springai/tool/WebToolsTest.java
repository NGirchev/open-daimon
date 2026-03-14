package io.github.ngirchev.opendaimon.ai.springai.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
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
}
