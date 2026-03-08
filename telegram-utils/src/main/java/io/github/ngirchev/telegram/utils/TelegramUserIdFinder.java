package io.github.ngirchev.telegram.utils;

import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChat;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatAdministrators;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMemberCount;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.bots.DefaultAbsSender;
import org.telegram.telegrambots.bots.DefaultBotOptions;

import java.util.ArrayList;
import java.util.Scanner;

@Slf4j
public class TelegramUserIdFinder {

    private static final String BOT_TOKEN = System.getenv("TELEGRAM_TOKEN");
    private static final String CHANNEL_ID = System.getenv("TELEGRAM_CHANNEL_ID");
    private final DefaultAbsSender sender;

    public TelegramUserIdFinder() {
        this.sender = new DefaultAbsSender(new DefaultBotOptions()) {
            @Override
            public String getBotToken() {
                return BOT_TOKEN;
            }
        };
    }

    public Long findUserIdByUsername(String username) {
        try {
            // Strip @ from username if present
            username = username.startsWith("@") ? username.substring(1) : username;
            
            System.out.println("Fetching channel info for " + CHANNEL_ID + "...");

            GetChatAdministrators getChatAdministrators = new GetChatAdministrators();
            getChatAdministrators.setChatId(CHANNEL_ID);
            ArrayList<ChatMember> execute = sender.execute(getChatAdministrators);
            System.out.println(execute);

            GetChat getChat = new GetChat();
            getChat.setChatId(CHANNEL_ID);
            Chat chat = sender.execute(getChat);
            System.out.println(chat);

            // Get member count
            GetChatMemberCount getMemberCount = new GetChatMemberCount();
            getMemberCount.setChatId(CHANNEL_ID);
            
            try {
                Integer memberCount = sender.execute(getMemberCount);
                System.out.println("Channel member count: " + memberCount);
                
                // Try to find the specific user
                GetChatMember getChatMember = new GetChatMember();
                getChatMember.setChatId(CHANNEL_ID);
//                getChatMember.setUserId(username);

                try {
                    ChatMember member = sender.execute(getChatMember);
                    if (member != null && member.getUser() != null) {
                        System.out.println("Found user: " + member.getUser().getUserName() + 
                                         " (ID: " + member.getUser().getId() + 
                                         ", Name: " + member.getUser().getFirstName() + " " + 
                                         member.getUser().getLastName() + ")");
                        return member.getUser().getId();
                    }
                } catch (TelegramApiException ex) {
                    System.out.println("Error finding user: " + ex.getMessage());
                }
            } catch (TelegramApiException ex) {
                System.out.println("Error fetching channel info: " + ex.getMessage());
            }
            
            return null;
        } catch (Exception e) {
            System.out.println("Unexpected error: " + e.getMessage());
            return null;
        }
    }

    public static void main(String[] args) {
        if (BOT_TOKEN == null || BOT_TOKEN.isBlank() || CHANNEL_ID == null || CHANNEL_ID.isBlank()) {
            System.err.println("Set TELEGRAM_TOKEN and TELEGRAM_CHANNEL_ID environment variables.");
            return;
        }
        Scanner scanner = new Scanner(System.in);
        TelegramUserIdFinder finder = new TelegramUserIdFinder();

        while (true) {
            System.out.print("Enter username (or 'exit' to quit): ");
            String username = scanner.nextLine().trim();
            
            if (username.equalsIgnoreCase("exit")) {
                System.out.println("Goodbye!");
                scanner.close();
                return;
            }

            Long userId = finder.findUserIdByUsername(username);
            if (userId != null) {
                System.out.println("User ID for " + username + ": " + userId);
            } else {
                System.out.println("Could not find user " + username);
            }
        }
    }
} 