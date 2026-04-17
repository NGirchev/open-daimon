package io.github.ngirchev.opendaimon.ai.springai.tool;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
public class WebTools {

    private static final int FETCH_TIMEOUT_SECONDS = 6;
    private static final int MAX_TOOL_TEXT_LENGTH = 6000;
    private static final int DEFAULT_MAX_FETCH_BYTES = 1_048_576;
    private static final String REASON_TOO_LARGE = "TOO_LARGE";
    private static final String REASON_UNREADABLE_2XX = "UNREADABLE_2XX";

    private final WebClient webClient;
    private final String apiKey;
    private final String apiUrl;
    private final int maxFetchBytes;

    public WebTools(WebClient webClient, String apiKey, String apiUrl) {
        this(webClient, apiKey, apiUrl, DEFAULT_MAX_FETCH_BYTES);
    }

    public WebTools(WebClient webClient, String apiKey, String apiUrl, Integer maxFetchBytes) {
        this.webClient = webClient;
        this.apiKey = apiKey;
        this.apiUrl = apiUrl;
        this.maxFetchBytes = maxFetchBytes != null && maxFetchBytes > 0
                ? maxFetchBytes
                : DEFAULT_MAX_FETCH_BYTES;
    }

    @Tool(
        name = "web_search",
        description = "Search the web for recent, factual information and return top results with URLs."
    )
    public SearchResult webSearch(String query) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("WebTools.webSearch: Serper API key is not configured. Web search disabled. Returning empty result for query=[{}].", query);
            return new SearchResult(query, List.of());
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
        description = "Fetch a URL and return cleaned main text for citation."
    )
    public String fetchUrl(String url) {
        if (url == null || (!url.startsWith("http://") && !url.startsWith("https://"))) {
            log.warn("WebTools.fetchUrl: url=[{}] is not a valid HTTP(S) URL. Skipping.", url);
            return "";
        }
        try {
            log.info("WebTools fetchUrl: {}", url);
            String html = webClient.get()
                    .uri(url)
                    .exchangeToMono(this::readBodyWithStatusHandling)
                    .timeout(Duration.ofSeconds(FETCH_TIMEOUT_SECONDS))
                    .block();

            if (html == null || html.isBlank()) {
                log.warn("WebTools.fetchUrl: empty response for url=[{}]. Returning empty string.", url);
                return "";
            }

            String text = extractPlainText(html);
            // avoid token overflow - todo add additional model call to grep and parse the result
            return text.length() > MAX_TOOL_TEXT_LENGTH ? text.substring(0, MAX_TOOL_TEXT_LENGTH) : text;
        } catch (ResponseBodyTooLargeException | DataBufferLimitException e) {
            log.warn("WebTools.fetchUrl failed for url=[{}]: body too large (maxFetchBytes={}). Returning error text.",
                    url, maxFetchBytes);
            return "fetch_url failed: " + REASON_TOO_LARGE + " for " + url;
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().is2xxSuccessful()) {
                log.error("WebTools.fetchUrl failed for url=[{}]: HTTP {} {} with unreadable body. Returning error text.",
                        url, e.getStatusCode().value(), e.getStatusText(), e);
                return "fetch_url failed: " + REASON_UNREADABLE_2XX + " for " + url;
            }
            int statusCode = e.getStatusCode().value();
            log.error("WebTools.fetchUrl failed for url=[{}]: HTTP {} {}. Returning error text.",
                    url, statusCode, e.getStatusText());
            return "fetch_url failed: HTTP " + statusCode + " for " + url;
        } catch (Exception e) {
            String reason = e.getMessage() != null && !e.getMessage().isBlank()
                    ? e.getMessage()
                    : e.getClass().getSimpleName();
            log.error("WebTools.fetchUrl failed for url=[{}]: {}. Returning error text.", url, reason, e);
            return "fetch_url failed: " + reason + " for " + url;
        }
    }

    private Mono<String> readBodyWithStatusHandling(ClientResponse response) {
        if (!response.statusCode().is2xxSuccessful()) {
            return response.createException().flatMap(Mono::error);
        }

        AtomicInteger totalBytes = new AtomicInteger();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        return response.bodyToFlux(DataBuffer.class)
                .doOnNext(dataBuffer -> appendChunk(output, totalBytes, dataBuffer))
                .then(Mono.fromSupplier(() -> output.toString(StandardCharsets.UTF_8)));
    }

    private void appendChunk(ByteArrayOutputStream output, AtomicInteger totalBytes, DataBuffer dataBuffer) {
        int chunkSize = dataBuffer.readableByteCount();
        int nextTotal = totalBytes.addAndGet(chunkSize);
        if (nextTotal > maxFetchBytes) {
            DataBufferUtils.release(dataBuffer);
            throw new ResponseBodyTooLargeException(maxFetchBytes);
        }

        byte[] bytes = new byte[chunkSize];
        dataBuffer.read(bytes);
        DataBufferUtils.release(dataBuffer);
        output.writeBytes(bytes);
    }

    private String extractPlainText(String html) {
        Document doc = Jsoup.parse(html);
        doc.select("script, style, nav, footer, header").remove();
        doc.body();
        return doc.body().text();
    }

    private static final class ResponseBodyTooLargeException extends RuntimeException {
        private ResponseBodyTooLargeException(int maxFetchBytes) {
            super("Response body too large. maxFetchBytes=" + maxFetchBytes);
        }
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
