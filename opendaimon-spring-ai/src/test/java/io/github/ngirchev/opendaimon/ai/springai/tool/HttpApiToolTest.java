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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Verifies the textual error contract of {@link HttpApiTool}.
 *
 * <p>{@code WebClient.bodyToMono} can raise a {@link WebClientResponseException} with
 * a 2xx status when the body exceeds the codec memory limit or fails to decode — in
 * that case the tool must return {@code "Error: <op> could not decode …"} so the agent
 * layer classifies the result as FAILED and does not retry the same URL. Non-2xx
 * failures keep the existing {@code "HTTP error <code> <status>: <body>"} contract.
 */
@ExtendWith(MockitoExtension.class)
class HttpApiToolTest {

    @Mock
    private WebClient webClient;

    @Mock
    private WebClient.RequestHeadersUriSpec<?> getUriSpec;

    @Mock
    private WebClient.RequestHeadersSpec<?> getHeadersSpec;

    @Mock
    private WebClient.RequestBodyUriSpec postUriSpec;

    @Mock
    private WebClient.RequestBodySpec postBodySpec;

    @Mock
    private WebClient.RequestHeadersSpec<?> postHeadersSpec;

    @Mock
    private WebClient.ResponseSpec responseSpec;

    private HttpApiTool httpApiTool;

    @BeforeEach
    void setUp() {
        httpApiTool = new HttpApiTool(webClient);
    }

    @Test
    void shouldReturnStructuredErrorWhenGetBodyDecodingFailsOn2xx() {
        WebClientResponseException okButUndecodable = WebClientResponseException.create(
                HttpStatus.OK.value(), "OK", null, null, null);
        stubGet(Mono.error(okButUndecodable));

        String result = httpApiTool.httpGet("https://example.com/huge-json");

        assertThat(result).isEqualTo(
                "Error: http_get could not decode response body for https://example.com/huge-json");
    }

    @Test
    void shouldReturnStructuredErrorWhenPostBodyDecodingFailsOn2xx() {
        WebClientResponseException okButUndecodable = WebClientResponseException.create(
                HttpStatus.OK.value(), "OK", null, null, null);
        stubPost(Mono.error(okButUndecodable));

        String result = httpApiTool.httpPost("https://example.com/big-reply", "{}");

        assertThat(result).isEqualTo(
                "Error: http_post could not decode response body for https://example.com/big-reply");
    }

    @Test
    void shouldReturnHttpErrorForNon2xxGet() {
        WebClientResponseException forbidden = WebClientResponseException.create(
                HttpStatus.FORBIDDEN.value(), "Forbidden", null, "access denied".getBytes(), null);
        stubGet(Mono.error(forbidden));

        String result = httpApiTool.httpGet("https://example.com/secret");

        assertThat(result).startsWith("HTTP error 403 FORBIDDEN: ");
        assertThat(result).contains("access denied");
    }

    @Test
    void shouldReturnHttpErrorForNon2xxPost() {
        WebClientResponseException forbidden = WebClientResponseException.create(
                HttpStatus.FORBIDDEN.value(), "Forbidden", null, "access denied".getBytes(), null);
        stubPost(Mono.error(forbidden));

        String result = httpApiTool.httpPost("https://example.com/secret", "{}");

        assertThat(result).startsWith("HTTP error 403 FORBIDDEN: ");
        assertThat(result).contains("access denied");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubGet(Mono<String> bodyMono) {
        when(webClient.get()).thenReturn((WebClient.RequestHeadersUriSpec) getUriSpec);
        when(getUriSpec.uri(anyString())).thenReturn((WebClient.RequestHeadersSpec) getHeadersSpec);
        when(getHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(eq(String.class))).thenReturn(bodyMono);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubPost(Mono<String> bodyMono) {
        when(webClient.post()).thenReturn(postUriSpec);
        when(postUriSpec.uri(anyString())).thenReturn(postBodySpec);
        when(postBodySpec.header(anyString(), anyString())).thenReturn(postBodySpec);
        when(postBodySpec.bodyValue(anyString())).thenReturn((WebClient.RequestHeadersSpec) postHeadersSpec);
        when(postHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(eq(String.class))).thenReturn(bodyMono);
    }
}
