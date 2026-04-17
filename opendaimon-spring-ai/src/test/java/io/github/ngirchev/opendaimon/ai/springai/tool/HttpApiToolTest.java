package io.github.ngirchev.opendaimon.ai.springai.tool;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class HttpApiToolTest {

    @Test
    void httpGet_when2xxWebClientResponseException_returnsUnreadable2xxReason() {
        WebClient failingClient = WebClient.builder()
                .filter((request, next) -> Mono.error(WebClientResponseException.create(
                        200,
                        "OK",
                        HttpHeaders.EMPTY,
                        new byte[0],
                        StandardCharsets.UTF_8
                )))
                .build();
        HttpApiTool tool = new HttpApiTool(failingClient);

        String result = tool.httpGet("https://8.8.8.8/api");

        assertThat(result).isEqualTo("Error: UNREADABLE_2XX_RESPONSE");
    }

    @Test
    void httpGet_when4xxWebClientResponseException_keepsHttpErrorFormat() {
        WebClient failingClient = WebClient.builder()
                .filter((request, next) -> Mono.error(WebClientResponseException.create(
                        403,
                        "Forbidden",
                        HttpHeaders.EMPTY,
                        "blocked".getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8
                )))
                .build();
        HttpApiTool tool = new HttpApiTool(failingClient);

        String result = tool.httpGet("https://8.8.8.8/api");

        assertThat(result).contains("HTTP error 403 FORBIDDEN");
    }
}
