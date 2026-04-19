package io.github.ngirchev.opendaimon.ai.springai.tool;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class UrlLivenessCheckerImplTest {

    private MockWebServer mockWebServer;
    private UrlLivenessCheckerImpl checker;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        checker = new UrlLivenessCheckerImpl(
                WebClient.builder().build(),
                Duration.ofSeconds(2),
                5,
                Duration.ofMinutes(10)
        );
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    @Test
    void shouldReturnTrueWhenHeadReturns200() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(200));

        boolean live = checker.isLive(mockWebServer.url("/ok").toString());

        assertThat(live).isTrue();
    }

    @Test
    void shouldReturnFalseWhenHeadReturns404() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(404));

        boolean live = checker.isLive(mockWebServer.url("/missing").toString());

        assertThat(live).isFalse();
    }

    @Test
    void shouldReturnFalseWhenHeadTimesOut() {
        UrlLivenessCheckerImpl tightChecker = new UrlLivenessCheckerImpl(
                WebClient.builder().build(),
                Duration.ofMillis(200),
                5,
                Duration.ofMinutes(10)
        );
        // HEAD has no body, so setBodyDelay has no effect. NO_RESPONSE keeps the
        // TCP socket open without sending status line, forcing the client side to
        // hit its read timeout.
        mockWebServer.enqueue(new MockResponse()
                .setSocketPolicy(SocketPolicy.NO_RESPONSE));

        boolean live = tightChecker.isLive(mockWebServer.url("/slow").toString());

        assertThat(live).isFalse();
    }

    @Test
    void shouldFallBackToRangedGetWhenHeadReturns405() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(405));
        mockWebServer.enqueue(new MockResponse().setResponseCode(206));

        boolean live = checker.isLive(mockWebServer.url("/no-head").toString());

        assertThat(live).isTrue();
    }

    @Test
    void shouldReturnFalseWhenHeadReturns405AndRangedGetReturns404() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(405));
        mockWebServer.enqueue(new MockResponse().setResponseCode(404));

        boolean live = checker.isLive(mockWebServer.url("/no-head-gone").toString());

        assertThat(live).isFalse();
    }

    @Test
    void shouldStripDeadMarkdownLinksLeavingAnchorText() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(200)); // live
        mockWebServer.enqueue(new MockResponse().setResponseCode(404)); // dead
        String liveUrl = mockWebServer.url("/live").toString();
        String deadUrl = mockWebServer.url("/dead").toString();
        String text = "See [live guide](" + liveUrl + ") and [dead guide](" + deadUrl + ") for details.";

        String sanitized = checker.stripDeadLinks(text);

        assertThat(sanitized).contains("[live guide](" + liveUrl + ")");
        assertThat(sanitized).doesNotContain(deadUrl);
        assertThat(sanitized).contains("dead guide");
        assertThat(sanitized).doesNotContain("[dead guide]");
    }

    @Test
    void shouldReplaceBareDeadUrlWithUnavailableMarker() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(404));
        String deadUrl = mockWebServer.url("/gone").toString();
        String text = "Reference: " + deadUrl + " — see above.";

        String sanitized = checker.stripDeadLinks(text);

        assertThat(sanitized).doesNotContain(deadUrl);
        assertThat(sanitized).contains("(ссылка недоступна)");
    }

    @Test
    void shouldServeSecondIsLiveCallFromCacheWithoutHttpRequest() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(200));
        String url = mockWebServer.url("/cached").toString();

        boolean firstCall = checker.isLive(url);
        boolean secondCall = checker.isLive(url);

        assertThat(firstCall).isTrue();
        assertThat(secondCall).isTrue();
        // Only the first call should have hit the mock server; no enqueued response for the second.
        assertThat(mockWebServer.getRequestCount()).isEqualTo(1);
    }

    @Test
    void shouldCacheNegativeResultAndSkipSecondHttpRequest() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(404));
        String url = mockWebServer.url("/not-there").toString();

        boolean firstCall = checker.isLive(url);
        boolean secondCall = checker.isLive(url);

        assertThat(firstCall).isFalse();
        assertThat(secondCall).isFalse();
        assertThat(mockWebServer.getRequestCount()).isEqualTo(1);
    }
}
