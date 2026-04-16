package com.limy.util;

import java.util.*;
import java.util.Set;

public class TodoManager {
    
    public record TodoItem(String id, String text, String status) {
        private static final Set<String> VALID_STATUSES = Set.of("pending", "in_progress", "completed");

        public TodoItem {
            if (text == null || text.trim().isEmpty()) {
                throw new IllegalArgumentException("Text cannot be null or empty");
            }
            if (status == null || !VALID_STATUSES.contains(status)) {
                throw new IllegalArgumentException("Invalid status: " + status);
            }
        }
    }

    public record TodoUpdateCommand(List<TodoItem> items) {}
    
    private List<TodoItem> items;

    public TodoManager() {
        this.items = new ArrayList<>();
    }



    public String update(TodoUpdateCommand command) {
        int size = command.items().size();
        if (size > 20) {
            throw new IllegalArgumentException("Max 20 todos allowed");
        }
        
        List<TodoItem> validated = new ArrayList<>(size);
        int inProgressCount = 0;

        for (TodoItem item : command.items()) {
            if ("in_progress".equals(item.status())) {
                inProgressCount++;
                if (inProgressCount > 1) {
                    throw new IllegalArgumentException("Only one task can be in_progress at a time");
                }
            }
            validated.add(item);
        }
        
        this.items = validated;
        return render();
    }

    public String render() {
        if (this.items.isEmpty()) {
            return "No todos.";
        }
        
        List<String> lines = new ArrayList<>();
        int completedCount = 0;
        for (TodoItem item : this.items) {
            String marker = switch (item.status()) {
                case "in_progress" -> "[>]";
                case "completed" -> "[x]";
                default -> "[ ]";
            };
            lines.add(marker + " #" + item.id() + ": " + item.text());
            if ("completed".equals(item.status())) {
                completedCount++;
            }
        }

        lines.add("\n(" + completedCount + "/" + this.items.size() + " completed)");
        return String.join("\n", lines);
    }
}
