package io.github.ngirchev.opendaimon.ai.springai.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;

/**
 * Agent tool for making HTTP API requests.
 *
 * <p>Supports GET and POST methods with configurable timeout.
 * Useful for agents that need to interact with external REST APIs,
 * fetch JSON data, or trigger webhooks.
 *
 * <p>Security: Only allows HTTP(S) URLs. Response is truncated to avoid
 * token overflow in the LLM context.
 */
@Slf4j
public class HttpApiTool {

    private static final int MAX_RESPONSE_LENGTH = 8000;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    private final WebClient webClient;

    public HttpApiTool(WebClient webClient) {
        this.webClient = webClient;
    }

    @Tool(
            name = "http_get",
            description = "Make an HTTP GET request to a URL and return the response body. " +
                    "Use for fetching JSON from REST APIs, checking endpoint status, or retrieving data."
    )
    public String httpGet(
            @ToolParam(description = "The full URL to send the GET request to (must start with http:// or https://)") String url) {
        if (!isValidUrl(url)) {
            return "Error: Invalid URL. Must start with http:// or https://";
        }
        try {
            log.info("HttpApiTool GET: {}", url);
            String response = webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(DEFAULT_TIMEOUT)
                    .block();

            return truncate(response);
        } catch (WebClientResponseException e) {
            log.error("HttpApiTool GET failed: url={}, status={}", url, e.getStatusCode());
            return "HTTP error " + e.getStatusCode() + ": " + truncate(e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("HttpApiTool GET failed: url={}, error={}", url, e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    @Tool(
            name = "http_post",
            description = "Make an HTTP POST request with a JSON body and return the response. " +
                    "Use for sending data to REST APIs, triggering actions, or submitting forms."
    )
    public String httpPost(
            @ToolParam(description = "The full URL to send the POST request to") String url,
            @ToolParam(description = "The JSON request body to send") String body) {
        if (!isValidUrl(url)) {
            return "Error: Invalid URL. Must start with http:// or https://";
        }
        try {
            log.info("HttpApiTool POST: url={}, bodyLength={}", url, body != null ? body.length() : 0);
            String response = webClient.post()
                    .uri(url)
                    .header("Content-Type", "application/json")
                    .bodyValue(body != null ? body : "")
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(DEFAULT_TIMEOUT)
                    .block();

            return truncate(response);
        } catch (WebClientResponseException e) {
            log.error("HttpApiTool POST failed: url={}, status={}", url, e.getStatusCode());
            return "HTTP error " + e.getStatusCode() + ": " + truncate(e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("HttpApiTool POST failed: url={}, error={}", url, e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    private boolean isValidUrl(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > MAX_RESPONSE_LENGTH
                ? text.substring(0, MAX_RESPONSE_LENGTH) + "...(truncated)"
                : text;
    }
}
