package io.github.ngirchev.opendaimon.rest.command;

import jakarta.servlet.http.HttpServletRequest;
import io.github.ngirchev.opendaimon.common.command.IChatCommand;

public record RestChatCommand(
        String message,
        String assistantRole,
        String model,
        String email,
        RestChatCommandType commandType,
        HttpServletRequest request,
        Long userId
) implements IChatCommand<RestChatCommandType> {

    @Override
    public Long userId() {
        return userId != null ? userId : 0L;
    }

    @Override
    public String userText() {
        return message;
    }

    @Override
    public boolean stream() {
        return commandType == RestChatCommandType.STREAM;
    }
}
