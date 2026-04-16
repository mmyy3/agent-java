package com.limy.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MessageBus: JSONL inbox per teammate
 */
public class MessageBus {

    public static final Set<String> VALID_MSG_TYPES = Set.of(
            "message",
            "broadcast",
            "shutdown_request",
            "shutdown_response",
            "plan_approval_response",
            "plan_approval_request"
    );

    private final Path dir;
    private final ObjectMapper objectMapper;

    public MessageBus(Path inboxDir) {
        this.dir = inboxDir;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        this.objectMapper = new ObjectMapper();
    }

    public interface BusSender {
        String getSender();

        void setSender(String sender);

    }

    public static class SendCommand implements BusSender {
        private String sender;
        private String to;
        private String content;
        private String msgType;
        private Map<String, Object> extra;

        public SendCommand() {
        }

        public SendCommand(String sender, String to, String content, String msgType, Map<String, Object> extra) {
            this.sender = sender;
            this.to = to;
            this.content = content;
            this.msgType = msgType;
            this.extra = extra;
        }

        @Override
        public String getSender() {
            return sender;
        }

        @Override
        public void setSender(String sender) {
            this.sender = sender;
        }

        public String getTo() {
            return to;
        }

        public void setTo(String to) {
            this.to = to;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public String getMsgType() {
            return msgType;
        }

        public void setMsgType(String msgType) {
            this.msgType = msgType;
        }

        public Map<String, Object> getExtra() {
            return extra;
        }

        public void setExtra(Map<String, Object> extra) {
            this.extra = extra;
        }
    }

    /**
     * Send a message to a specific teammate
     */
    public String send(SendCommand command) {
        if (!VALID_MSG_TYPES.contains(command.getMsgType())) {
            return String.format("Error: Invalid type '%s'. Valid: %s", command.getMsgType(), VALID_MSG_TYPES);
        }

        Map<String, Object> message = new HashMap<>();
        message.put("type", command.getMsgType());
        message.put("from", command.getSender());
        message.put("content", command.getContent());
        message.put("timestamp", Instant.now().toEpochMilli() / 1000);

        if (command.getExtra() != null) {
            message.putAll(command.getExtra());
        }

        Path inboxPath = dir.resolve(command.getTo() + ".jsonl");

        try (BufferedWriter writer = Files.newBufferedWriter(inboxPath,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            writer.write(objectMapper.writeValueAsString(message));
            writer.newLine();
            return String.format("Sent %s to %s", command.getMsgType(), command.getTo());
        } catch (IOException e) {
            return String.format("Error sending message: %s", e.getMessage());
        }
    }

    public static class ReadInboxCommand implements BusSender {
        private String name;

        public ReadInboxCommand() {
        }

        public ReadInboxCommand(String name) {
            this.name = name;
        }

        @Override
        public String getSender() {
            return this.name;
        }

        @Override
        public void setSender(String sender) {
            this.name = sender;
        }
    }

    /**
     * Read all messages from a teammate's inbox
     */
    public List<Map<String, Object>> readInbox(ReadInboxCommand command) {
        Path inboxPath = dir.resolve(command.getSender() + ".jsonl");

        if (!Files.exists(inboxPath)) {
            return new ArrayList<>();
        }

        List<Map<String, Object>> messages = new ArrayList<>();

        try {
            List<String> lines = Files.readAllLines(inboxPath);

            for (String line : lines) {
                if (!line.trim().isEmpty()) {
                    try {
                        var message = objectMapper.readValue(line, new TypeReference<Map<String, Object>>() {
                        });
                        messages.add(message);
                    } catch (Exception e) {
                        System.err.println("Error parsing message: " + e.getMessage());
                    }
                }
            }

            Files.write(inboxPath, new byte[0], StandardOpenOption.TRUNCATE_EXISTING);

        } catch (IOException e) {
            System.err.println("Error reading inbox: " + e.getMessage());
        }

        return messages;
    }

    public static class BroadcastCommand implements BusSender {
        private String sender;
        private String content;
        private List<String> teammates;

        public BroadcastCommand() {
        }

        public BroadcastCommand(String sender, String content, List<String> teammates) {
            this.sender = sender;
            this.content = content;
            this.teammates = teammates;
        }

        @Override
        public String getSender() {
            return this.sender;
        }

        @Override
        public void setSender(String sender) {
            this.sender = sender;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public List<String> getTeammates() {
            return teammates;
        }

        public void setTeammates(List<String> teammates) {
            this.teammates = teammates;
        }
    }

    /**
     * Broadcast a message to all teammates except the sender
     */
    public String broadcast(BroadcastCommand command) {
        int count = 0;
        for (String name : command.getTeammates()) {
            if (!name.equals(command.getSender())) {
                String result = send(new SendCommand(command.getSender(), name, command.getContent(), "broadcast", null));
                if (!result.startsWith("Error")) {
                    count++;
                }
            }
        }
        return String.format("Broadcast to %d teammates", count);
    }
}