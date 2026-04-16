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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;


public class TeammateManagerS10 extends Base implements MemberManager {

    private final Path dir;
    private final Path configPath;
    private final Config config;
    private final ObjectMapper objectMapper;
    private final ExecutorService executorService;
    private final MessageBus messageBus;
    private final Map<String, TeammateToolWrapper<?>> toolHandlers;
    private final Lock lock = new ReentrantLock();
    private final Map<String, ShutdownRequest> shutdownRequests = new HashMap<>();
    private final Map<String, PlanReview> planRequests = new HashMap<>();

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
            ),
            ToolUnion.ofTool(
                    Tool.builder().name("shutdownResponse").description("Respond to a shutdown request. Approve to shut down, reject to keep working.")
                            .inputSchema(
                                    Tool.InputSchema.builder().type(JsonValue.from("object"))
                                            .putAdditionalProperty("requestId", JsonValue.from(Map.of("type", "string")))
                                            .putAdditionalProperty("approve", JsonValue.from(Map.of("type", "boolean")))
                                            .putAdditionalProperty("reason", JsonValue.from(Map.of("type", "string")))
                                            .required(List.of("requestId", "approve"))
                                            .build()
                            ).build()
            ),
            ToolUnion.ofTool(
                    Tool.builder().name("planApproval").description("Submit a plan for lead approval. Provide plan text.")
                            .inputSchema(
                                    Tool.InputSchema.builder().type(JsonValue.from("object"))
                                            .putAdditionalProperty("plan", JsonValue.from(Map.of("type", "string")))
                                            .required(List.of("plan"))
                                            .build()
                            ).build()
            )
    );

    public TeammateManagerS10(Path teamDir, MessageBus messageBus) {
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
                }, MessageBus.ReadInboxCommand.class, this),
                "shutdownResponse", new TeammateToolWrapper<> (this::shutdownResponse, ShutdownResponseCommand.class, this),
                "planApproval", new TeammateToolWrapper<>(this::planApproval, PlanApprovalCommand.class, this)
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
        this.lock.lock();
        try {
        if (Files.exists(configPath)) {
            try {
                return objectMapper.readValue(configPath.toFile(), Config.class);
            } catch (IOException e) {
                throw new RuntimeException("Failed to load config", e);
            }
        }
        return new Config("default", new ArrayList<>());
        } finally {
            this.lock.unlock();
        }
    }

    private void saveConfig() {
        this.lock.lock();
        try {
            objectMapper.writeValue(configPath.toFile(), config);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save config", e);
        } finally {
            this.lock.unlock();
        }
    }

    private Optional<Member> findMember(String name) {
        return config.members().stream().filter(member -> member.getName().equals(name)).findFirst();
    }

    public record SpawnCommand(String name, String role, String prompt) {
    }

    public String spawn(SpawnCommand command) {
        this.lock.lock();
        try {
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
        } finally {
            this.lock.unlock();
        }
    }


    public void teammateLoop(SpawnCommand command) {

        var sysPrompt = String.format("You are '%s', role: %s, at %s. \nSubmit plans via plan_approval before major work. \nRespond to shutdown_request with shutdown_response.",
                command.name(), command.role(), WORKDIR);
        var messages = new ArrayList<MessageParam>();
        messages.add(MessageParam.builder().role(MessageParam.Role.USER).content(command.prompt()).build());

        var shouldExit = false;
        var hasNothingToDo = false;
        for (int i = 0; i < 50; i++) {
            var inbox = this.messageBus.readInbox(new MessageBus.ReadInboxCommand(command.name()));
            if (hasNothingToDo && (null == inbox || inbox.isEmpty())) {
                try {
                    TimeUnit.SECONDS.sleep(1);
                } catch (Exception _) {

                }
                continue;
            }  else {
                hasNothingToDo = false;
            }
            try {
                for (Map<String, Object> msg : inbox) {
                    messages.addLast(MessageParam.builder().role(MessageParam.Role.USER).content(objectMapper.writeValueAsString(msg)).build());
                }
                if (shouldExit) {
                    break;
                }
                var paramsBuilder = MessageCreateParams.builder()
                        .model(MODEL).system(sysPrompt)
                        .messages(messages)
                        .tools(TOOLS).maxTokens(8000);

                var response = CLIENT.messages().create(paramsBuilder.build());
                addAssistants(messages, response);

                // If the model didn't call a tool, we're done
                if (!response.stopReason().orElse(StopReason.END_TURN).equals(StopReason.TOOL_USE)) {
                    hasNothingToDo = true;
                    continue;
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
                        if (toolUseBlock.name().equals("shutdownResponse")) {
                            var shutdownResponse = objectMapper.convertValue(toolUseBlock._input(), ShutdownResponseCommand.class);
                            if (shutdownResponse.getApprove()) {
                                shouldExit = true;
                            }
                        }
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

        this.lock.lock();
        try {
            var member = this.findMember(command.name());
            if (member.isPresent() && !member.get().getStatus().equals("shutdown")) {
                member.get().setStatus("idle");
                this.saveConfig();
            }
        } finally {
            this.lock.unlock();
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




    public record HandleShutdownRequestCommand(String teammate) {}
    public String handleShutdownRequest(HandleShutdownRequestCommand command) {
        var requestId = UUID.randomUUID().toString().substring(0, 8);
        this.lock.lock();
        try {
            shutdownRequests.put(requestId, new ShutdownRequest(command.teammate(), "pending"));
        } finally {
            this.lock.unlock();
        }
        var sendCommand = new MessageBus.SendCommand(
                "lead", command.teammate(), "Please shut down gracefully.", "shutdown_request",
                Map.of("request_id", requestId)
        );
        this.messageBus.send(sendCommand);
        return String.format("Shutdown request %s sent to '%s' (status: pending)" , requestId, command.teammate());
    }


    public static class ShutdownResponseCommand implements MessageBus.BusSender {
        private String requestId;
        private String reason;
        private boolean approve;
        private String sender;

        public ShutdownResponseCommand() {
        }

        public ShutdownResponseCommand(String requestId, String reason, boolean approve) {
            this.requestId = requestId;
            this.reason = reason;
            this.approve = approve;
        }

        public String getRequestId() {
            return requestId;
        }

        public void setRequestId(String requestId) {
            this.requestId = requestId;
        }

        public boolean getApprove() {
            return approve;
        }

        public void setApprove(boolean approve) {
            this.approve = approve;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }

        @Override
        public String getSender() {
            return this.sender;
        }

        @Override
        public void setSender(String sender) {
            this.sender = sender;
        }
    }

    public static class ShutdownRequest {
        private String target;
        private String status;

        public ShutdownRequest() {
        }

        public ShutdownRequest(String status, String target) {
            this.status = status;
            this.target = target;
        }

        public String getTarget() {
            return target;
        }

        public void setTarget(String target) {
            this.target = target;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }
    }

    public record HandlePlanReviewCommand(String requestId, boolean approve, String feedback) {}

    public String handlePlanReview(HandlePlanReviewCommand command) {
        this.lock.lock();
        try {
            var req = this.planRequests.get(command.requestId());
            if (null == req) {
                return String.format("Error: Unknown plan request_id '%s'", command.requestId());
            }
            req.setStatus(command.approve() ? "approved" : "rejected");
            String feedback = command.feedback() == null ? "" : command.feedback();
            this.messageBus.send(new MessageBus.SendCommand(
                    "lead", req.getFrom(), feedback, "plan_approval_response",
                    Map.of("request_id", command.requestId(), "approve", command.approve(), "feedback", feedback)
            ));
            return String.format("Plan %s for '%s'", req.getStatus(), req.getFrom());
        } finally {
            this.lock.unlock();
        }
    }

    public static class PlanReview {
        private String from;
        private String plan;
        private String status;

        public PlanReview() {
        }

        public PlanReview(String from, String plan, String status) {
            this.from = from;
            this.plan = plan;
            this.status = status;
        }

        public String getFrom() {
            return from;
        }

        public void setFrom(String from) {
            this.from = from;
        }

        public String getPlan() {
            return plan;
        }

        public void setPlan(String plan) {
            this.plan = plan;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }
    }
    public record CheckShutdownStatusCommand(String requestId) {}

    public String checkShutdownStatus(CheckShutdownStatusCommand command) {
        this.lock.lock();
        try {
            ShutdownRequest shutdownRequest = shutdownRequests.get(command.requestId());
            if (null == shutdownRequest) {
                return "{\"error\": \"not found\"}";
            }
            return this.objectMapper.writeValueAsString(shutdownRequest);
        } catch (JsonProcessingException e) {

        } finally {
            this.lock.unlock();
        }
        return "";
    }

    public String shutdownResponse(ShutdownResponseCommand command) {
        this.lock.lock();
        try {
            var shutdownRequest = this.shutdownRequests.get(command.getRequestId());
            if (Objects.nonNull(shutdownRequest)) {
                shutdownRequest.setStatus(command.getApprove() ? "approved" : "rejected");
            }
            this.messageBus.send(new MessageBus.SendCommand(
                    command.sender, "lead", command.getReason() == null ? "" : command.getReason(), "shutdown_response",
                    Map.of("request_id", command.getRequestId(), "approve", command.getApprove())
            ));
        } finally {
            this.lock.unlock();
        }
        return String.format("Shutdown %s", command.getApprove() ? "approved" : "rejected");
    }

    public static class PlanApprovalCommand implements MessageBus.BusSender {
        private String sender;
        private String plan;

        public PlanApprovalCommand() {
        }

        public PlanApprovalCommand(String sender, String plan) {
            this.sender = sender;
            this.plan = plan;
        }

        @Override
        public String getSender() {
            return sender;
        }

        @Override
        public void setSender(String sender) {
            this.sender = sender;
        }

        public String getPlan() {
            return plan;
        }

        public void setPlan(String plan) {
            this.plan = plan;
        }
    }

    public String planApproval(PlanApprovalCommand command) {
        var planText = null == command.getPlan() ? "" : command.getPlan();
        var requestId = UUID.randomUUID().toString().substring(0, 8);
        this.lock.lock();
        try {
            this.planRequests.put(requestId, new PlanReview(command.getSender(), planText, "pending"));
            this.messageBus.send(new MessageBus.SendCommand(
                    command.getSender(), "lead", planText, "plan_approval_request", Map.of(
                            "request_id", requestId,"plan", planText
            )
            ));
        } finally {
            this.lock.unlock();
        }
        return String.format("Plan submitted (request_id=%s). Waiting for lead approval.", requestId);
    }
}