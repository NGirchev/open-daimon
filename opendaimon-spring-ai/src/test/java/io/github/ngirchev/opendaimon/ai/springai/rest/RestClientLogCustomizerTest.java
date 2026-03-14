package io.github.ngirchev.opendaimon.ai.springai.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Unit tests for RestClientLogCustomizer.
 * Verifies that the customizer registers an interceptor that wraps the response
 * in ClientHttpResponseWrapper so the body can be read after logging.
 */
class RestClientLogCustomizerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void whenRequestMade_thenResponseBodyReadableAndCorrect() {
        RestClient.Builder builder = RestClient.builder();
        new RestClientLogCustomizer(objectMapper).customize(builder);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        String jsonBody = "{\"result\":\"success\"}";
        String requestUri = "http://example.com/test";
        server.expect(requestTo(URI.create(requestUri)))
                .andRespond(withSuccess(jsonBody, MediaType.APPLICATION_JSON));

        RestClient client = builder.build();
        String result = client.get()
                .uri(requestUri)
                .retrieve()
                .body(String.class);

        assertEquals(jsonBody, result);
        server.verify();
    }

    @Test
    void whenRequestWithBody_thenInterceptorRunsAndResponseReturned() {
        RestClient.Builder builder = RestClient.builder();
        new RestClientLogCustomizer(objectMapper).customize(builder);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        String postUri = "http://example.com/post";
        server.expect(requestTo(URI.create(postUri)))
                .andRespond(withSuccess("{\"id\":1}", MediaType.APPLICATION_JSON));

        RestClient client = builder.build();
        String response = client.post()
                .uri(postUri)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"name\":\"test\"}")
                .retrieve()
                .body(String.class);

        assertEquals("{\"id\":1}", response);
        server.verify();
    }
}
