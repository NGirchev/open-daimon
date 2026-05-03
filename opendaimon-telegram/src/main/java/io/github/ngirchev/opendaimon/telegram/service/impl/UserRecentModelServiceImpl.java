package io.github.ngirchev.opendaimon.telegram.service.impl;

import io.github.ngirchev.opendaimon.common.model.User;
import io.github.ngirchev.opendaimon.common.model.UserRecentModel;
import io.github.ngirchev.opendaimon.common.repository.UserRecentModelRepository;
import io.github.ngirchev.opendaimon.common.repository.UserRepository;
import io.github.ngirchev.opendaimon.telegram.service.UserRecentModelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
public class UserRecentModelServiceImpl implements UserRecentModelService {

    /**
     * Maximum number of recent-model rows retained per user. Keeping this value
     * aligned with {@code ModelTelegramCommandHandler.PAGE_SIZE} avoids the need
     * for extra pagination inside the "Recent" category.
     */
    public static final int RECENT_CAP = 8;

    private final UserRecentModelRepository userRecentModelRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public void recordUsage(Long userId, String modelName) {
        if (userId == null || modelName == null || modelName.isBlank()) {
            log.warn("Skipping recordUsage for userId={} modelName='{}'", userId, modelName);
            return;
        }

        Optional<UserRecentModel> existing = userRecentModelRepository
                .findByUserIdAndModelName(userId, modelName);

        if (existing.isPresent()) {
            UserRecentModel entry = existing.get();
            entry.setLastUsedAt(OffsetDateTime.now());
            userRecentModelRepository.save(entry);
        } else {
            UserRecentModel entry = new UserRecentModel();
            User userRef = userRepository.getReferenceById(userId);
            entry.setUser(userRef);
            entry.setModelName(modelName);
            entry.setLastUsedAt(OffsetDateTime.now());
            userRecentModelRepository.save(entry);
        }

        pruneBeyondCap(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> getRecentModels(Long userId, int limit) {
        if (userId == null || limit <= 0) {
            return List.of();
        }
        Pageable page = PageRequest.of(0, limit);
        return userRecentModelRepository.findTopByUser(userId, page).stream()
                .map(UserRecentModel::getModelName)
                .toList();
    }

    /**
     * Retains only the top-{@link #RECENT_CAP} most recent entries for the user;
     * deletes everything older. Performed after each upsert so the table is
     * bounded regardless of concurrent history size.
     */
    private void pruneBeyondCap(Long userId) {
        Pageable page = PageRequest.of(0, RECENT_CAP);
        List<Long> retainIds = userRecentModelRepository.findTopByUser(userId, page).stream()
                .map(UserRecentModel::getId)
                .toList();
        if (retainIds.isEmpty()) {
            return;
        }
        userRecentModelRepository.deleteByUserIdAndIdNotIn(userId, retainIds);
    }
}
