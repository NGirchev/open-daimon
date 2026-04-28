package io.github.ngirchev.opendaimon.ai.springai.tool;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WebClient-based implementation of {@link UrlLivenessChecker}.
 *
 * <p>Uses HEAD requests with a strict timeout to classify URLs. If HEAD is rejected
 * with {@code 405 Method Not Allowed} or {@code 403 Forbidden} (commonly done by
 * Cloudflare-fronted sites for HEAD requests without a browser UA), a range-limited
 * GET ({@code Range: bytes=0-0}) is attempted as a fallback.
 *
 * <p><b>SSRF protection.</b> Before issuing any HTTP request the target URL is
 * validated against the same hostname/IP blocklist as {@link HttpApiTool}:
 * loopback, site-local, link-local, any-local addresses and metadata-service
 * hostnames are rejected outright and reported as dead. This prevents a hallucinated
 * URL in the agent's final answer from probing internal infrastructure
 * (AWS/GCP metadata endpoints, localhost management ports, etc.) even when
 * {@link HttpApiTool} is disabled.
 *
 * <p>{@link #stripDeadLinks(String)} bounds the number of unique URLs checked per
 * answer to avoid pathological delays on long answers with many citations and
 * runs the probes in parallel with bounded concurrency.
 *
 * <p>Results of {@link #isLive(String)} are cached in an in-memory Caffeine cache
 * keyed by URL with a configurable TTL (typically minutes).
 */
@Slf4j
public class UrlLivenessCheckerImpl implements UrlLivenessChecker {

    private static final Pattern MARKDOWN_LINK_PATTERN =
            Pattern.compile("\\[([^\\]]+)]\\((https?://[^\\s)]+)\\)");
    private static final Pattern BARE_URL_PATTERN =
            Pattern.compile("(?<![(\\[])https?://\\S+");
    private static final String DEFAULT_DEAD_MARKER = "[link unavailable]";

    /** Per-answer upper bound on concurrent HEAD/GET probes. */
    private static final int PROBE_CONCURRENCY = 5;

    /**
     * Hostnames that resolve (or can resolve) to private/internal/metadata services.
     * Kept in sync with {@code HttpApiTool.BLOCKED_HOST_PATTERNS} — any change to
     * that list should be mirrored here so the final-answer sanitizer and the
     * agent tool enforce the same SSRF boundary.
     */
    private static final List<Pattern> BLOCKED_HOST_PATTERNS = List.of(
            Pattern.compile("^localhost$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^.*\\.local$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^metadata\\.google\\.internal$", Pattern.CASE_INSENSITIVE)
    );

    private final WebClient webClient;
    private final Duration timeout;
    private final int maxUrlsPerAnswer;
    private final Cache<String, Boolean> livenessCache;
    /** When true, loopback / site-local hosts are not auto-rejected. Only used by tests. */
    private final boolean allowLoopbackForTests;

    public UrlLivenessCheckerImpl(WebClient webClient,
                                  Duration timeout,
                                  int maxUrlsPerAnswer,
                                  Duration cacheTtl) {
        this(webClient, timeout, maxUrlsPerAnswer, cacheTtl, false);
    }

    /**
     * Test-only constructor that allows loopback probes so {@code MockWebServer}
     * (which only binds to 127.0.0.1) can exercise the checker end-to-end.
     * Never call this from production code: the SSRF guard is the whole point of
     * this class, and disabling it opens the final-answer sanitizer itself as an
     * SSRF vector.
     */
    UrlLivenessCheckerImpl(WebClient webClient,
                           Duration timeout,
                           int maxUrlsPerAnswer,
                           Duration cacheTtl,
                           boolean allowLoopbackForTests) {
        this.webClient = webClient;
        this.timeout = timeout;
        this.maxUrlsPerAnswer = maxUrlsPerAnswer;
        this.allowLoopbackForTests = allowLoopbackForTests;
        this.livenessCache = Caffeine.newBuilder()
                .expireAfterWrite(cacheTtl)
                .maximumSize(10_000)
                .build();
    }

    @Override
    public boolean isLive(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        Boolean cached = livenessCache.getIfPresent(url);
        if (cached != null) {
            log.debug("UrlLivenessChecker: cache hit url='{}' live={}", url, cached);
            return cached;
        }
        if (!isUrlSafeToProbe(url, allowLoopbackForTests)) {
            livenessCache.put(url, false);
            return false;
        }
        boolean result = checkLive(url);
        livenessCache.put(url, result);
        return result;
    }

    private boolean checkLive(String url) {
        HttpStatusCode headStatus = headStatus(url);
        if (headStatus == null) {
            return false;
        }
        if (headStatus.is2xxSuccessful() || headStatus.is3xxRedirection()) {
            return true;
        }
        // Cloudflare and many CDNs reject HEAD from non-browser UAs with 403/405.
        // 401 sometimes indicates "HEAD not supported but GET is". Try a tiny
        // ranged GET before giving up — this matches what curl/browser would do.
        if (headStatus.value() == 405 || headStatus.value() == 403 || headStatus.value() == 401) {
            return rangedGetIsLive(url);
        }
        return false;
    }

    @Override
    public String stripDeadLinks(String finalAnswer, String languageCode) {
        if (finalAnswer == null || finalAnswer.isBlank()) {
            return finalAnswer;
        }

        LinkedHashSet<String> uniqueUrls = collectUrls(finalAnswer);
        if (uniqueUrls.isEmpty()) {
            return finalAnswer;
        }

        Map<String, Boolean> livenessByUrl = probeAll(uniqueUrls);

        String marker = resolveDeadMarker(languageCode);
        String afterMarkdown = replaceDeadMarkdownLinks(finalAnswer, livenessByUrl);
        return replaceDeadBareUrls(afterMarkdown, livenessByUrl, marker);
    }

    /**
     * Picks the dead-link replacement marker for the given language code
     * ({@code ISO 639-1}, case-insensitive). Unknown or {@code null} codes
     * fall back to the language-neutral default {@link #DEFAULT_DEAD_MARKER}.
     *
     * <p>Kept as a narrow explicit switch rather than a resource bundle: there are
     * only a handful of supported locales, and each entry doubles as documentation
     * of what the user will actually see in each language.
     */
    private static String resolveDeadMarker(String languageCode) {
        if (languageCode == null || languageCode.isBlank()) {
            return DEFAULT_DEAD_MARKER;
        }
        return switch (languageCode.toLowerCase()) {
            case "ru" -> "(ссылка недоступна)";
            case "de" -> "[Link nicht verfügbar]";
            case "fr" -> "[lien indisponible]";
            case "es" -> "[enlace no disponible]";
            case "zh" -> "[链接不可用]";
            default -> DEFAULT_DEAD_MARKER;
        };
    }

    /**
     * Probes every URL in the given set concurrently (up to {@link #PROBE_CONCURRENCY}
     * in-flight requests) and returns a map of url → live-flag. The overall wall time
     * is bounded by {@code timeout * ceil(urls.size() / PROBE_CONCURRENCY)} rather
     * than {@code timeout * urls.size()} for the sequential version.
     */
    private Map<String, Boolean> probeAll(LinkedHashSet<String> urls) {
        Map<String, Boolean> result = new LinkedHashMap<>();
        Flux.fromIterable(urls)
                .flatMap(url -> Mono.fromCallable(() -> isLive(url))
                                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                                .map(live -> Map.entry(url, live)),
                        PROBE_CONCURRENCY)
                .toIterable()
                .forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
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

    private String replaceDeadBareUrls(String text, Map<String, Boolean> livenessByUrl, String marker) {
        Matcher matcher = BARE_URL_PATTERN.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String raw = matcher.group();
            String url = stripTrailingPunctuation(raw);
            Boolean live = livenessByUrl.get(url);
            if (Boolean.FALSE.equals(live)) {
                log.info("UrlLivenessChecker: replacing dead bare url='{}'", url);
                String trailing = raw.substring(url.length());
                matcher.appendReplacement(out, Matcher.quoteReplacement(marker + trailing));
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
                    .block(timeout);
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

    /**
     * Rejects URLs that could make the liveness check itself a privilege vector:
     * non-http(s), missing host, metadata/loopback hostnames, or DNS-resolved to
     * loopback / site-local / link-local / any-local IPs. Packaged package-private
     * for the dedicated SSRF test ({@code UrlLivenessCheckerImplSsrfTest}).
     *
     * @param allowLoopback when true, loopback / site-local IPs are permitted —
     *                      used by tests that point at a local {@code MockWebServer}.
     *                      Production callers must pass {@code false}.
     */
    static boolean isUrlSafeToProbe(String url, boolean allowLoopback) {
        if (url == null || (!url.startsWith("http://") && !url.startsWith("https://"))) {
            log.info("UrlLivenessChecker: skipping non-http(s) url='{}'", url);
            return false;
        }
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return false;
            }
            if (!allowLoopback) {
                for (Pattern pattern : BLOCKED_HOST_PATTERNS) {
                    if (pattern.matcher(host).matches()) {
                        log.info("UrlLivenessChecker: blocked host url='{}' host='{}'", url, host);
                        return false;
                    }
                }
            }
            InetAddress address = InetAddress.getByName(host);
            boolean internal = address.isLoopbackAddress()
                    || address.isSiteLocalAddress()
                    || address.isLinkLocalAddress()
                    || address.isAnyLocalAddress();
            if (internal && !allowLoopback) {
                log.info("UrlLivenessChecker: blocked private/loopback url='{}' ip='{}'",
                        url, address.getHostAddress());
                return false;
            }
            return true;
        } catch (UnknownHostException e) {
            log.debug("UrlLivenessChecker: cannot resolve host for url='{}': {}", url, e.getMessage());
            return false;
        } catch (IllegalArgumentException e) {
            log.debug("UrlLivenessChecker: malformed url='{}': {}", url, e.getMessage());
            return false;
        }
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
