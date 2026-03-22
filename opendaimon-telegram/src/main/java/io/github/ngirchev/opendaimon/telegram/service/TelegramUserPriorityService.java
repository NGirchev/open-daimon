package io.github.ngirchev.opendaimon.telegram.service;

import io.github.ngirchev.opendaimon.bulkhead.model.UserPriority;
import io.github.ngirchev.opendaimon.bulkhead.service.IUserObject;
import io.github.ngirchev.opendaimon.bulkhead.service.IUserPriorityService;
import io.github.ngirchev.opendaimon.bulkhead.service.IWhitelistService;
import io.github.ngirchev.opendaimon.telegram.config.TelegramProperties;
import io.github.ngirchev.opendaimon.telegram.model.TelegramUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;


@Slf4j
@RequiredArgsConstructor
public class TelegramUserPriorityService implements IUserPriorityService {

    private final TelegramUserService userService;
    private final IWhitelistService whitelistService;
    private final TelegramProperties telegramProperties;

    @Override
    public UserPriority getUserPriority(Long userId) {
        if (userId == null) {
            return UserPriority.BLOCKED;
        }

        var user = userService.findById(userId);

        TelegramProperties.AccessConfig access = telegramProperties.getAccess();
        Set<Long> adminIds = access.getAdmin().getIds();
        Set<String> adminChannels = access.getAdmin().getChannels();
        Set<Long> vipIds = access.getVip().getIds();
        Set<String> vipChannels = access.getVip().getChannels();
        Set<Long> regularIds = access.getRegular().getIds();
        Set<String> regularChannels = access.getRegular().getChannels();

        boolean inAdminChannel = isUserInChannels(userId, adminChannels);
        boolean inWhitelist = whitelistService.isUserAllowed(userId);
        boolean inRegularChannel = isUserInChannels(userId, regularChannels);
        boolean inVipChannel = isUserInChannels(userId, vipChannels);

        if (adminIds.contains(userId) || user.map(IUserObject::getIsAdmin).map(Boolean.TRUE::equals).orElse(false) || inAdminChannel) {
            return UserPriority.ADMIN;
        }

        if (access.getAdmin().isDefaultBlocked()) {
            return UserPriority.BLOCKED;
        }

        if (user.map(IUserObject::getIsBlocked).map(Boolean.TRUE::equals).orElse(false)) {
            return UserPriority.BLOCKED;
        }

        if (vipIds.contains(userId) || user.map(IUserObject::getIsPremium).map(Boolean.TRUE::equals).orElse(false) || inVipChannel) {
            if (user.isPresent()) {
                Long telegramId = ((TelegramUser) user.get()).getTelegramId();
                userService.ensureUserWithLevel(telegramId, UserPriority.VIP);
            }
            return UserPriority.VIP;
        }

        if (access.getVip().isDefaultBlocked()) {
            return UserPriority.BLOCKED;
        }

        if (inRegularChannel || regularIds.contains(userId) || inWhitelist) {
            return UserPriority.REGULAR;
        }

        return access.getRegular().isDefaultBlocked() ? UserPriority.BLOCKED : UserPriority.REGULAR;
    }

    private boolean isUserInChannels(Long userId, Set<String> channels) {
        if (channels == null || channels.isEmpty()) {
            return false;
        }
        for (String channelId : channels) {
            try {
                if (whitelistService.checkUserInChannel(userId, channelId)) {
                    return true;
                }
            } catch (Exception e) {
                log.debug("Could not check channel membership for user {} in channel {}: {}", userId, channelId, e.getMessage());
            }
        }
        return false;
    }
}
