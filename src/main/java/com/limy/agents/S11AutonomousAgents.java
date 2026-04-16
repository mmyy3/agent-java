package com.limy.agents;

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
import com.limy.util.EnhancedBashExecutor;
import com.limy.util.MessageBus;
import com.limy.util.PathUtils;
import com.limy.util.TaskManager;
import com.limy.util.TeammateManagerS11;
import com.limy.util.TeammateToolWrapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * s11_autonomous_agents.py - Autonomous Agents
 * <p>
 * Idle cycle with task board polling, auto-claiming unclaimed tasks, and
 * identity re-injection after context compression. Builds on s10's protocols.
 * <p>
 * Teammate lifecycle:
 * +-------+
 * | spawn |
 * +---+---+
 * |
 * v
 * +-------+  tool_use    +-------+
 * | WORK  | <----------- |  LLM  |
 * +---+---+              +-------+
 * |
 * | stop_reason != tool_use
 * v
 * +--------+
 * | IDLE   | poll every 5s for up to 60s
 * +---+----+
 * |
 * +---> check inbox -> message? -> resume WORK
 * |
 * +---> scan .tasks/ -> unclaimed? -> claim -> resume WORK
 * |
 * +---> timeout (60s) -> shutdown
 * <p>
 * Identity re-injection after compression:
 * messages = [identity_block, ...remaining...]
 * "You are 'coder', role: backend, team: my-team"
 * <p>
 * Key insight: "The agent finds work itself."
 */
public class S11AutonomousAgents extends Base {

    private static final Path TEAM_DIR = Path.of(WORKDIR, ".team");
    private static final Path TASKS_DIR = Path.of(WORKDIR, ".tasks");
    private static final Path INBOX_DIR = TEAM_DIR.resolve("inbox");
    private static final String SYSTEM = "You are a team lead at " + WORKDIR + ". Teammates are autonomous -- they find work themselves.";


    private static final ObjectMapper OBJECT_MAPPER;
    private static final MessageBus BUS = new MessageBus(INBOX_DIR);
    private static final TaskManager TASKS = new TaskManager(TASKS_DIR);
    private static final TeammateManagerS11 TEAM = new TeammateManagerS11(TEAM_DIR, BUS, TASKS);

    private static final Map<String, TeammateToolWrapper<?>> TOOL_HANDLERS = new HashMap<>();
    private static final List<ToolUnion> TOOLS = new ArrayList<>();

    static {
        OBJECT_MAPPER = new ObjectMapper();
        OBJECT_MAPPER.enable(SerializationFeature.INDENT_OUTPUT);

        TOOL_HANDLERS.put("bash", new TeammateToolWrapper<>(EnhancedBashExecutor::runBash, EnhancedBashExecutor.BashCommand.class, TEAM));
        TOOL_HANDLERS.put("readFile", new TeammateToolWrapper<>(PathUtils::runRead, PathUtils.ReadCommand.class, TEAM));
        TOOL_HANDLERS.put("writeFile", new TeammateToolWrapper<>(PathUtils::runWrite, PathUtils.WriteCommand.class, TEAM));
        TOOL_HANDLERS.put("editFile", new TeammateToolWrapper<>(PathUtils::runEdit, PathUtils.EditCommand.class, TEAM));
        TOOL_HANDLERS.put("sendMessage", new TeammateToolWrapper<>(BUS::send, MessageBus.SendCommand.class, TEAM));
        TOOL_HANDLERS.put("readInbox", new TeammateToolWrapper<>(arg -> {
            try {
                return OBJECT_MAPPER.writeValueAsString(BUS.readInbox(arg));
            } catch (JsonProcessingException e) {
                return "{}";
            }
        }, MessageBus.ReadInboxCommand.class, TEAM));
        TOOL_HANDLERS.put("broadcast", new TeammateToolWrapper<>(BUS::broadcast, MessageBus.BroadcastCommand.class, TEAM));
        TOOL_HANDLERS.put("spawnTeammate", new TeammateToolWrapper<>(TEAM::spawn, TeammateManagerS11.SpawnCommand.class, TEAM));
        TOOL_HANDLERS.put("listTeammates", new TeammateToolWrapper<>(_ -> TEAM.listAll(), Object.class, TEAM));
        TOOL_HANDLERS.put("shutdownRequest", new TeammateToolWrapper<>(TEAM::handleShutdownRequest, TeammateManagerS11.HandleShutdownRequestCommand.class, TEAM));
        TOOL_HANDLERS.put("shutdownResponse", new TeammateToolWrapper<>(TEAM::checkShutdownStatus, TeammateManagerS11.CheckShutdownStatusCommand.class, TEAM));
        TOOL_HANDLERS.put("planApproval", new TeammateToolWrapper<>(TEAM::handlePlanReview, TeammateManagerS11.HandlePlanReviewCommand.class, TEAM));
        TOOL_HANDLERS.put("taskCreate", new TeammateToolWrapper<>(TASKS::create, TaskManager.CreateTaskCommand.class, TEAM));
        TOOL_HANDLERS.put("taskUpdate", new TeammateToolWrapper<>(TASKS::update, TaskManager.UpdateTaskCommand.class, TEAM));
        TOOL_HANDLERS.put("taskList", new TeammateToolWrapper<>(_ -> TASKS.listAll(), Object.class, TEAM));
        TOOL_HANDLERS.put("taskGet", new TeammateToolWrapper<>(TASKS::get, TaskManager.GetTaskCommand.class, TEAM));
        TOOL_HANDLERS.put("claimTask", new TeammateToolWrapper<>(TASKS::claimTask, TaskManager.ClaimTaskCommand.class, TEAM));

        TOOLS.add(ToolUnion.ofTool(
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
        ));
        TOOLS.add(ToolUnion.ofTool(
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
        ));
        TOOLS.add(ToolUnion.ofTool(
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
        ));
        TOOLS.add(ToolUnion.ofTool(
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
        ));
        TOOLS.add(ToolUnion.ofTool(
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
        ));
        TOOLS.add(ToolUnion.ofTool(
                Tool.builder().name("readInbox").description("Read and drain your inbox.")
                        .inputSchema(
                                Tool.InputSchema.builder().type(JsonValue.from("object")).build()
                        ).build()
        ));
        TOOLS.add(ToolUnion.ofTool(
                Tool.builder().name("broadcast").description("Send a message to all teammates.")
                        .inputSchema(
                                Tool.InputSchema.builder().type(JsonValue.from("object"))
                                        .putAdditionalProperty("content", JsonValue.from(Map.of("type", "string")))
                                        .required(List.of("content"))
                                        .build()
                        ).build()
        ));
        TOOLS.add(ToolUnion.ofTool(
                Tool.builder().name("spawnTeammate").description("Spawn a persistent teammate that runs in its own thread.")
                        .inputSchema(
                                Tool.InputSchema.builder().type(JsonValue.from("object"))
                                        .putAdditionalProperty("name", JsonValue.from(Map.of("type", "string")))
                                        .putAdditionalProperty("role", JsonValue.from(Map.of("type", "string")))
                                        .putAdditionalProperty("prompt", JsonValue.from(Map.of("type", "string")))
                                        .required(List.of("name", "role", "prompt"))
                                        .build()
                        ).build()
        ));
        TOOLS.add(ToolUnion.ofTool(
                Tool.builder().name("listTeammates").description("List all teammates with name, role, status.")
                        .inputSchema(
                                Tool.InputSchema.builder().type(JsonValue.from("object"))
                                        .build()
                        ).build()
        ));
        TOOLS.add(ToolUnion.ofTool(
                Tool.builder().name("shutdownRequest").description("Request a teammate to shut down gracefully. Returns a request_id for tracking.")
                        .inputSchema(
                                Tool.InputSchema.builder().type(JsonValue.from("object"))
                                        .putAdditionalProperty("teammate", JsonValue.from(Map.of("type", "string")))
                                        .required(List.of("teammate"))
                                        .build()
                        ).build()
        ));
        TOOLS.add(ToolUnion.ofTool(
                Tool.builder().name("shutdownResponse").description("Check the status of a shutdown request by request_id.")
                        .inputSchema(
                                Tool.InputSchema.builder().type(JsonValue.from("object"))
                                        .putAdditionalProperty("requestId", JsonValue.from(Map.of("type", "string")))
                                        .required(List.of("requestId"))
                                        .build()
                        ).build()
        ));
        TOOLS.add(ToolUnion.ofTool(
                Tool.builder().name("planApproval").description("Approve or reject a teammate's plan. Provide request_id + approve + optional feedback.")
                        .inputSchema(
                                Tool.InputSchema.builder().type(JsonValue.from("object"))
                                        .putAdditionalProperty("requestId", JsonValue.from(Map.of("type", "string")))
                                        .putAdditionalProperty("approve", JsonValue.from(Map.of("type", "boolean")))
                                        .putAdditionalProperty("feedback", JsonValue.from(Map.of("type", "string")))
                                        .required(List.of("requestId", "approve"))
                                        .build()
                        ).build()
        ));

        TOOLS.add(ToolUnion.ofTool(
                Tool.builder().name("taskCreate").description("Create a new task.")
                        .inputSchema(
                                Tool.InputSchema.builder().type(JsonValue.from("object"))
                                        .properties(
                                                Tool.InputSchema.Properties.builder()
                                                        .putAdditionalProperty("subject", JsonValue.from(Map.of("type", "string")))
                                                        .putAdditionalProperty("description", JsonValue.from(Map.of("type", "string")))
                                                        .build()
                                        )
                                        .required(List.of("subject"))
                                        .build()
                        ).build()
        ));

        TOOLS.add(ToolUnion.ofTool(
                Tool.builder().name("taskUpdate").description("Update a task's status or dependencies.")
                        .inputSchema(
                                Tool.InputSchema.builder().type(JsonValue.from("object"))
                                        .properties(
                                                Tool.InputSchema.Properties.builder()
                                                        .putAdditionalProperty("taskId", JsonValue.from(Map.of("type", "integer")))
                                                        .putAdditionalProperty("status", JsonValue.from(Map.of("type", "string",
                                                                "enum", List.of("pending", "in_progress", "completed"))))
                                                        .putAdditionalProperty("addBlockedBy", JsonValue.from(Map.of("type", "array", "items", Map.of("type", "integer"))))
                                                        .putAdditionalProperty("addBlocks", JsonValue.from(Map.of("type", "array", "items", Map.of("type", "integer"))))
                                                        .build()
                                        )
                                        .required(List.of("task_id"))
                                        .build()
                        ).build()
        ));

        TOOLS.add(ToolUnion.ofTool(
                Tool.builder().name("taskList").description("List all tasks with status summary.")
                        .inputSchema(
                                Tool.InputSchema.builder().type(JsonValue.from("object"))
                                        .properties(
                                                Tool.InputSchema.Properties.builder().build()
                                        )
                                        .build()
                        ).build()
        ));

        TOOLS.add(ToolUnion.ofTool(
                Tool.builder().name("taskGet").description("Get full details of a task by ID.")
                        .inputSchema(
                                Tool.InputSchema.builder().type(JsonValue.from("object"))
                                        .properties(
                                                Tool.InputSchema.Properties.builder()
                                                        .putAdditionalProperty("taskId", JsonValue.from(Map.of("type", "integer")))
                                                        .build()
                                        )
                                        .required(List.of("taskId"))
                                        .build()
                        ).build()
        ));

        TOOLS.add(ToolUnion.ofTool(
                Tool.builder().name("claimTask").description("Claim a task from the task board by ID.")
                        .inputSchema(
                                Tool.InputSchema.builder().type(JsonValue.from("object"))
                                        .properties(
                                                Tool.InputSchema.Properties.builder()
                                                        .putAdditionalProperty("taskId", JsonValue.from(Map.of("type", "integer")))
                                                        .build()
                                        )
                                        .required(List.of("taskId"))
                                        .build()
                        ).build()
        ));
    }


    private static void agentLoop(List<MessageParam> messages) {
        boolean waitResponse = false;
        boolean hasMemberWorking = false;
        while (true) {
            if (hasMemberWorking) {
                try {
                    TimeUnit.SECONDS.sleep(1);
                } catch (InterruptedException _) {
                }
                hasMemberWorking = TEAM.listAllMembers().stream()
                        .anyMatch(m -> !m.getStatus().equals("idle") && !m.getStatus().equals("shutdown"));
                continue;
            }
            var inbox = BUS.readInbox(new MessageBus.ReadInboxCommand("lead"));

            if (null != inbox && !inbox.isEmpty()) {
                try {
                    messages.addLast(MessageParam.builder().role(MessageParam.Role.USER).content(String.format("<inbox>" + OBJECT_MAPPER.writeValueAsString(inbox) + "</inbox>")).build());
                    messages.addLast(MessageParam.builder().role(MessageParam.Role.ASSISTANT).content("Noted inbox messages.").build());
                } catch (JsonProcessingException e) {
                    throw new RuntimeException(e);
                }
                waitResponse = false;
            } else if (waitResponse) {
                try {
                    TimeUnit.SECONDS.sleep(1);
                } catch (InterruptedException _) {
                }
                continue;
            }

            var paramsBuilder = MessageCreateParams.builder()
                    .model(MODEL).system(SYSTEM)
                    .messages(messages)
                    .tools(TOOLS).maxTokens(8000);

            var response = CLIENT.messages().create(paramsBuilder.build());

            addAssistants(messages, response);

            // If the model didn't call a tool, we're done
            if (!response.stopReason().orElse(StopReason.END_TURN).equals(StopReason.TOOL_USE)) {
                 hasMemberWorking = TEAM.listAllMembers().stream()
                        .anyMatch(m -> !m.getStatus().equals("idle") && !m.getStatus().equals("shutdown"));
                if (hasMemberWorking) {
                    messages.add(MessageParam.builder().role(MessageParam.Role.USER).content("Please wait for all teammate finished, then give the final result").build());
                    continue;
                }
                return;
            }

            List<ContentBlockParam> results = new ArrayList<>();
            // Execute each tool call, collect results
            for (ContentBlock contentBlock : response.content()) {
                if (contentBlock.isToolUse()) {
                    var toolUseBlock = contentBlock.toolUse().orElseThrow();
                    var handler = TOOL_HANDLERS.get(toolUseBlock.name());
                    var output = "";
                    if (null == handler) {
                        output = "Unknown tool: " + toolUseBlock.name();
                    } else {
                        output = handler.executeCommand("lead", toolUseBlock._input());
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
                    if (toolUseBlock.name().equals("broadcast") || toolUseBlock.name().equals("sendMessage") || toolUseBlock.name().equals("shutdownRequest") || toolUseBlock.name().equals("shutdownResponse") || toolUseBlock.name().equals("planApproval")) {
                        waitResponse = true;
                    }
                }
            }
            messages.add(
                    MessageParam.builder()
                            .role(MessageParam.Role.USER)
                            .content(MessageParam.Content.ofBlockParams(results))
                            .build()
            );
        }
    }

    public static void main(String[] ignoredArgs) {
        var history = new ArrayList<MessageParam>();
        while (true) {
            var query = "";
            try {
                query = IO.readln("\033[36ms11 >> \033[0m").strip();
            } catch (Exception e) {
                break;
            }
            if (query.equalsIgnoreCase("q") || query.equalsIgnoreCase("exit") || query.equalsIgnoreCase("")) {
                break;
            }
            if (query.equalsIgnoreCase("/team")) {
                IO.println(TEAM.listAll());
                continue;
            }
            if (query.equalsIgnoreCase("/inbox")) {
                try {
                    IO.println(OBJECT_MAPPER.writeValueAsString(BUS.readInbox(new MessageBus.ReadInboxCommand("lead"))));
                } catch (JsonProcessingException ignored) {

                }
                continue;
            }
            if (query.equalsIgnoreCase("/tasks")) {
                IO.println(TASKS.listAll());
                continue;
            }


            history.add(MessageParam.builder().role(MessageParam.Role.USER).content(query).build());
            agentLoop(history);
            var responseContent = history.getLast().content();
            responseContent.blockParams().stream()
                    .flatMap(Collection::stream)
                    .filter(ContentBlockParam::isText)
                    .forEach(block -> IO.println(block.text().orElseThrow().text().trim()));
        }
    }
}
