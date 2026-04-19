package io.github.ngirchev.opendaimon.ai.springai.tool;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import io.github.ngirchev.opendaimon.common.service.UrlLivenessChecker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WebClient-based implementation of {@link UrlLivenessChecker}.
 *
 * <p>Uses HEAD requests with a strict timeout to classify URLs. If HEAD is rejected
 * with {@code 405 Method Not Allowed}, a range-limited GET ({@code Range: bytes=0-0})
 * is attempted as a fallback — many CDNs disallow HEAD but accept a tiny ranged GET.
 *
 * <p>{@link #stripDeadLinks(String)} bounds the number of unique URLs checked per
 * answer to avoid pathological delays on long answers with many citations.
 */
@Slf4j
public class UrlLivenessCheckerImpl implements UrlLivenessChecker {

    private static final Pattern MARKDOWN_LINK_PATTERN =
            Pattern.compile("\\[([^\\]]+)]\\((https?://[^\\s)]+)\\)");
    private static final Pattern BARE_URL_PATTERN =
            Pattern.compile("(?<![(\\[])https?://\\S+");
    private static final String DEAD_BARE_URL_REPLACEMENT = "(ссылка недоступна)";
    private static final Duration DEFAULT_CACHE_TTL = Duration.ofMinutes(10);
    private static final int MAX_CACHE_ENTRIES = 10_000;

    private final WebClient webClient;
    private final Duration timeout;
    private final int maxUrlsPerAnswer;
    private final Cache<String, Boolean> livenessCache;

    public UrlLivenessCheckerImpl(WebClient webClient, Duration timeout, int maxUrlsPerAnswer) {
        this(webClient, timeout, maxUrlsPerAnswer, DEFAULT_CACHE_TTL);
    }

    public UrlLivenessCheckerImpl(WebClient webClient,
                                  Duration timeout,
                                  int maxUrlsPerAnswer,
                                  Duration cacheTtl) {
        this(webClient, timeout, maxUrlsPerAnswer, cacheTtl, Ticker.systemTicker());
    }

    UrlLivenessCheckerImpl(WebClient webClient,
                           Duration timeout,
                           int maxUrlsPerAnswer,
                           Duration cacheTtl,
                           Ticker ticker) {
        this.webClient = webClient;
        this.timeout = timeout;
        this.maxUrlsPerAnswer = maxUrlsPerAnswer;
        Duration effectiveCacheTtl = cacheTtl != null && !cacheTtl.isZero() && !cacheTtl.isNegative()
                ? cacheTtl
                : DEFAULT_CACHE_TTL;
        this.livenessCache = Caffeine.newBuilder()
                .maximumSize(MAX_CACHE_ENTRIES)
                .expireAfterWrite(effectiveCacheTtl)
                .ticker(ticker)
                .build();
    }

    @Override
    public boolean isLive(String url) {
        String normalizedUrl = normalizeUrl(url);
        if (normalizedUrl == null || normalizedUrl.isBlank()) {
            return false;
        }
        return livenessCache.get(normalizedUrl, this::isLiveUncached);
    }

    private boolean isLiveUncached(String url) {
        HttpStatusCode headStatus = headStatus(url);
        if (headStatus == null) {
            return false;
        }
        if (headStatus.is2xxSuccessful() || headStatus.is3xxRedirection()) {
            return true;
        }
        if (headStatus.value() == 405) {
            return rangedGetIsLive(url);
        }
        return false;
    }

    @Override
    public String stripDeadLinks(String finalAnswer) {
        if (finalAnswer == null || finalAnswer.isBlank()) {
            return finalAnswer;
        }

        LinkedHashSet<String> uniqueUrls = collectUrls(finalAnswer);
        if (uniqueUrls.isEmpty()) {
            return finalAnswer;
        }

        Map<String, Boolean> livenessByUrl = new LinkedHashMap<>();
        for (String url : uniqueUrls) {
            livenessByUrl.put(url, isLive(url));
        }

        String afterMarkdown = replaceDeadMarkdownLinks(finalAnswer, livenessByUrl);
        return replaceDeadBareUrls(afterMarkdown, livenessByUrl);
    }

    private LinkedHashSet<String> collectUrls(String text) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        Matcher markdownMatcher = MARKDOWN_LINK_PATTERN.matcher(text);
        while (markdownMatcher.find() && urls.size() < maxUrlsPerAnswer) {
            urls.add(markdownMatcher.group(2));
        }
        if (urls.size() >= maxUrlsPerAnswer) {
            return urls;
        }
        Matcher bareMatcher = BARE_URL_PATTERN.matcher(text);
        while (bareMatcher.find() && urls.size() < maxUrlsPerAnswer) {
            urls.add(stripTrailingPunctuation(bareMatcher.group()));
        }
        return urls;
    }

    private String replaceDeadMarkdownLinks(String text, Map<String, Boolean> livenessByUrl) {
        Matcher matcher = MARKDOWN_LINK_PATTERN.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String anchor = matcher.group(1);
            String url = matcher.group(2);
            Boolean live = livenessByUrl.get(url);
            if (Boolean.FALSE.equals(live)) {
                log.info("UrlLivenessChecker: stripping dead markdown link anchor='{}' url='{}'", anchor, url);
                matcher.appendReplacement(out, Matcher.quoteReplacement(anchor));
            } else {
                matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group()));
            }
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private String replaceDeadBareUrls(String text, Map<String, Boolean> livenessByUrl) {
        Matcher matcher = BARE_URL_PATTERN.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String raw = matcher.group();
            String url = stripTrailingPunctuation(raw);
            Boolean live = livenessByUrl.get(url);
            if (Boolean.FALSE.equals(live)) {
                log.info("UrlLivenessChecker: replacing dead bare url='{}'", url);
                String trailing = raw.substring(url.length());
                matcher.appendReplacement(out, Matcher.quoteReplacement(DEAD_BARE_URL_REPLACEMENT + trailing));
            } else {
                matcher.appendReplacement(out, Matcher.quoteReplacement(raw));
            }
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private HttpStatusCode headStatus(String url) {
        try {
            return webClient.method(HttpMethod.HEAD)
                    .uri(url)
                    .retrieve()
                    .toBodilessEntity()
                    .map(entity -> entity.getStatusCode())
                    .block(timeout)
                    ;
        } catch (WebClientResponseException e) {
            return e.getStatusCode();
        } catch (Exception e) {
            log.debug("UrlLivenessChecker: HEAD failed for url='{}': {}", url, e.getMessage());
            return null;
        }
    }

    private boolean rangedGetIsLive(String url) {
        try {
            HttpStatusCode status = webClient.get()
                    .uri(url)
                    .header(HttpHeaders.RANGE, "bytes=0-0")
                    .retrieve()
                    .toBodilessEntity()
                    .map(entity -> entity.getStatusCode())
                    .block(timeout);
            return status != null && (status.is2xxSuccessful() || status.is3xxRedirection());
        } catch (WebClientResponseException e) {
            HttpStatusCode status = e.getStatusCode();
            return status.is2xxSuccessful() || status.is3xxRedirection();
        } catch (Exception e) {
            log.debug("UrlLivenessChecker: ranged GET failed for url='{}': {}", url, e.getMessage());
            return false;
        }
    }

    private static String normalizeUrl(String url) {
        return url == null ? null : url.trim();
    }

    private static String stripTrailingPunctuation(String url) {
        int end = url.length();
        while (end > 0) {
            char c = url.charAt(end - 1);
            if (c == '.' || c == ',' || c == ';' || c == ':' || c == '!' || c == '?'
                    || c == ')' || c == ']' || c == '}' || c == '"' || c == '\'' || c == '>') {
                end--;
            } else {
                break;
            }
        }
        return end == url.length() ? url : url.substring(0, end);
    }
}
