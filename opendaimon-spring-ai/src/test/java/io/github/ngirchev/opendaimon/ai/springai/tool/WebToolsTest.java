package io.github.ngirchev.opendaimon.ai.springai.tool;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class WebToolsTest {

    private MockWebServer mockWebServer;
    private WebTools webTools;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        webTools = new WebTools(
                WebClient.builder().build(),
                "test-key",
                mockWebServer.url("/search").toString(),
                1_048_576
        );
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    @Test
    void webSearch_whenApiKeyBlank_returnsEmptyResult() {
        WebTools noKeyTools = new WebTools(WebClient.builder().build(), "   ", "https://example.com");

        WebTools.SearchResult result = noKeyTools.webSearch("query");

        assertThat(result).isNotNull();
        assertThat(result.query()).isEqualTo("query");
        assertThat(result.hits()).isEmpty();
    }

    @Test
    void webSearch_whenApiKeyNull_returnsEmptyResult() {
        WebTools noKeyTools = new WebTools(WebClient.builder().build(), null, "https://example.com");

        WebTools.SearchResult result = noKeyTools.webSearch("query");

        assertThat(result).isNotNull();
        assertThat(result.hits()).isEmpty();
    }

    @Test
    void fetchUrl_returnsCleanedText() {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("<html><body><script>ignore me</script><p>Hello world</p></body></html>")
                .addHeader("Content-Type", "text/html"));

        String result = webTools.fetchUrl(mockWebServer.url("/article").toString());

        assertThat(result).contains("Hello world");
        assertThat(result).doesNotContain("ignore me");
    }

    @Test
    void fetchUrl_whenResponseEmpty_returnsErrorText() {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("")
                .addHeader("Content-Type", "text/html"));
        String url = mockWebServer.url("/empty").toString();

        String result = webTools.fetchUrl(url);

        assertThat(result).contains("EMPTY_RESPONSE");
        assertThat(result).contains(url);
    }

    @Test
    void fetchUrl_whenUrlInvalid_returnsErrorText() {
        String result = webTools.fetchUrl("ftp://example.com/file");

        assertThat(result).isEqualTo("fetch_url failed: INVALID_URL for ftp://example.com/file");
    }

    @Test
    void fetchUrl_whenHttpError_returnsErrorText() {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(403)
                .setBody("blocked")
                .addHeader("Content-Type", "text/plain"));
        String url = mockWebServer.url("/blocked").toString();

        String result = webTools.fetchUrl(url);

        assertThat(result).contains("HTTP 403");
        assertThat(result).contains(url);
    }

    @Test
    void fetchUrl_whenResponseExceedsMaxFetchBytes_returnsTooLargeReason() {
        WebTools limitedWebTools = new WebTools(
                WebClient.builder().build(),
                "test-key",
                mockWebServer.url("/search").toString(),
                128
        );
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("<html><body>" + "A".repeat(600) + "</body></html>")
                .addHeader("Content-Type", "text/html"));

        String result = limitedWebTools.fetchUrl(mockWebServer.url("/large").toString());

        assertThat(result).contains("TOO_LARGE");
    }

    @Test
    void fetchUrl_whenHttp200BodyUnreadable_returnsUnreadable2xxReason() {
        WebClient failingWebClient = WebClient.builder()
                .filter((request, next) -> Mono.error(WebClientResponseException.create(
                        200,
                        "OK",
                        HttpHeaders.EMPTY,
                        new byte[0],
                        StandardCharsets.UTF_8
                )))
                .build();
        WebTools failingWebTools = new WebTools(failingWebClient, "test-key", "https://example.com", 1024);

        String result = failingWebTools.fetchUrl("https://example.com/page");

        assertThat(result).contains("UNREADABLE_2XX");
    }
}
