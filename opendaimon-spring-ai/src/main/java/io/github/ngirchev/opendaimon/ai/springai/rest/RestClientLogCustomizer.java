package io.github.ngirchev.opendaimon.ai.springai.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;

@Slf4j
@RequiredArgsConstructor
public class RestClientLogCustomizer implements RestClientCustomizer {

    private final ObjectMapper objectMapper;

    @Override
    public void customize(RestClient.Builder builder) {
        builder.requestInterceptor((req, body, exec) -> {

                    // ---------- REQUEST ----------
                    String requestBody = new String(body, StandardCharsets.UTF_8);

                    log.debug("=== OPENROUTER REQUEST ===");
                    log.debug("HTTP {} {}", req.getMethod(), req.getURI());

                    try {
                        // pretty-print JSON
                        Object json = objectMapper.readValue(requestBody, Object.class);
                        log.debug("REQUEST BODY:\n{}",
                                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(json)
                        );
                    } catch (Exception e) {
                        // in case it's not JSON
                        log.debug("REQUEST BODY (raw): {}", requestBody);
                    }

                    // execute request
                    ClientHttpResponse response = exec.execute(req, body);

                    // ---------- RESPONSE ----------
                    byte[] responseBytes = response.getBody().readAllBytes();
                    String responseBody = new String(responseBytes, StandardCharsets.UTF_8);

                    log.debug("=== OPENROUTER RESPONSE ===");
                    log.debug("STATUS: {} {}", response.getStatusCode(), response.getStatusText());

                    try {
                        Object json = objectMapper.readValue(responseBody, Object.class);
                        log.debug("RESPONSE BODY:\n{}",
                                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(json)
                        );
                    } catch (Exception e) {
                        log.debug("RESPONSE BODY (raw): {}", responseBody);
                    }

                    log.debug("==========================");

                    // IMPORTANT: return response with restored body
                    return new ClientHttpResponseWrapper(response, responseBytes);
                }
        );
    }
}
