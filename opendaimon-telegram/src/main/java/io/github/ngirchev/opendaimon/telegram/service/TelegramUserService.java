package io.github.ngirchev.opendaimon.telegram.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.objects.User;
import io.github.ngirchev.opendaimon.common.SupportedLanguages;
import io.github.ngirchev.opendaimon.bulkhead.model.UserPriority;
import io.github.ngirchev.opendaimon.bulkhead.service.IUserObject;
import io.github.ngirchev.opendaimon.bulkhead.service.IUserService;
import io.github.ngirchev.opendaimon.common.model.AssistantRole;
import io.github.ngirchev.opendaimon.common.service.AssistantRoleService;
import io.github.ngirchev.opendaimon.telegram.model.TelegramUser;
import io.github.ngirchev.opendaimon.telegram.model.TelegramUserSession;
import io.github.ngirchev.opendaimon.telegram.repository.TelegramUserRepository;

import org.telegram.telegrambots.meta.api.objects.Chat;

import java.time.OffsetDateTime;
import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
public class TelegramUserService implements IUserService {

    private static final String USER_NOT_FOUND = "User not found";

    private final TelegramUserRepository telegramUserRepository;
    private final TelegramUserSessionService telegramUserSessionService;
    private final AssistantRoleService assistantRoleService;

    @Override
    public Optional<IUserObject> findById(Long id) {
        return telegramUserRepository.findById(id).map(IUserObject.class::cast);
    }

    public Optional<TelegramUser> findByTelegramId(Long telegramId) {
        return telegramUserRepository.findByTelegramId(telegramId);
    }

    @Transactional
    public TelegramUser getOrCreateUser(User telegramUser) {
        return getOrCreateUserInner(telegramUser);
    }

    @Transactional
    public TelegramUser updateUserActivity(TelegramUser user) {
        OffsetDateTime now = OffsetDateTime.now();
        user.setUpdatedAt(now);
        user.setLastActivityAt(now);
        return telegramUserRepository.save(user);
    }

    @Transactional
    public TelegramUser createUser(User telegramUser) {
        return createUserInner(telegramUser);
    }

    /**
     * Updates the assistant role for the user.
     *
     * @param telegramUser          Telegram API user
     * @param assistantRoleContent  new role content
     * @return updated user
     */
    @Transactional
    public TelegramUser updateAssistantRole(User telegramUser, String assistantRoleContent) {
        TelegramUser user = telegramUserRepository.findByTelegramId(telegramUser.getId())
                .orElseThrow(() -> new RuntimeException(USER_NOT_FOUND));
        
        AssistantRole role = assistantRoleService.updateActiveRole(user, assistantRoleContent);
        user.setCurrentAssistantRole(role);
        OffsetDateTime now = OffsetDateTime.now();
        user.setUpdatedAt(now);
        user.setLastActivityAt(now);
        return telegramUserRepository.save(user);
    }

    /**
     * Returns the active assistant role for the user, creating one with default content if none exists.
     *
     * @param user           user
     * @param defaultContent default role content
     * @return active role
     */
    @Transactional
    public AssistantRole getOrCreateAssistantRole(TelegramUser user, String defaultContent) {
        // Re-load user in this transaction (caller may pass detached user from bulkhead thread)
        Long telegramId = user.getTelegramId();
        if (telegramId == null) {
            throw new IllegalArgumentException("telegramId is null");
        }

        TelegramUser managedUser = telegramUserRepository.findByTelegramId(telegramId)
                .orElseThrow(() -> new RuntimeException(USER_NOT_FOUND));

        AssistantRole role = managedUser.getCurrentAssistantRole();
        if (role == null) {
            role = assistantRoleService.getOrCreateDefaultRole(managedUser, defaultContent);

            managedUser.setCurrentAssistantRole(role);
            OffsetDateTime now = OffsetDateTime.now();
            managedUser.setUpdatedAt(now);
            managedUser.setLastActivityAt(now);
            telegramUserRepository.save(managedUser);
        }

        // Initialize role fields in this transaction to avoid LazyInitializationException later
        role.getId();
        role.getVersion();
        role.getContent();

        return role;
    }

    /**
     * Updates the user's language by telegramId and, if present, reapplies the language
     * requirement in the active assistant role.
     *
     * @param telegramId   Telegram user id
     * @param languageCode new language code (e.g. "ru", "en")
     * @return updated user
     */
    @Transactional
    public TelegramUser updateLanguageCode(Long telegramId, String languageCode) {
        TelegramUser user = telegramUserRepository.findByTelegramId(telegramId)
                .orElseThrow(() -> new RuntimeException(USER_NOT_FOUND));
        String normalized = languageCode != null && !languageCode.isBlank()
                ? languageCode.trim().toLowerCase().split("-")[0] : SupportedLanguages.DEFAULT_LANGUAGE;
        user.setLanguageCode(normalized);
        OffsetDateTime now = OffsetDateTime.now();
        user.setUpdatedAt(now);
        user.setLastActivityAt(now);

        return telegramUserRepository.save(user);
    }

    /**
     * Updates the bot status in the user's current session.
     *
     * @param user      user
     * @param botStatus new bot status
     */
    @Transactional
    public void updateUserSession(TelegramUser user, String botStatus) {
        telegramUserSessionService.updateSessionStatus(user, botStatus);
    }

    @Transactional
    public TelegramUserSession getOrCreateSession(User telegramUser) {
        TelegramUser user = getOrCreateUserInner(telegramUser);
        return telegramUserSessionService.getOrCreateSession(user);
    }

    @Transactional
    public Optional<TelegramUserSession> tryToGetSession(Long userId) {
        return findByTelegramId(userId).map(telegramUserSessionService::getOrCreateSession);
    }

    private TelegramUser getOrCreateUserInner(User telegramUser) {
        Optional<TelegramUser> existingUser = telegramUserRepository.findByTelegramId(telegramUser.getId());

        if (existingUser.isPresent()) {
            TelegramUser user = existingUser.get();
            updateUserInfo(user, telegramUser);
            return telegramUserRepository.save(user);
        } else {
            return createUserInner(telegramUser);
        }
    }

    private TelegramUser createUserInner(User telegramUser) {
        TelegramUser user = new TelegramUser();
        user.setTelegramId(telegramUser.getId());
        user.setUsername(telegramUser.getUserName());
        user.setFirstName(telegramUser.getFirstName());
        user.setLastName(telegramUser.getLastName());
        String fromApi = telegramUser.getLanguageCode();
        user.setLanguageCode((fromApi != null && !fromApi.isBlank())
                ? fromApi.trim().toLowerCase().split("-")[0]
                : SupportedLanguages.DEFAULT_LANGUAGE);
        user.setIsPremium(telegramUser.getIsPremium());
        OffsetDateTime now = OffsetDateTime.now();
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        user.setLastActivityAt(now);
        user.setIsBlocked(false);
        user.setIsAdmin(false);
        return telegramUserRepository.save(user);
    }

    /**
     * Applies flags by access level (strict matrix). Used for startup and explicit level assignment.
     */
    public static void applyFlagsByLevel(TelegramUser user, UserPriority level) {
        if (level == null) {
            return;
        }
        switch (level) {
            case ADMIN -> {
                user.setIsAdmin(true);
                user.setIsPremium(true);
                user.setIsBlocked(false);
            }
            case VIP -> {
                user.setIsAdmin(false);
                user.setIsPremium(true);
                user.setIsBlocked(false);
            }
            case REGULAR, BLOCKED -> {
                user.setIsAdmin(false);
                user.setIsPremium(false);
                user.setIsBlocked(level == UserPriority.BLOCKED);
            }
        }
    }

    /**
     * Ensures a Telegram user exists with the given telegramId and access level. Creates minimal user if missing, then applies strict flag matrix.
     */
    @Transactional
    public TelegramUser ensureUserWithLevel(Long telegramId, UserPriority level) {
        return ensureUserWithLevel(telegramId, level, Optional.empty());
    }

    /**
     * Ensures a Telegram user exists with the given telegramId and access level. If chatFromApi is present (from getChat),
     * uses real username/firstName/lastName; otherwise uses placeholder "startup_&lt;id&gt;" until the user interacts.
     */
    @Transactional
    public TelegramUser ensureUserWithLevel(Long telegramId, UserPriority level, Optional<Chat> chatFromApi) {
        if (telegramId == null) {
            throw new IllegalArgumentException("telegramId is required for Telegram user");
        }
        TelegramUser user = telegramUserRepository.findByTelegramId(telegramId).orElseGet(() -> {
            TelegramUser newUser = new TelegramUser();
            newUser.setTelegramId(telegramId);
            if (chatFromApi.isPresent()) {
                Chat chat = chatFromApi.get();
                newUser.setUsername(chat.getUserName() != null && !chat.getUserName().isBlank() ? chat.getUserName() : "id_" + telegramId);
                newUser.setFirstName(chat.getFirstName());
                newUser.setLastName(chat.getLastName());
            } else {
                newUser.setUsername("id_" + telegramId);
            }
            OffsetDateTime now = OffsetDateTime.now();
            newUser.setCreatedAt(now);
            newUser.setUpdatedAt(now);
            newUser.setLastActivityAt(now);
            newUser.setIsBlocked(false);
            newUser.setIsPremium(false);
            newUser.setIsAdmin(false);
            newUser.setLanguageCode(null);
            return telegramUserRepository.save(newUser);
        });
        applyFlagsByLevel(user, level);
        OffsetDateTime now = OffsetDateTime.now();
        user.setUpdatedAt(now);
        user.setLastActivityAt(now);
        TelegramUser saved = telegramUserRepository.save(user);
        log.info("Telegram user ensured: id={}, telegramId={}, username='{}', level={}, isAdmin={}, isPremium={}, isBlocked={}",
                saved.getId(), saved.getTelegramId(), saved.getUsername(), level, saved.getIsAdmin(), saved.getIsPremium(), saved.getIsBlocked());
        return saved;
    }

    private void updateUserInfo(TelegramUser user, User telegramUser) {
        String username = telegramUser.getUserName();
        if (username != null) {
            user.setUsername(username);
        }
        String firstName = telegramUser.getFirstName();
        if (firstName != null) {
            user.setFirstName(firstName);
        }
        String lastName = telegramUser.getLastName();
        if (lastName != null) {
            user.setLastName(lastName);
        }
        // Set language from Telegram API only once (when not yet initialized); after that only /language can change it
        if (user.getLanguageCode() == null) {
            String fromApi = telegramUser.getLanguageCode();
            if (fromApi != null && !fromApi.isBlank()) {
                user.setLanguageCode(fromApi.trim().toLowerCase().split("-")[0]);
            }
        }
        Boolean isPremium = telegramUser.getIsPremium();
        if (isPremium != null) {
            user.setIsPremium(isPremium);
        }
        OffsetDateTime now = OffsetDateTime.now();
        user.setUpdatedAt(now);
        user.setLastActivityAt(now);
    }
}
