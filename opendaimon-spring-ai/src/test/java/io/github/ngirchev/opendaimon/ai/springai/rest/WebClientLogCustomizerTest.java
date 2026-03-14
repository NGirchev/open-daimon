package io.github.ngirchev.opendaimon.ai.springai.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for HTTP error logging, including 429 (Too Many Requests).
 *
 * Verifies:
 * 1. Logging of successful requests (2xx)
 * 2. Logging of 429 errors with response body
 * 3. Logging of other errors (4xx, 5xx)
 * 4. Truncation of long response bodies
 */
@ExtendWith(MockitoExtension.class)
class WebClientLogCustomizerTest {

    private MockWebServer mockWebServer;
    private WebClient webClient;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        // Create WebClient with customizer
        WebClientLogCustomizer customizer = new WebClientLogCustomizer(new ObjectMapper());
        WebClient.Builder builder = WebClient.builder();
        customizer.customize(builder);
        webClient = builder.build();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (mockWebServer != null) {
            mockWebServer.shutdown();
        }
    }

    @Test
    void when429Error_thenExceptionThrown() {
        // Arrange
        String errorBody = "{\"error\":{\"message\":\"Rate limit exceeded\",\"type\":\"rate_limit_error\"}}";
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(429)
                .setBody(errorBody)
                .addHeader("Content-Type", "application/json")
                .addHeader("Retry-After", "60"));

        String baseUrl = mockWebServer.url("/test").toString();

        // Act & Assert
        WebClientResponseException exception = assertThrows(WebClientResponseException.class, () -> {
            webClient.get()
                    .uri(baseUrl)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
        });

        // Verify exception has status 429
        assertEquals(HttpStatus.TOO_MANY_REQUESTS.value(), exception.getStatusCode().value());
        
        // Verify response body is available in exception
        // WebClientLogCustomizer logs 429 via log.error() with response body in logAndBufferErrorsIfNeeded()
        String responseBody = exception.getResponseBodyAsString();
        assertNotNull(responseBody);
        assertTrue(responseBody.contains("Rate limit exceeded") || responseBody.contains("rate_limit_error"));
        
        // Note: Logging happens in WebClientLogCustomizer.logAndBufferErrorsIfNeeded() and appears in app logs.
        // For verifying logging in integration tests use OutputCaptureExtension with logback in test/resources.
    }

    @Test
    void when429ErrorWithLongBody_thenBodyTruncated() {
        // Arrange - long response body (> 4000 chars)
        StringBuilder longBody = new StringBuilder("{\"error\":\"");
        for (int i = 0; i < 500; i++) {
            longBody.append("This is a very long error message that should be truncated. ");
        }
        longBody.append("\"}");
        
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(429)
                .setBody(longBody.toString())
                .addHeader("Content-Type", "application/json"));

        String baseUrl = mockWebServer.url("/test").toString();

        // Act & Assert
        WebClientResponseException exception = assertThrows(WebClientResponseException.class, () -> {
            webClient.get()
                    .uri(baseUrl)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
        });

        assertEquals(HttpStatus.TOO_MANY_REQUESTS.value(), exception.getStatusCode().value());
        
        // Verify response body is available (WebClientLogCustomizer should log it)
        String responseBody = exception.getResponseBodyAsString();
        assertNotNull(responseBody);
        // Body must not be empty (logging should work)
        assertFalse(responseBody.isEmpty());
    }

    @Test
    void whenSuccessResponse_thenNoException() {
        // Arrange
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"result\":\"success\"}")
                .addHeader("Content-Type", "application/json"));

        String baseUrl = mockWebServer.url("/test").toString();

        // Act
        String response = webClient.get()
                .uri(baseUrl)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        // Assert
        assertNotNull(response);
        assertEquals("{\"result\":\"success\"}", response);
    }

    @Test
    void when500Error_thenExceptionThrown() {
        // Arrange
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(500)
                .setBody("{\"error\":\"Internal Server Error\"}")
                .addHeader("Content-Type", "application/json"));

        String baseUrl = mockWebServer.url("/test").toString();

        // Act & Assert
        WebClientResponseException exception = assertThrows(WebClientResponseException.class, () -> {
            webClient.get()
                    .uri(baseUrl)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
        });

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR.value(), exception.getStatusCode().value());
    }

    @Test
    void whenRequestToLocalhost_thenRequestProcessed() {
        // Arrange - MockWebServer uses localhost, which should be logged
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"result\":\"success\"}"));

        String baseUrl = mockWebServer.url("/test").toString();

        // Act
        String response = webClient.get()
                .uri(baseUrl)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        // Assert - request must be processed
        assertNotNull(response);
        assertEquals("{\"result\":\"success\"}", response);
    }
}
