package ru.girchev.telegram.utils;

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
            // Убираем @ если он есть в начале username
            username = username.startsWith("@") ? username.substring(1) : username;
            
            System.out.println("Получаем информацию о канале " + CHANNEL_ID + "...");

            GetChatAdministrators getChatAdministrators = new GetChatAdministrators();
            getChatAdministrators.setChatId(CHANNEL_ID);
            ArrayList<ChatMember> execute = sender.execute(getChatAdministrators);
            System.out.println(execute);

            GetChat getChat = new GetChat();
            getChat.setChatId(CHANNEL_ID);
            Chat chat = sender.execute(getChat);
            System.out.println(chat);

            // Получаем количество участников
            GetChatMemberCount getMemberCount = new GetChatMemberCount();
            getMemberCount.setChatId(CHANNEL_ID);
            
            try {
                Integer memberCount = sender.execute(getMemberCount);
                System.out.println("Количество участников в канале: " + memberCount);
                
                // Пробуем найти конкретного пользователя
                GetChatMember getChatMember = new GetChatMember();
                getChatMember.setChatId(CHANNEL_ID);
//                getChatMember.setUserId(username);

                try {
                    ChatMember member = sender.execute(getChatMember);
                    if (member != null && member.getUser() != null) {
                        System.out.println("Найден пользователь: " + member.getUser().getUserName() + 
                                         " (ID: " + member.getUser().getId() + 
                                         ", Имя: " + member.getUser().getFirstName() + " " + 
                                         member.getUser().getLastName() + ")");
                        return member.getUser().getId();
                    }
                } catch (TelegramApiException ex) {
                    System.out.println("Ошибка при поиске пользователя: " + ex.getMessage());
                }
            } catch (TelegramApiException ex) {
                System.out.println("Ошибка при получении информации о канале: " + ex.getMessage());
            }
            
            return null;
        } catch (Exception e) {
            System.out.println("Неожиданная ошибка: " + e.getMessage());
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
            System.out.print("Введите username пользователя (или 'exit' для выхода): ");
            String username = scanner.nextLine().trim();
            
            if (username.equalsIgnoreCase("exit")) {
                System.out.println("До свидания!");
                scanner.close();
                return;
            }

            Long userId = finder.findUserIdByUsername(username);
            if (userId != null) {
                System.out.println("ID пользователя " + username + ": " + userId);
            } else {
                System.out.println("Не удалось найти пользователя " + username);
            }
        }
    }
} 