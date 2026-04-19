package io.github.ngirchev.opendaimon.ai.springai.tool;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
public class WebTools {

    private static final int FETCH_TIMEOUT_SECONDS = 6;
    private static final int MAX_TOOL_TEXT_LENGTH = 6000;
    private static final int DEFAULT_MAX_FETCH_BYTES = 1_048_576;
    private static final int FALLBACK_CANDIDATE_LIMIT = 3;
    private static final String MISSING_URL_TARGET = "(missing url argument)";
    private static final String REASON_TOO_LARGE = "TOO_LARGE";
    private static final String REASON_UNREADABLE_2XX = "UNREADABLE_2XX";
    private static final String REASON_INVALID_URL = "INVALID_URL";
    private static final String REASON_MISSING_URL = "MISSING_URL";
    private static final String REASON_EMPTY_RESPONSE = "EMPTY_RESPONSE";

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
    public SearchResult webSearch(
            @ToolParam(description = "Concrete search query text. Must not be null or blank.") String query) {
        String normalizedQuery = query != null ? query.trim() : "";
        if (normalizedQuery.isBlank()) {
            log.warn("WebTools.webSearch: query is blank. Returning empty result.");
            return new SearchResult(normalizedQuery, List.of());
        }

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("WebTools.webSearch: Serper API key is not configured. Web search disabled. Returning empty result for query=[{}].", query);
            return new SearchResult(normalizedQuery, List.of());
        }

        Map<String, Object> body = Map.of(
            "q", normalizedQuery,
            "num", 8
        );

        try {
            log.info("WebTools webSearch: {}", normalizedQuery);
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
                    (existing, replacement) -> existing,
                    LinkedHashMap::new
                ))
                .values()
                .stream()
                .limit(6)
                .collect(Collectors.toList());

            if (hits.isEmpty()) {
                log.warn("WebTools.webSearch: no results for query=[{}]. Returning empty SearchResult.", normalizedQuery);
            }
            return new SearchResult(normalizedQuery, hits);
        } catch (WebClientResponseException e) {
            String errorBody = e.getResponseBodyAsString();
            log.error("WebTools.webSearch failed (status: {}): {}. Response body: {}. Returning empty result for query=[{}].",
                e.getStatusCode(), e.getMessage(), errorBody, normalizedQuery);
            return new SearchResult(normalizedQuery, List.of());
        } catch (Exception e) {
            log.error("WebTools.webSearch failed: {}. Returning empty result for query=[{}].", e.getMessage(), normalizedQuery, e);
            return new SearchResult(normalizedQuery, List.of());
        }
    }

    @Tool(
        name = "fetch_url",
        description = "Fetch a URL and return cleaned main text for citation."
    )
    public String fetchUrl(
            @ToolParam(description = "Full public HTTP(S) URL to fetch. Must be a concrete URL, not null.") String url) {
        return fetchUrl(url, true);
    }

    private String fetchUrl(String url, boolean allowFallback) {
        String normalizedUrl = normalizeUrl(url);
        if (isMissingUrl(normalizedUrl)) {
            log.warn("WebTools.fetchUrl: missing URL argument. Returning error text.");
            return fetchFailure(REASON_MISSING_URL, MISSING_URL_TARGET);
        }
        if (!isHttpUrl(normalizedUrl)) {
            log.warn("WebTools.fetchUrl: url=[{}] is not a valid HTTP(S) URL. Returning error text.", url);
            return fetchFailure(REASON_INVALID_URL, normalizedUrl);
        }
        try {
            log.info("WebTools fetchUrl: {}", normalizedUrl);
            String html = webClient.get()
                    .uri(normalizedUrl)
                    .exchangeToMono(this::readBodyWithStatusHandling)
                    .timeout(Duration.ofSeconds(FETCH_TIMEOUT_SECONDS))
                    .block();

            if (html == null || html.isBlank()) {
                log.warn("WebTools.fetchUrl: empty response for url=[{}]. Returning error text.", normalizedUrl);
                return fetchFailure(REASON_EMPTY_RESPONSE, normalizedUrl);
            }

            String text = extractPlainText(html);
            return text.length() > MAX_TOOL_TEXT_LENGTH ? text.substring(0, MAX_TOOL_TEXT_LENGTH) : text;
        } catch (ResponseBodyTooLargeException | DataBufferLimitException e) {
            log.warn("WebTools.fetchUrl failed for url=[{}]: body too large (maxFetchBytes={}). Returning error text.",
                    normalizedUrl, maxFetchBytes);
            return fetchFailure(REASON_TOO_LARGE, normalizedUrl);
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().is2xxSuccessful()) {
                log.error("WebTools.fetchUrl failed for url=[{}]: HTTP {} {} with unreadable body. Returning error text.",
                        normalizedUrl, e.getStatusCode().value(), e.getStatusText(), e);
                return fetchFailure(REASON_UNREADABLE_2XX, normalizedUrl);
            }
            int statusCode = e.getStatusCode().value();
            log.error("WebTools.fetchUrl failed for url=[{}]: HTTP {} {}. Returning error text.",
                    normalizedUrl, statusCode, e.getStatusText());
            if (statusCode == 403 && allowFallback) {
                String fallbackContent = fetchFallbackSource(normalizedUrl);
                if (fallbackContent != null) {
                    return fallbackContent;
                }
            }
            return fetchFailure("HTTP " + statusCode, normalizedUrl);
        } catch (Exception e) {
            String reason = e.getMessage() != null && !e.getMessage().isBlank()
                    ? e.getMessage()
                    : e.getClass().getSimpleName();
            log.error("WebTools.fetchUrl failed for url=[{}]: {}. Returning error text.", normalizedUrl, reason, e);
            return fetchFailure(reason, normalizedUrl);
        }
    }

    private String fetchFallbackSource(String originalUrl) {
        if (apiKey == null || apiKey.isBlank()) {
            log.info("WebTools.fetchUrl: HTTP 403 for url=[{}], fallback search skipped because Serper is not configured.",
                    originalUrl);
            return null;
        }

        String query = buildFallbackSearchQuery(originalUrl);
        SearchResult searchResult = webSearch(query);
        List<String> candidates = fallbackCandidateUrls(searchResult, originalUrl);
        if (candidates.isEmpty()) {
            log.info("WebTools.fetchUrl: HTTP 403 for url=[{}], fallback search returned no usable candidates.",
                    originalUrl);
            return null;
        }

        for (String candidateUrl : candidates) {
            String candidateResult = fetchUrl(candidateUrl, false);
            if (!isFetchFailure(candidateResult) && candidateResult != null && !candidateResult.isBlank()) {
                log.info("WebTools.fetchUrl: using fallback source url=[{}] for blocked url=[{}].",
                        candidateUrl, originalUrl);
                return "Original URL blocked: " + originalUrl
                        + "\nFallback source: " + candidateUrl
                        + "\n\n" + candidateResult;
            }
        }

        log.info("WebTools.fetchUrl: HTTP 403 for url=[{}], all fallback candidates failed.", originalUrl);
        return null;
    }

    private List<String> fallbackCandidateUrls(SearchResult searchResult, String originalUrl) {
        if (searchResult == null || searchResult.hits() == null || searchResult.hits().isEmpty()) {
            return List.of();
        }
        String originalAuthority = normalizedAuthority(originalUrl);
        return searchResult.hits().stream()
                .map(SearchHit::url)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(WebTools::isHttpUrl)
                .filter(candidateUrl -> !sameAuthority(candidateUrl, originalAuthority))
                .filter(candidateUrl -> !isLikelyPdf(candidateUrl))
                .distinct()
                .limit(FALLBACK_CANDIDATE_LIMIT)
                .toList();
    }

    private static String buildFallbackSearchQuery(String originalUrl) {
        String title = titleFromUrl(originalUrl);
        String host = normalizedHost(originalUrl);
        if (title.isBlank()) {
            return host == null ? originalUrl : originalUrl + " -site:" + host;
        }
        return host == null ? title : title + " -site:" + host;
    }

    private static String titleFromUrl(String url) {
        try {
            URI uri = URI.create(url);
            String path = uri.getPath();
            if (path == null || path.isBlank()) {
                return "";
            }
            String[] segments = path.split("/");
            String lastSegment = segments.length == 0 ? "" : segments[segments.length - 1];
            String decoded = URLDecoder.decode(lastSegment, StandardCharsets.UTF_8);
            return decoded
                    .replace('_', ' ')
                    .replace('-', ' ')
                    .replaceAll("^\\d+\\s+", "")
                    .replaceAll("\\s+", " ")
                    .trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static String normalizeUrl(String url) {
        return url == null ? null : url.trim();
    }

    private static boolean isMissingUrl(String url) {
        return url == null
                || url.isBlank()
                || "null".equalsIgnoreCase(url.trim())
                || "undefined".equalsIgnoreCase(url.trim());
    }

    private static boolean isHttpUrl(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    private static boolean isFetchFailure(String text) {
        return text != null && text.regionMatches(true, 0, "fetch_url failed:", 0, "fetch_url failed:".length());
    }

    private static boolean isLikelyPdf(String url) {
        String normalized = url.toLowerCase(Locale.ROOT);
        int queryIndex = normalized.indexOf('?');
        String path = queryIndex >= 0 ? normalized.substring(0, queryIndex) : normalized;
        return path.endsWith(".pdf");
    }

    private static String normalizedAuthority(String url) {
        try {
            URI uri = URI.create(url);
            String authority = uri.getAuthority();
            return authority == null || authority.isBlank() ? null : authority.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return null;
        }
    }

    private static String normalizedHost(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            return host == null || host.isBlank() ? null : host.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean sameAuthority(String candidateUrl, String originalAuthority) {
        if (originalAuthority == null) {
            return false;
        }
        String candidateAuthority = normalizedAuthority(candidateUrl);
        return originalAuthority.equals(candidateAuthority);
    }

    private static String fetchFailure(String reason, String target) {
        return "fetch_url failed: " + reason + " for " + target;
    }

    Mono<String> readBodyWithStatusHandling(ClientResponse response) {
        if (!response.statusCode().is2xxSuccessful()) {
            return response.createException().flatMap(Mono::error);
        }

        AtomicInteger totalBytes = new AtomicInteger();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        return response.bodyToFlux(DataBuffer.class)
                .doOnDiscard(DataBuffer.class, DataBufferUtils::release)
                .doOnNext(dataBuffer -> appendChunk(output, totalBytes, dataBuffer))
                .then(Mono.fromSupplier(() -> output.toString(StandardCharsets.UTF_8)));
    }

    private void appendChunk(ByteArrayOutputStream output, AtomicInteger totalBytes, DataBuffer dataBuffer) {
        try {
            int chunkSize = dataBuffer.readableByteCount();
            int nextTotal = totalBytes.addAndGet(chunkSize);
            if (nextTotal > maxFetchBytes) {
                throw new ResponseBodyTooLargeException(maxFetchBytes);
            }

            byte[] bytes = new byte[chunkSize];
            dataBuffer.read(bytes);
            output.writeBytes(bytes);
        } finally {
            DataBufferUtils.release(dataBuffer);
        }
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
    @Getter
    @Setter
    private static class SerperOrganic {
        private String title;
        private String link;
        private String snippet;
    }

    @Getter
    private static class SerperResponse {
        private List<SerperOrganic> organic = new ArrayList<>();

        public void setOrganic(List<SerperOrganic> organic) {
            this.organic = organic != null ? organic : new ArrayList<>();
        }
    }
}
