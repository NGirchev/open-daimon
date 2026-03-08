package io.github.ngirchev.aibot.telegram.service;

import io.github.ngirchev.aibot.bulkhead.service.IUserPriorityService;
import io.github.ngirchev.aibot.bulkhead.service.PriorityRequestExecutor;
import io.github.ngirchev.aibot.common.command.CommandHandlerRegistry;
import io.github.ngirchev.aibot.common.meter.AIBotMeterRegistry;
import io.github.ngirchev.aibot.common.service.CommandSyncService;

public class TelegramCommandSyncService extends CommandSyncService {
    public TelegramCommandSyncService(AIBotMeterRegistry aiBotMeterRegistry, CommandHandlerRegistry commandHandlerRegistry, PriorityRequestExecutor priorityRequestExecutor, IUserPriorityService userPriorityService) {
        super(aiBotMeterRegistry, commandHandlerRegistry, priorityRequestExecutor, userPriorityService);
    }
}
