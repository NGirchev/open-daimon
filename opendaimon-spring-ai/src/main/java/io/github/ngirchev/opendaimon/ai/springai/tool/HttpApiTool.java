package io.github.ngirchev.opendaimon.ai.springai.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Agent tool for making HTTP API requests.
 *
 * <p>Supports GET and POST methods with configurable timeout.
 * Useful for agents that need to interact with external REST APIs,
 * fetch JSON data, or trigger webhooks.
 *
 * <p>Security: Only allows HTTP(S) URLs to public hosts. Private/internal
 * IP ranges and loopback addresses are blocked to prevent SSRF attacks.
 * An optional domain allowlist can further restrict which hosts are reachable.
 * Response is truncated to avoid token overflow in the LLM context.
 */
@Slf4j
public class HttpApiTool {

    private static final int MAX_RESPONSE_LENGTH = 8000;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Patterns matching hostnames that resolve to private/internal IP ranges.
     * These are blocked to prevent SSRF attacks against internal infrastructure.
     */
    private static final List<Pattern> BLOCKED_HOST_PATTERNS = List.of(
            Pattern.compile("^localhost$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^.*\\.local$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^metadata\\.google\\.internal$", Pattern.CASE_INSENSITIVE)
    );

    private final WebClient webClient;
    private final Set<String> allowedDomains;

    public HttpApiTool(WebClient webClient) {
        this(webClient, Set.of());
    }

    /**
     * @param allowedDomains if non-empty, only these domains are permitted (exact match, case-insensitive).
     *                       An empty set means all public domains are allowed.
     */
    public HttpApiTool(WebClient webClient, Set<String> allowedDomains) {
        this.webClient = webClient;
        this.allowedDomains = allowedDomains != null ? Set.copyOf(allowedDomains) : Set.of();
    }

    @Tool(
            name = "http_get",
            description = "Make an HTTP GET request to a URL and return the response body. " +
                    "Use for fetching JSON from REST APIs, checking endpoint status, or retrieving data."
    )
    public String httpGet(
            @ToolParam(description = "The full URL to send the GET request to (must start with http:// or https://)") String url) {
        String urlError = validateUrl(url);
        if (urlError != null) {
            return "Error: " + urlError;
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
            return formatWebClientError(e, url, "http_get");
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
        String urlError = validateUrl(url);
        if (urlError != null) {
            return "Error: " + urlError;
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
            return formatWebClientError(e, url, "http_post");
        } catch (Exception e) {
            log.error("HttpApiTool POST failed: url={}, error={}", url, e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Classifies a {@link WebClientResponseException} into a tool-layer error string.
     *
     * <p>{@code WebClient.bodyToMono} can raise this exception with a 2xx status when the
     * response body cannot be decoded (e.g. exceeds {@code maxInMemorySize} codec limit,
     * charset mismatch, malformed gzip). In that case the status is misleading — the
     * upstream server actually succeeded — so we surface {@code "Error: <op> could not
     * decode …"} which the agent layer classifies as FAILED. For genuine non-2xx
     * failures we keep the existing {@code "HTTP error <code> <status>: <body>"} contract.
     *
     * @param e  the exception from WebClient
     * @param url the request URL (for diagnostics in the returned message)
     * @param op  the tool operation ({@code "http_get"} or {@code "http_post"})
     */
    private String formatWebClientError(WebClientResponseException e, String url, String op) {
        if (e.getStatusCode().is2xxSuccessful()) {
            log.warn("HttpApiTool.{}: body decode failed on 2xx for url=[{}]: {}",
                    op, url, e.getMessage());
            return "Error: " + op + " could not decode response body for " + url;
        }
        log.error("HttpApiTool.{} failed: url={}, status={}", op, url, e.getStatusCode());
        return "HTTP error " + e.getStatusCode() + ": " + truncate(e.getResponseBodyAsString());
    }

    /**
     * Validates the URL: must be HTTP(S), must not target private/loopback addresses,
     * and must match the domain allowlist if one is configured.
     */
    private String validateUrl(String url) {
        if (url == null || (!url.startsWith("http://") && !url.startsWith("https://"))) {
            return "Invalid URL. Must start with http:// or https://";
        }
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return "Invalid URL: no host";
            }

            // Check hostname blocklist
            for (Pattern pattern : BLOCKED_HOST_PATTERNS) {
                if (pattern.matcher(host).matches()) {
                    return "Blocked host: " + host;
                }
            }

            // Check domain allowlist (if configured)
            if (!allowedDomains.isEmpty()
                    && allowedDomains.stream().noneMatch(d -> d.equalsIgnoreCase(host))) {
                return "Host not in allowlist: " + host;
            }

            // Resolve IP and block private/loopback ranges
            InetAddress address = InetAddress.getByName(host);
            if (address.isLoopbackAddress()
                    || address.isSiteLocalAddress()
                    || address.isLinkLocalAddress()
                    || address.isAnyLocalAddress()) {
                return "Blocked: private/loopback IP for host " + host;
            }

        } catch (UnknownHostException e) {
            return "Cannot resolve host: " + e.getMessage();
        } catch (IllegalArgumentException e) {
            return "Malformed URL: " + e.getMessage();
        }
        return null; // valid
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
