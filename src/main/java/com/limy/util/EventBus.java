package com.limy.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EventBus  {
    private final Path eventLogPath;
    private final ObjectMapper objectMapper;

    public EventBus(Path eventLogPath) {
        this.eventLogPath = eventLogPath;
        this.objectMapper = new ObjectMapper();

        try {
            // 创建父目录
            Files.createDirectories(eventLogPath.getParent());

            // 如果文件不存在，创建空文件
            if (!Files.exists(eventLogPath)) {
                Files.createFile(eventLogPath);
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

    public record EmitCommand(String event, Map<String, Object> task, Map<String, Object> worktree, String error) {}

    public void emit(EmitCommand command) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("event", command.event());
        payload.put("ts", System.currentTimeMillis() / 1000.0);
        payload.put("task", command.task() != null ? command.task() : Collections.emptyMap());
        payload.put("worktree", command.worktree() != null ? command.worktree() : Collections.emptyMap());
        
        if (command.error() != null) {
            payload.put("error", command.error());
        }

        try {
            var jsonLine = objectMapper.writeValueAsString(payload) + "\n";
            Files.writeString(eventLogPath, jsonLine,
                       StandardOpenOption.APPEND, StandardOpenOption.CREATE);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write event to log file", e);
        }
    }

    public record ListRecentCommand(Integer limit) {}

    public String listRecent(ListRecentCommand command) {
        var limit = command.limit();
        if (null == limit) {
            limit = 20;
        }
        int n = Math.max(1, Math.min(limit, 200));

        try {
            var lines = Files.readAllLines(eventLogPath, StandardCharsets.UTF_8);
            int startIndex = Math.max(0, lines.size() - n);
            var recentLines = lines.subList(startIndex, lines.size());

            List<Map<String, Object>> items = new ArrayList<>();

            for (String line : recentLines) {
                if (line.trim().isEmpty()) {
                    continue;
                }

                try {
                    Map<String, Object> eventData = objectMapper.readValue(line, new TypeReference<>(){});
                    items.add(eventData);
                } catch (Exception e) {
                    Map<String, Object> errorItem = new HashMap<>();
                    errorItem.put("event", "parse_error");
                    errorItem.put("raw", line);
                    items.add(errorItem);
                }
            }

            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(items);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read event log file", e);
        }
    }
}