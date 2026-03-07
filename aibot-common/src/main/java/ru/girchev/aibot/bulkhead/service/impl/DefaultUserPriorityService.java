package ru.girchev.aibot.bulkhead.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.girchev.aibot.bulkhead.model.UserPriority;
import ru.girchev.aibot.bulkhead.service.IUserObject;
import ru.girchev.aibot.bulkhead.service.IUserPriorityService;
import ru.girchev.aibot.bulkhead.service.IUserService;
import ru.girchev.aibot.bulkhead.service.IWhitelistService;


/**
 * Реализация сервиса для определения приоритета пользователя.
 * Определяет приоритет на основе данных пользователя Telegram.
 */
@Slf4j
@RequiredArgsConstructor
public class DefaultUserPriorityService implements IUserPriorityService {

    private final IUserService userService;
    private final IWhitelistService whitelistService;

    /**
     * Определяет приоритет пользователя по его идентификатору.
     * Администраторы всегда получают ADMIN приоритет независимо от других условий.
     * Если пользователь заблокирован в Telegram - возвращает BLOCKED.
     * Если пользователь имеет премиум статус - возвращает VIP.
     * В остальных случаях - возвращает REGULAR.
     *
     * @param userId идентификатор пользователя
     * @return приоритет пользователя (ADMIN, VIP, REGULAR или BLOCKED)
     */
    @Override
    public UserPriority getUserPriority(Long userId) {
        if (userId == null) {
            return UserPriority.BLOCKED;
        }

        var user = userService.findById(userId);
        
        // Администраторы всегда получают ADMIN приоритет независимо от других условий
        if (user.map(IUserObject::getIsAdmin).map(Boolean.TRUE::equals).orElse(false)) {
            return UserPriority.ADMIN;
        }

        // Проверяем, есть ли пользователь в белом списке
        if (!whitelistService.isUserAllowed(userId)) {
            // Если нет в белом списке, проверяем членство в канале
            if (whitelistService.checkUserInChannel(userId)) {
                // Если пользователь в канале, добавляем в белый список
                whitelistService.addToWhitelist(userId);
            } else {
                return UserPriority.BLOCKED;
            }
        }

        if (user.map(IUserObject::getIsBlocked).map(Boolean.TRUE::equals).orElse(false)) {
            return UserPriority.BLOCKED;
        } else if (user.map(IUserObject::getIsPremium).map(Boolean.TRUE::equals).orElse(false)) {
            return UserPriority.VIP;
        } else {
            return UserPriority.REGULAR;
        }
    }
} 