package com.limy.util;

import com.anthropic.core.JsonValue;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.function.Function;

public class ToolWrapper <T> {
    private final Function<T, String> fun;
    private final Class<T> type;
    private final ObjectMapper objectMapper;

    public ToolWrapper(Function<T, String> fun, Class<T> type) {
        this.fun = fun;
        this.type = type;
        this.objectMapper = new ObjectMapper();
    }

    public Function<T, String> getCommand() {
        return fun;
    }

    public String executeCommand(JsonValue input) {
        T convert = objectMapper.convertValue(input, type);
        return fun.apply(convert);
    }
}
