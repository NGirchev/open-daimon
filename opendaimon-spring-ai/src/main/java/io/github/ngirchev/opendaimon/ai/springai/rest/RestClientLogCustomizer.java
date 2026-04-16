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

                    log.debug("HTTP -> {} {}", req.getMethod(), req.getURI());
                    log.debug("HTTP -> {} {} REQUEST BODY RAW:\n{}", req.getMethod(), req.getURI(), requestBody);

                    if (log.isDebugEnabled()) {
                        try {
                            // pretty-print JSON
                            Object json = objectMapper.readValue(requestBody, Object.class);
                            log.debug("HTTP -> {} {} REQUEST BODY (formatted):\n{}",
                                    req.getMethod(),
                                    req.getURI(),
                                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(json)
                            );
                        } catch (Exception e) {
                            log.debug("HTTP -> {} {} REQUEST BODY (formatted unavailable)", req.getMethod(), req.getURI());
                        }
                    }

                    // execute request
                    ClientHttpResponse response = exec.execute(req, body);

                    // ---------- RESPONSE ----------
                    byte[] responseBytes = response.getBody().readAllBytes();
                    String responseBody = new String(responseBytes, StandardCharsets.UTF_8);

                    log.debug("HTTP <- {} {} status={} {}", req.getMethod(), req.getURI(),
                            response.getStatusCode(), response.getStatusText());
                    log.debug("HTTP <- {} {} RESPONSE BODY RAW:\n{}", req.getMethod(), req.getURI(), responseBody);

                    if (log.isDebugEnabled()) {
                        try {
                            Object json = objectMapper.readValue(responseBody, Object.class);
                            log.debug("HTTP <- {} {} RESPONSE BODY (formatted):\n{}",
                                    req.getMethod(),
                                    req.getURI(),
                                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(json)
                            );
                        } catch (Exception e) {
                            log.debug("HTTP <- {} {} RESPONSE BODY (formatted unavailable)", req.getMethod(), req.getURI());
                        }
                    }

                    // IMPORTANT: return response with restored body
                    return new ClientHttpResponseWrapper(response, responseBytes);
                }
        );
    }
}
