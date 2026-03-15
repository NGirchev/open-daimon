package io.github.ngirchev.opendaimon.common.event;

/**
 * Published before conversation summarization starts.
 * Listeners can use this to notify users that context compaction is in progress.
 *
 * @param conversationId thread key of the conversation being summarized
 */
public record SummarizationStartedEvent(String conversationId) {}
