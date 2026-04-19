package io.github.ngirchev.opendaimon.ai.springai.tool;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
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
        lenient().when(getRequestHeadersSpec.headers(any())).thenReturn(getRequestHeadersSpec);
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
    void fetchUrl_sendsBrowserLikeHeaders() throws Exception {
        MockWebServer server = startServer();
        try {
            server.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/html")
                    .setBody("<html><body><p>Hello world</p></body></html>"));
            WebTools realWebTools = new WebTools(WebClient.builder().build(), "test-key", "https://serper.dev/search");

            String result = realWebTools.fetchUrl(server.url("/article").toString());

            RecordedRequest request = takeRequest(server);
            assertThat(result).contains("Hello world");
            assertThat(request.getHeader("User-Agent")).contains("Mozilla/5.0");
            assertThat(request.getHeader("Accept")).contains("text/html");
            assertThat(request.getHeader("Accept-Language")).isEqualTo("en-US,en;q=0.9");
        } finally {
            server.shutdown();
        }
    }

    @Test
    void fetchUrl_retriesCloudflareChallenge403OnceWithServiceUserAgent() throws Exception {
        MockWebServer server = startServer();
        try {
            server.enqueue(new MockResponse()
                    .setResponseCode(403)
                    .setHeader("cf-mitigated", "challenge")
                    .setBody("blocked"));
            server.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/html")
                    .setBody("<html><body><main>Readable fallback page</main></body></html>"));
            WebTools realWebTools = new WebTools(WebClient.builder().build(), "test-key", "https://serper.dev/search");

            String result = realWebTools.fetchUrl(server.url("/cloudflare").toString());

            RecordedRequest first = takeRequest(server);
            RecordedRequest second = takeRequest(server);
            assertThat(result).contains("Readable fallback page");
            assertThat(first.getHeader("User-Agent")).contains("Mozilla/5.0");
            assertThat(second.getHeader("User-Agent")).isEqualTo("OpenDaimonWebFetch/1.0");
            assertThat(server.getRequestCount()).isEqualTo(2);
        } finally {
            server.shutdown();
        }
    }

    @Test
    void fetchUrl_doesNotRetryRegular403() throws Exception {
        MockWebServer server = startServer();
        try {
            server.enqueue(new MockResponse()
                    .setResponseCode(403)
                    .setBody("blocked"));
            WebTools realWebTools = new WebTools(WebClient.builder().build(), "test-key", "https://serper.dev/search");

            String result = realWebTools.fetchUrl(server.url("/regular-403").toString());

            assertThat(result).isEqualTo("HTTP error 403 Forbidden");
            assertThat(server.getRequestCount()).isEqualTo(1);
        } finally {
            server.shutdown();
        }
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
    void shouldReturnStructuredInvalidUrlReasonWhenUrlNotHttp() {
        // URL validation is a pre-flight check — no network call is made at all.
        String result = webTools.fetchUrl("ftp://example.com/resource");

        assertThat(result).startsWith("Error: " + WebTools.REASON_INVALID_URL);
        verify(webClient, never()).get();
    }

    @Test
    void shouldReturnStructuredErrorWhenBodyDecodingFailsOn2xx() {
        // WebClient.bodyToMono can raise a WebClientResponseException with a 2xx status
        // when the body exceeds the codec memory limit (DataBufferLimitException) or fails
        // to decode. The raw "HTTP error 200 OK" string is absurd and confuses the agent
        // into retry loops — surface a distinct REASON_UNREADABLE_2XX instead so observe()
        // classifies it as FAILED and the model tries a different URL.
        WebClientResponseException okButUndecodable = WebClientResponseException.create(
                HttpStatus.OK.value(), "OK", null, null, null);
        when(webClient.get()).thenReturn(getSpec);
        when(getSpec.uri(anyString())).thenReturn(getRequestHeadersSpec);
        when(getRequestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(eq(String.class)))
                .thenReturn(Mono.error(okButUndecodable));

        String result = webTools.fetchUrl("https://hackernoon.com/huge-article");

        assertThat(result)
                .startsWith("Error: " + WebTools.REASON_UNREADABLE_2XX)
                .contains("https://hackernoon.com/huge-article");
    }

    @Test
    void shouldReturnStructuredTooLargeReasonWhenBufferLimitExceeded() {
        // When codec maxInMemorySize is exceeded mid-stream, WebClient propagates a
        // DataBufferLimitException (sometimes wrapped). Without REASON_TOO_LARGE the
        // generic "Error: <class>" message pushes the agent into an unhelpful retry
        // loop on the same URL; structured reason lets observe() fail-fast and the
        // model pick a smaller page.
        when(webClient.get()).thenReturn(getSpec);
        when(getSpec.uri(anyString())).thenReturn(getRequestHeadersSpec);
        when(getRequestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(eq(String.class)))
                .thenReturn(Mono.error(new org.springframework.core.io.buffer.DataBufferLimitException(
                        "Exceeded limit of 2097152 bytes")));

        String result = webTools.fetchUrl("https://example.com/10mb.html");

        assertThat(result).startsWith("Error: " + WebTools.REASON_TOO_LARGE);
    }

    private static MockWebServer startServer() throws IOException {
        MockWebServer server = new MockWebServer();
        server.start();
        return server;
    }

    private static RecordedRequest takeRequest(MockWebServer server) throws InterruptedException {
        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        return request;
    }
}
