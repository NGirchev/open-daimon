package io.github.ngirchev.opendaimon.ai.springai.tool;

/**
 * Verifies that URLs referenced by the LLM in its final answer actually resolve
 * to a live page. Used as the last-mile sanitizer against model-hallucinated
 * citations — the LLM often fabricates plausible-looking URLs that return 404.
 *
 * <p>Implementations are expected to be idempotent and safe to call repeatedly
 * for the same URL within a short window (typically backed by a short-TTL cache
 * so a single answer containing the same URL twice does not issue two HTTP
 * round-trips).
 */
public interface UrlLivenessChecker {

    /**
     * Checks whether the given URL resolves to a live page.
     *
     * @param url absolute {@code http(s)} URL; {@code null} / blank values return {@code false}
     * @return {@code true} if the URL responds with a success / redirect status,
     *         {@code false} on 4xx / 5xx / timeout / network failure
     */
    boolean isLive(String url);

    /**
     * Rewrites the given final answer text by removing or replacing dead URLs:
     * markdown links {@code [anchor](url)} whose URL is dead are collapsed to the
     * anchor text; bare URLs that are dead are replaced with a short unavailable
     * marker so the reader is not sent to a broken page.
     *
     * @param text final answer text as produced by the LLM; {@code null} / blank returns the input unchanged
     * @return sanitized text with the same surrounding content
     */
    String stripDeadLinks(String text);
}
