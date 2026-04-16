package com.limy.util;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUnion;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.limy.agents.Base;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;


public class TeammateManagerS09 extends Base implements MemberManager{

    private final Path dir;
    private final Path configPath;
    private final Config config;
    private final ObjectMapper objectMapper;
    private final ExecutorService executorService;
    private final MessageBus messageBus;
    private final Map<String, TeammateToolWrapper<?>> toolHandlers;

    private static final List<ToolUnion> TOOLS = List.of(
            ToolUnion.ofTool(
                    Tool.builder().name("bash").description("Run a shell command.")
                            .inputSchema(
                                    Tool.InputSchema.builder().type(JsonValue.from("object"))

                                            .properties(
                                                    Tool.InputSchema.Properties.builder()
                                                            .putAdditionalProperty("command", JsonValue.from(Map.of("type", "string")))
                                                            .build()
                                            )
                                            .required(List.of("command"))
                                            .build()
                            ).build()
            ),
            ToolUnion.ofTool(
                    Tool.builder().name("readFile").description("Read file contents.")
                            .inputSchema(
                                    Tool.InputSchema.builder().type(JsonValue.from("object"))

                                            .properties(
                                                    Tool.InputSchema.Properties.builder()
                                                            .putAdditionalProperty("path", JsonValue.from(Map.of("type", "string")))
                                                            .putAdditionalProperty("limit", JsonValue.from(Map.of("type", "integer")))
                                                            .build()
                                            )
                                            .required(List.of("path"))
                                            .build()
                            ).build()
            ),
            ToolUnion.ofTool(
                    Tool.builder().name("writeFile").description("Write content to file.")
                            .inputSchema(
                                    Tool.InputSchema.builder().type(JsonValue.from("object"))

                                            .properties(
                                                    Tool.InputSchema.Properties.builder()
                                                            .putAdditionalProperty("path", JsonValue.from(Map.of("type", "string")))
                                                            .putAdditionalProperty("content", JsonValue.from(Map.of("type", "string")))
                                                            .build()
                                            )
                                            .required(List.of("path", "content"))
                                            .build()
                            ).build()
            ),
            ToolUnion.ofTool(
                    Tool.builder().name("editFile").description("Replace exact text in file.")
                            .inputSchema(
                                    Tool.InputSchema.builder().type(JsonValue.from("object"))

                                            .properties(
                                                    Tool.InputSchema.Properties.builder()
                                                            .putAdditionalProperty("path", JsonValue.from(Map.of("type", "string")))
                                                            .putAdditionalProperty("oldText", JsonValue.from(Map.of("type", "string")))
                                                            .putAdditionalProperty("newText", JsonValue.from(Map.of("type", "string")))
                                                            .build()
                                            )
                                            .required(List.of("path", "oldText", "newText"))
                                            .build()
                            ).build()
            ),
            ToolUnion.ofTool(
                    Tool.builder().name("sendMessage").description("Send message to a teammate.")
                            .inputSchema(
                                    Tool.InputSchema.builder().type(JsonValue.from("object"))

                                            .properties(
                                                    Tool.InputSchema.Properties.builder()
                                                            .putAdditionalProperty("to", JsonValue.from(Map.of("type", "string")))
                                                            .putAdditionalProperty("content", JsonValue.from(Map.of("type", "string")))
                                                            .putAdditionalProperty("msgType", JsonValue.from(Map.of("type", "string", "enum", MessageBus.VALID_MSG_TYPES)))
                                                            .build()
                                            )
                                            .required(List.of("to", "content", "msgType"))
                                            .build()
                            ).build()
            ),
            ToolUnion.ofTool(
                    Tool.builder().name("readInbox").description("Read and drain your inbox.")
                            .inputSchema(
                                    Tool.InputSchema.builder().type(JsonValue.from("object")).build()
                            ).build()
            )
    );

    public TeammateManagerS09(Path teamDir, MessageBus messageBus) {
        this.dir = teamDir;
        this.messageBus = messageBus;
        this.configPath = dir.resolve("config.json");
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        this.executorService = Executors.newFixedThreadPool(10);

        try {
            Files.createDirectories(dir);
            this.config = loadConfig();
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize TeammateManager", e);
        }

        toolHandlers = Map.of(
                "bash", new TeammateToolWrapper<>(EnhancedBashExecutor::runBash, EnhancedBashExecutor.BashCommand.class, this),
                "readFile", new TeammateToolWrapper<>(PathUtils::runRead, PathUtils.ReadCommand.class, this),
                "writeFile", new TeammateToolWrapper<>(PathUtils::runWrite, PathUtils.WriteCommand.class, this),
                "editFile", new TeammateToolWrapper<>(PathUtils::runEdit, PathUtils.EditCommand.class, this),
                "sendMessage", new TeammateToolWrapper<>(messageBus::send, MessageBus.SendCommand.class, this),
                "readInbox", new TeammateToolWrapper<>(arg -> {
                    try {
                        return objectMapper.writeValueAsString(messageBus.readInbox(arg));
                    } catch (JsonProcessingException e) {
                        return "{}";
                    }
                }, MessageBus.ReadInboxCommand.class, this)
        );
    }

    public static class Member {
        private String name;
        private String role;
        private String status;

        public Member() {
        }

        public Member(String name, String role, String status) {
            this.name = name;
            this.role = role;
            this.status = status;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }
    }

    public record Config(String teamName, List<Member> members) {
    }

    private Config loadConfig() {
        if (Files.exists(configPath)) {
            try {
                return objectMapper.readValue(configPath.toFile(), Config.class);
            } catch (IOException e) {
                throw new RuntimeException("Failed to load config", e);
            }
        }
        return new Config("default", new ArrayList<>());
    }

    private void saveConfig() {
        try {
            objectMapper.writeValue(configPath.toFile(), config);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save config", e);
        }
    }

    public Optional<Member> findMember(String name) {
        return config.members().stream().filter(member -> member.getName().equals(name)).findFirst();
    }

    public record SpawnCommand(String name, String role, String prompt) {
    }

    public String spawn(SpawnCommand command) {
        var memberOptional = this.findMember(command.name());
        if (memberOptional.isPresent()) {
            var member = memberOptional.get();
            if (!member.getStatus().equals("idle") && !member.getStatus().equals("shutdown")) {
                return String.format("Error: '%s' is currently %s", member.getName(), member.getStatus());
            }
            member.setStatus("working");
            member.setRole(command.role());
        } else {
            var member = new Member(command.name(), command.role(), "working");
            this.config.members().add(member);
        }
        this.saveConfig();
        this.executorService.execute(() -> this.teammateLoop(command));
        return String.format("Spawned '%s' (role: %s)", command.name(), command.role());
    }


    public void teammateLoop(SpawnCommand command) {
        var sysPrompt = String.format("You are '%s', role: %s, at %s. \nUse send_message to communicate. Complete your task.",
                command.name(), command.role(), WORKDIR);
        var messages = new ArrayList<MessageParam>();
        messages.add(MessageParam.builder().role(MessageParam.Role.USER).content(command.prompt()).build());
        for (int i = 0; i < 50; i++) {
            var inbox = this.messageBus.readInbox(new MessageBus.ReadInboxCommand(command.name()));
            try {
                for (Map<String, Object> msg : inbox) {
                    messages.addLast(MessageParam.builder().role(MessageParam.Role.USER).content(objectMapper.writeValueAsString(msg)).build());
                }
                var paramsBuilder = MessageCreateParams.builder()
                        .model(MODEL).system(sysPrompt)
                        .messages(messages)
                        .tools(TOOLS).maxTokens(8000);

                var response = CLIENT.messages().create(paramsBuilder.build());
                addAssistants(messages, response);

                // If the model didn't call a tool, we're done
                if (!response.stopReason().orElse(StopReason.END_TURN).equals(StopReason.TOOL_USE)) {
                    break;
                }

                List<ContentBlockParam> results = new ArrayList<>();
                for (ContentBlock contentBlock : response.content()) {
                    if (contentBlock.isToolUse()) {
                        var toolUseBlock = contentBlock.toolUse().orElseThrow();
                        var handler = toolHandlers.get(toolUseBlock.name());
                        var output = "";
                        if (null == handler) {
                            output = "Unknown tool: " + toolUseBlock.name();
                        } else {
                            output = handler.executeCommand(command.name(), toolUseBlock._input());
                            IO.println(String.format("> %s: %s}", toolUseBlock.name(), output.substring(0, Math.min(output.length(), 200)).trim()));
                        }
                        results.add(
                                ContentBlockParam.ofToolResult(
                                        ToolResultBlockParam.builder()
                                                .toolUseId(toolUseBlock.id())
                                                .content(output)
                                                .build()
                                )
                        );
                    }
                }
                messages.add(
                        MessageParam.builder()
                                .role(MessageParam.Role.USER)
                                .content(MessageParam.Content.ofBlockParams(results))
                                .build()
                );

            } catch (JsonProcessingException e) {
                break;
            }

        }

        var member = this.findMember(command.name());
        if (member.isPresent() && !member.get().getStatus().equals("shutdown")) {
            member.get().setStatus("idle");
            this.saveConfig();
        }
    }

    public String listAll() {
        if (this.config.members().isEmpty()) {
            return "No teammates.";
        }
        List<String> lines = new ArrayList<>();
        lines.add("Team: " + this.config.teamName());
        for (Member m : this.config.members()) {
            lines.add("  " + m.getName() + " (" + m.getRole() + "): " + m.getStatus());
        }
        return String.join("\n", lines);
    }

    @Override
    public List<String> memberNames() {
        return this.config.members().stream()
                .map(Member::getName)
                .collect(Collectors.toList());
    }

    public void shutdown() {
        executorService.shutdown();
    }
}