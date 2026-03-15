package io.github.ngirchev.opendaimon.ai.springai.tool;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
public class WebTools {

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
                .filter(hit -> hit != null)
                .collect(Collectors.toMap(
                    hit -> hit.url(),
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
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(6))
                .block();

            if (html == null || html.isBlank()) {
                log.warn("WebTools.fetchUrl: empty response for url=[{}]. Returning empty string.", url);
                return "";
            }

            // HTML to plain text (minimal)
            Document doc = Jsoup.parse(html);
            doc.select("script, style, nav, footer, header").remove();

            String text = doc.body() != null ? doc.body().text() : "";
            // avoid token overflow
            return text.length() > 6000 ? text.substring(0, 6000) : text;
        } catch (WebClientResponseException e) {
            log.error("WebTools.fetchUrl failed for url=[{}]: {}. Returning empty string.", url, e.getMessage());
            return "";
        } catch (Exception e) {
            log.error("WebTools.fetchUrl failed for url=[{}]: {}. Returning empty string.", url, e.getMessage(), e);
            return "";
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

