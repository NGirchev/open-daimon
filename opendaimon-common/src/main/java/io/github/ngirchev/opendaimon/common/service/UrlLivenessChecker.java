package io.github.ngirchev.opendaimon.common.service;

/**
 * Post-processing guard that validates outbound URLs before they reach the user.
 *
 * <p>Used by the Telegram renderer to strip hallucinated or dead links from a model's
 * final answer: HEAD-checks every URL and either keeps it, replaces a markdown link
 * with plain anchor text, or replaces a bare URL with an "unavailable" marker.
 *
 * <p>The interface lives in {@code opendaimon-common} so that any module rendering
 * user-visible text can depend on it without pulling in the {@code opendaimon-spring-ai}
 * implementation (which requires Spring WebClient).
 */
public interface UrlLivenessChecker {

    /**
     * Checks whether a URL answers with HTTP 2xx/3xx within the configured timeout.
     *
     * @param url absolute HTTP(S) URL
     * @return {@code true} if the URL responds with 2xx/3xx; {@code false} on 4xx/5xx,
     *         timeout, or any I/O error
     */
    boolean isLive(String url);

    /**
     * Rewrites the given text by removing references to URLs that are not live.
     *
     * <p>Markdown links {@code [anchor](url)} with a dead URL become plain {@code anchor}.
     * Bare dead URLs are replaced with a human-readable unavailable marker.
     *
     * @param finalAnswer the full final answer text (may be markdown)
     * @return sanitized text, or the original reference if {@code finalAnswer} is {@code null}/blank
     */
    String stripDeadLinks(String finalAnswer);
}
