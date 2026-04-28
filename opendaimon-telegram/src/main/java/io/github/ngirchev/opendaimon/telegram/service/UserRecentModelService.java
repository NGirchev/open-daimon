package io.github.ngirchev.opendaimon.telegram.service;

import java.util.List;

/**
 * Tracks recently picked AI models per user so the {@code /model} menu can
 * offer a "Recent" shortcut category. Written only on explicit user choice
 * (not on {@code Auto} reset).
 */
public interface UserRecentModelService {

    /**
     * Upsert-records an explicit model pick. Updates {@code lastUsedAt} if the
     * pair (user, modelName) already exists, inserts a new row otherwise, and
     * prunes the user's history to the top entries so the table stays bounded.
     *
     * @param userId    internal user id ({@code user.id})
     * @param modelName gateway-provided model identifier
     */
    void recordUsage(Long userId, String modelName);

    /**
     * Returns up to {@code limit} recent model names for the user, ordered by
     * most recent first. Empty list if the user has no history yet.
     */
    List<String> getRecentModels(Long userId, int limit);
}
