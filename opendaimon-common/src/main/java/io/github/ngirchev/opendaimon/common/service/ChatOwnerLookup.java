package io.github.ngirchev.opendaimon.common.service;

import io.github.ngirchev.opendaimon.common.model.User;

import java.util.Optional;

/**
 * Cross-module SPI: resolves the settings-owner {@link User} for a given
 * Telegram {@code chat_id} (or any other scoped id carried by a
 * {@code ConversationThread}). Lives in {@code opendaimon-common} so that
 * summarization and other common-side paths can seed per-chat settings
 * without importing the Telegram module.
 * <p>
 * Default binding returns {@link Optional#empty()} — enough for non-Telegram
 * deployments. The Telegram module provides an implementation that delegates
 * to its {@code ChatSettingsOwnerResolver}.
 */
public interface ChatOwnerLookup {

    Optional<User> findByChatId(Long chatId);

    /** No-op fallback when no Telegram module is present. */
    ChatOwnerLookup NOOP = chatId -> Optional.empty();
}
