package io.github.ngirchev.opendaimon.telegram.service;

import lombok.RequiredArgsConstructor;
import io.github.ngirchev.opendaimon.common.model.User;
import io.github.ngirchev.opendaimon.common.service.ChatOwnerLookup;

import java.util.Optional;

/**
 * Telegram-side binding of {@link ChatOwnerLookup} — delegates to
 * {@link ChatSettingsOwnerResolver#findByChatId(Long)}. Registered as the
 * primary {@code ChatOwnerLookup} bean when the Telegram module is active,
 * overriding the common-module {@link ChatOwnerLookup#NOOP} fallback.
 */
@RequiredArgsConstructor
public class TelegramChatOwnerLookup implements ChatOwnerLookup {

    private final ChatSettingsOwnerResolver resolver;

    @Override
    public Optional<User> findByChatId(Long chatId) {
        return resolver.findByChatId(chatId);
    }
}
