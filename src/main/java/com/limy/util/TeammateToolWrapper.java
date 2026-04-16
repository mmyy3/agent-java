package com.limy.util;

import com.anthropic.core.JsonValue;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.function.Function;

public class TeammateToolWrapper<T> {
    private final Function<T, String> fun;
    private final Class<T> type;
    private final MemberManager manager;
    private final ObjectMapper objectMapper;

    public TeammateToolWrapper(Function<T, String> fun, Class<T> type, MemberManager manager) {
        this.fun = fun;
        this.type = type;
        this.manager = manager;
        this.objectMapper = new ObjectMapper();
    }

    public Function<T, String> getCommand() {
        return fun;
    }

    public String executeCommand(String sender, JsonValue input) {
        try {
            // Use ObjectMapper to convert the map to the target type
            T convert = objectMapper.convertValue(input, type);

            if (convert instanceof MessageBus.BusSender aBusSender) {
                aBusSender.setSender(sender);
            }
            if (convert instanceof MessageBus.BroadcastCommand broadcastCommand) {
                broadcastCommand.setTeammates(this.manager.memberNames());
            }
            return fun.apply(convert);
        } catch (Exception e) {
            System.err.println("Error converting input: " + e.getMessage());
            throw new RuntimeException("Failed to convert input to " + type.getSimpleName() + ": " + e.getMessage(), e);
        }
    }
}
