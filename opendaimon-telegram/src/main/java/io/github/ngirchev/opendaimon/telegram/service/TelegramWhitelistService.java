package io.github.ngirchev.opendaimon.telegram.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import io.github.ngirchev.opendaimon.bulkhead.service.IWhitelistService;
import io.github.ngirchev.opendaimon.telegram.TelegramBot;
import io.github.ngirchev.opendaimon.telegram.model.TelegramUser;
import io.github.ngirchev.opendaimon.telegram.model.TelegramWhitelist;
import io.github.ngirchev.opendaimon.telegram.repository.TelegramUserRepository;
import io.github.ngirchev.opendaimon.telegram.repository.TelegramWhitelistRepository;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
public class TelegramWhitelistService implements IWhitelistService {

    private final Set<Long> allowedUsers = new HashSet<>();
    private final TelegramWhitelistRepository whitelistRepository;
    private final TelegramBot telegramBot;
    private final TelegramUserRepository telegramUserRepository;
    private final Set<String> whitelistChannelIdExceptions;

    public TelegramWhitelistService(
            TelegramWhitelistRepository whitelistRepository,
            @Lazy TelegramBot telegramBot,
            TelegramUserRepository telegramUserRepository,
            Set<String> whitelistChannelIdExceptions) {
        this.whitelistRepository = whitelistRepository;
        this.telegramBot = telegramBot;
        this.telegramUserRepository = telegramUserRepository;
        this.whitelistChannelIdExceptions = whitelistChannelIdExceptions;
    }


    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        List<Long> allUserIds = whitelistRepository.findAllUserIds();
        allowedUsers.addAll(allUserIds);

        List<TelegramUser> whitelistUsers = whitelistRepository.findAllTelegramUsers();
        List<Long> allTelegramIds = whitelistUsers.stream()
                .map(TelegramUser::getTelegramId)
                .toList();

        log.info("Loaded user whitelist ({} entries): userIds={}, telegramIds={}",
                whitelistUsers.size(), allUserIds, allTelegramIds);
    }

    @Override
    public boolean isUserAllowed(Long userId) {
        return allowedUsers.contains(userId);
    }

    @Transactional
    @Override
    public void addToWhitelist(Long userId) {
        if (!allowedUsers.contains(userId)) {

            TelegramUser user = telegramUserRepository.findById(userId)
                    .orElseThrow(() -> new IllegalArgumentException("User not found"));
            
            TelegramWhitelist whitelist = new TelegramWhitelist();
            whitelist.setUser(user);
            whitelistRepository.save(whitelist);
            allowedUsers.add(user.getId());
            log.info("User {} added to whitelist", user.getTelegramId());
        }
    }

    @Override
    public boolean checkUserInChannel(Long userId) {
        if (whitelistChannelIdExceptions == null || whitelistChannelIdExceptions.isEmpty()) {
            log.debug("Channel/group list not configured, skipping membership check");
            return false;
        }

        TelegramUser user = telegramUserRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        
        // Check user in all channels from list
        for (String channelId : whitelistChannelIdExceptions) {
            try {
                GetChatMember getChatMember = new GetChatMember();
                getChatMember.setChatId(channelId.trim());


                getChatMember.setUserId(user.getTelegramId());
                ChatMember chatMember = telegramBot.execute(getChatMember);
                boolean isMember = ChatMemberStatus.fromTelegramStatus(chatMember.getStatus()).isMember();
                if (isMember) {
                    log.debug("User {} found in channel/group {}", user.getTelegramId(), channelId);
                    return true;
                }
            } catch (TelegramApiException e) {
                log.debug("User {} is not a member of channel/group {}: {}",
                        user.getTelegramId(), channelId, e.getMessage());
                // Continue checking next channel
            }
        }
        
        log.debug("User {} not found in any channel/group: {}", user.getTelegramId(), whitelistChannelIdExceptions);
        return false;
    }

    private enum ChatMemberStatus {
        CREATOR,
        ADMINISTRATOR,
        MEMBER,
        RESTRICTED,
        LEFT,
        KICKED;

        public boolean isMember() {
            return this == CREATOR || this == ADMINISTRATOR || this == MEMBER;
        }

        public static ChatMemberStatus fromTelegramStatus(String s) {
            return ChatMemberStatus.valueOf(s.toUpperCase());
        }
    }
}
