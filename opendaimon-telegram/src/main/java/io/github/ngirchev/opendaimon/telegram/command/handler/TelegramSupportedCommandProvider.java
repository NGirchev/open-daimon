package io.github.ngirchev.opendaimon.telegram.command.handler;

/**
 * Marker interface for handlers that can provide the description of their supported command
 * (used to build the command list in /start).
 */
public interface TelegramSupportedCommandProvider {

    /**
     * @param languageCode user language code (e.g. ru, en) for localized description
     * @return string like "/command - description" or null if handler should not appear in command list
     */
    String getSupportedCommandText(String languageCode);
}


