package io.github.ngirchev.opendaimon.common.model;

/**
 * Defines the owner scope of a conversation thread.
 * USER is used for user-owned channels (e.g. REST),
 * TELEGRAM_CHAT is used for Telegram chat/group shared history.
 */
public enum ThreadScopeKind {
    USER,
    TELEGRAM_CHAT
}

