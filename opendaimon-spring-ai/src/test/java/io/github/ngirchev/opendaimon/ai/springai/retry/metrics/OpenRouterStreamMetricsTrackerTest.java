package io.github.ngirchev.opendaimon.ai.springai.retry.metrics;

import io.github.ngirchev.opendaimon.ai.springai.retry.OpenRouterModelStatsRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.longThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for OpenRouterStreamMetricsTracker.
 */
@ExtendWith(MockitoExtension.class)
class OpenRouterStreamMetricsTrackerTest {

    private static final String MODEL_ID = "openai/gpt-4";

    @Mock
    private OpenRouterModelStatsRecorder recorder;

    @Mock
    private ObjectProvider<OpenRouterModelStatsRecorder> recorderProvider;

    private OpenRouterStreamMetricsTracker tracker;

    @BeforeEach
    void setUp() {
        when(recorderProvider.getIfAvailable()).thenReturn(recorder);
        tracker = new OpenRouterStreamMetricsTracker(recorderProvider);
    }

    @Test
    void whenRecorderNull_thenSourceFluxReturnedUnchanged() {
        when(recorderProvider.getIfAvailable()).thenReturn(null);
        OpenRouterStreamMetricsTracker t = new OpenRouterStreamMetricsTracker(recorderProvider);
        Flux<String> source = Flux.just("a", "b");

        Flux<String> result = t.track(MODEL_ID, source);

        List<String> items = result.collectList().block();
        assertNotNull(items);
        assertEquals(List.of("a", "b"), items);
        verifyNoInteractions(recorder);
    }

    @Test
    void whenModelIdNull_thenSourceFluxReturnedUnchanged() {
        Flux<String> source = Flux.just("a");

        Flux<String> result = tracker.track(null, source);

        assertEquals("a", result.blockFirst());
        verifyNoInteractions(recorder);
    }

    @Test
    void whenStreamCompletesSuccessfully_thenRecordSuccessCalled() {
        Flux<String> source = Flux.just("chunk1", "chunk2");

        Flux<String> result = tracker.track(MODEL_ID, source);

        assertEquals(List.of("chunk1", "chunk2"), result.collectList().block());

        verify(recorder).recordSuccess(eq(MODEL_ID), longThat(ms -> ms >= 0));
        verifyNoMoreInteractions(recorder);
    }

    @Test
    void whenStreamErrors_thenRecordFailureCalled() {
        Flux<String> source = Flux.error(new RuntimeException("test error"));

        assertThrows(RuntimeException.class, () ->
                tracker.track(MODEL_ID, source).blockLast());

        verify(recorder).recordFailure(eq(MODEL_ID), eq(599), longThat(ms -> ms >= 0));
        verifyNoMoreInteractions(recorder);
    }

    @Test
    void whenStreamErrorsWithWebClientResponseException_thenStatusAndBodyPassed() {
        String body = "{\"error\":\"rate limit\"}";
        WebClientResponseException error = WebClientResponseException.create(
                429,
                "Too Many Requests",
                org.springframework.http.HttpHeaders.EMPTY,
                body.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                java.nio.charset.StandardCharsets.UTF_8
        );
        Flux<String> source = Flux.<String>error(error);

        assertThrows(WebClientResponseException.class, () ->
                tracker.track(MODEL_ID, source).blockLast());

        verify(recorder).recordFailure(eq(MODEL_ID), eq(429), longThat(ms -> ms >= 0));
        verifyNoMoreInteractions(recorder);
    }

    @Test
    void whenGetIfAvailableReturnsNull_thenSourceFluxReturnedUnchanged() {
        when(recorderProvider.getIfAvailable()).thenReturn(null);
        OpenRouterStreamMetricsTracker t = new OpenRouterStreamMetricsTracker(recorderProvider);
        Flux<String> source = Flux.just("x");

        Flux<String> result = t.track(MODEL_ID, source);

        assertEquals("x", result.blockFirst());
        verify(recorderProvider).getIfAvailable();
        verifyNoInteractions(recorder);
    }
}
