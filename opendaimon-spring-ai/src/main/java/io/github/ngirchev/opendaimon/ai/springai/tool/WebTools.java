package io.github.ngirchev.opendaimon.ai.springai.tool;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
public class WebTools {

    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(6);
    static final String BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36";
    static final String SERVICE_USER_AGENT = "OpenDaimonWebFetch/1.0";
    private static final String ACCEPT_HEADER =
            "text/html,application/xhtml+xml,application/xml;q=0.9,"
                    + "text/plain;q=0.8,*/*;q=0.7";
    private static final String ACCEPT_LANGUAGE = "en-US,en;q=0.9";

    /**
     * Structured error reason codes returned to the agent in tool observations.
     * Agents key off these codes to decide whether to retry, switch to a different
     * hit, or surface the failure to the user — raw exception messages are unstable
     * and confuse the downstream LLM.
     */
    public static final String REASON_TOO_LARGE = "page_too_large";
    public static final String REASON_UNREADABLE_2XX = "unreadable_2xx";
    public static final String REASON_INVALID_URL = "invalid_url";
    public static final String REASON_TIMEOUT = "timeout";

    private final WebClient webClient;
    private final String apiKey;
    private final String apiUrl;

    @Tool(
        name = "web_search",
        description = "Search the web for recent, factual information and return top results with URLs."
    )
    public SearchResult webSearch(String query) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("WebTools.webSearch: Serper API key is not configured. Web search disabled. Returning empty result for query=[{}].", query);
            return new SearchResult(query, List.of());
        }

        if (query == null || query.isBlank()) {
            log.warn("WebTools.webSearch: query is null/blank — skipping. "
                    + "Likely the model emitted an empty tool_call arguments object.");
            return new SearchResult(query == null ? "" : query, List.of());
        }

        Map<String, Object> body = Map.of(
            "q", query,
            "num", 8
        );

        try {
            log.info("WebTools webSearch: {}", query);
            SerperResponse response = webClient.post()
                .uri(apiUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-API-KEY", apiKey)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(SerperResponse.class)
                .timeout(Duration.ofSeconds(6))
                .block();

            if (response == null) {
                response = new SerperResponse();
                log.info("WebTools webSearch response: {}", response);
            }

            List<SearchHit> hits = response.getOrganic().stream()
                .map(organic -> {
                    String url = organic.getLink() != null ? organic.getLink().trim() : "";
                    String title = organic.getTitle() != null ? organic.getTitle().trim() : "";
                    if (url.isBlank() || title.isBlank()) {
                        return null;
                    }
                    String snippet = organic.getSnippet() != null 
                        ? organic.getSnippet().substring(0, Math.min(300, organic.getSnippet().length()))
                        : null;
                    return new SearchHit(title, url, snippet);
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(
                        SearchHit::url,
                    hit -> hit,
                    (existing, replacement) -> existing
                ))
                .values()
                .stream()
                .limit(6)
                .collect(Collectors.toList());

            if (hits.isEmpty()) {
                log.warn("WebTools.webSearch: no results for query=[{}]. Returning empty SearchResult.", query);
            }
            return new SearchResult(query, hits);
        } catch (WebClientResponseException e) {
            String errorBody = e.getResponseBodyAsString();
            log.error("WebTools.webSearch failed (status: {}): {}. Response body: {}. Returning empty result for query=[{}].",
                e.getStatusCode(), e.getMessage(), errorBody, query);
            return new SearchResult(query, List.of());
        } catch (Exception e) {
            log.error("WebTools.webSearch failed: {}. Returning empty result for query=[{}].", e.getMessage(), query, e);
            return new SearchResult(query, List.of());
        }
    }

    @Tool(
        name = "fetch_url",
        description = "Fetch a selected HTTP(S) URL and return cleaned main text. Use web_search for discovery; do not retry a failed URL."
    )
    public String fetchUrl(String url) {
        if (url == null || (!url.startsWith("http://") && !url.startsWith("https://"))) {
            log.warn("WebTools.fetchUrl: url=[{}] is not a valid HTTP(S) URL. Skipping.", url);
            return "Error: " + REASON_INVALID_URL + " — not an http(s) URL";
        }
        try {
            log.info("WebTools fetchUrl: {}", url);
            return fetchAndExtract(url, BROWSER_USER_AGENT);
        } catch (WebClientResponseException e) {
            if (isCloudflareChallenge403(e)) {
                log.warn("WebTools.fetchUrl: Cloudflare challenge for url=[{}], retrying once with service User-Agent", url);
                try {
                    return fetchAndExtract(url, SERVICE_USER_AGENT);
                } catch (WebClientResponseException retryException) {
                    return handleWebClientResponseException(url, retryException);
                } catch (Exception retryException) {
                    return handleFetchException(url, retryException);
                }
            }
            return handleWebClientResponseException(url, e);
        } catch (Exception e) {
            return handleFetchException(url, e);
        }
    }

    private String fetchAndExtract(String url, String userAgent) {
        String html = webClient.get()
            .uri(url)
            .headers(headers -> applyFetchHeaders(headers, userAgent))
            .retrieve()
            .bodyToMono(String.class)
            .timeout(FETCH_TIMEOUT)
            .block();

        if (html == null || html.isBlank()) {
            log.warn("WebTools.fetchUrl: empty response for url=[{}]. Returning empty string.", url);
            return "";
        }

        Document doc = Jsoup.parse(html);
        doc.select("script, style, nav, footer, header").remove();

        doc.body();
        String text = doc.body().text();
        // avoid token overflow - todo add additional model call to grep and parse the result
        return text.length() > 6000 ? text.substring(0, 6000) : text;
    }

    private static void applyFetchHeaders(HttpHeaders headers, String userAgent) {
        headers.set(HttpHeaders.USER_AGENT, userAgent);
        headers.set(HttpHeaders.ACCEPT, ACCEPT_HEADER);
        headers.set(HttpHeaders.ACCEPT_LANGUAGE, ACCEPT_LANGUAGE);
    }

    private static boolean isCloudflareChallenge403(WebClientResponseException e) {
        return e.getStatusCode().value() == 403
                && "challenge".equalsIgnoreCase(e.getHeaders().getFirst("cf-mitigated"));
    }

    private String handleWebClientResponseException(String url, WebClientResponseException e) {
        if (e.getStatusCode().is2xxSuccessful()) {
            // Body decode failure on a successful status (maxInMemorySize exceeded,
            // charset mismatch, malformed gzip). Surface a distinct reason so the agent
            // stops retrying the same URL and can fall back to another search hit
            // instead of looping on an absurd "HTTP error 200 OK" marker.
            log.warn("WebTools.fetchUrl: body decode failed on 2xx for url=[{}]: {}",
                    url, e.getMessage());
            return "Error: " + REASON_UNREADABLE_2XX
                    + " — could not decode response body for " + url;
        }
        String reason = e.getStatusCode().value() + " " + e.getStatusText();
        log.error("WebTools.fetchUrl failed for url=[{}]: {}. Returning structured error.", url, e.getMessage());
        return "HTTP error " + reason;
    }

    private String handleFetchException(String url, Exception e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        if (root instanceof DataBufferLimitException || e instanceof DataBufferLimitException) {
            log.warn("WebTools.fetchUrl: response exceeded in-memory buffer for url=[{}]: {}",
                    url, e.getMessage());
            return "Error: " + REASON_TOO_LARGE + " — response exceeded buffer limit";
        }
        if (e instanceof TimeoutException || root instanceof TimeoutException) {
            log.warn("WebTools.fetchUrl: request timed out for url=[{}]", url);
            return "Error: " + REASON_TIMEOUT + " — request exceeded 6s timeout";
        }
        String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        log.error("WebTools.fetchUrl failed for url=[{}]: {}. Returning structured error.", url, msg, e);
        return "Error: " + msg;
    }

    // Data classes
    public record SearchHit(
        String title,
        String url,
        String snippet
    ) {}

    public record SearchResult(
        String query,
        List<SearchHit> hits
    ) {}

    // Private data classes for Serper API response
    private static class SerperOrganic {
        private String title;
        private String link;
        private String snippet;

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getLink() {
            return link;
        }

        public void setLink(String link) {
            this.link = link;
        }

        public String getSnippet() {
            return snippet;
        }

        public void setSnippet(String snippet) {
            this.snippet = snippet;
        }
    }

    private static class SerperResponse {
        private List<SerperOrganic> organic = new ArrayList<>();

        public List<SerperOrganic> getOrganic() {
            return organic;
        }

        public void setOrganic(List<SerperOrganic> organic) {
            this.organic = organic != null ? organic : new ArrayList<>();
        }
    }
}
