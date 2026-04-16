package com.limy.agents;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUnion;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.limy.util.BackgroundManager;
import com.limy.util.EnhancedBashExecutor;
import com.limy.util.MessageBus;
import com.limy.util.PathUtils;
import com.limy.util.SkillLoader;
import com.limy.util.TaskManager;
import com.limy.util.TeammateManagerS11;
import com.limy.util.TeammateToolWrapper;
import com.limy.util.TodoManager;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * s_full.py - Full Reference Agent
 * <p>
 * Capstone implementation combining every mechanism from s01-s11.
 * Session s12 (task-aware worktree isolation) is taught separately.
 * NOT a teaching session -- this is the "put it all together" reference.
 * <p>
 * +------------------------------------------------------------------+
 * |                        FULL AGENT                                 |
 * |                                                                   |
 * |  System prompt (s05 skills, task-first + optional todo nag)      |
 * |                                                                   |
 * |  Before each LLM call:                                            |
 * |  +--------------------+  +------------------+  +--------------+  |
 * |  | Microcompact (s06) |  | Drain bg (s08)   |  | Check inbox  |  |
 * |  | Auto-compact (s06) |  | notifications    |  | (s09)        |  |
 * |  +--------------------+  +------------------+  +--------------+  |
 * |                                                                   |
 * |  Tool dispatch (s02 pattern):                                     |
 * |  +--------+----------+----------+---------+-----------+          |
 * |  | bash   | read     | write    | edit    | TodoWrite |          |
 * |  | task   | load_sk  | compress | bg_run  | bg_check  |          |
 * |  | t_crt  | t_get    | t_upd    | t_list  | spawn_tm  |          |
 * |  | list_tm| send_msg | rd_inbox | bcast   | shutdown  |          |
 * |  | plan   | idle     | claim    |         |           |          |
 * |  +--------+----------+----------+---------+-----------+          |
 * |                                                                   |
 * |  Subagent (s04):  spawn -> work -> return summary                 |
 * |  Teammate (s09):  spawn -> work -> idle -> auto-claim (s11)      |
 * |  Shutdown (s10):  request_id handshake                            |
 * |  Plan gate (s10): submit -> approve/reject                        |
 * +------------------------------------------------------------------+
 * <p>
 * REPL commands: /compact /tasks /team /inbox
 */
public class SFull extends Base {

    private static final Path TEAM_DIR = Path.of(WORKDIR, ".team");
    private static final Path INBOX_DIR = TEAM_DIR.resolve("inbox");
    private static final Path TASKS_DIR = Path.of(WORKDIR, ".tasks");
    private static final String TRANSCRIPT_DIR = WORKDIR + File.separator + ".transcripts";
    private static final int THRESHOLD = 50000;
    private static final int KEEP_RECENT = 3;

    private static final TodoManager TODO = new TodoManager();
    private static final SkillLoader SKILLS = new SkillLoader(SKILLS_DIR);
    private static final TaskManager TASKS = new TaskManager(TASKS_DIR);
    private static final BackgroundManager BG;
    private static final MessageBus BUS = new MessageBus(INBOX_DIR);
    private static final TeammateManagerS11 TEAM = new TeammateManagerS11(TEAM_DIR, BUS, TASKS);
    private static final ObjectMapper OBJECT_MAPPER;
    private static final Map<String, TeammateToolWrapper<?>> TOOL_HANDLERS = new HashMap<>();
    private static final List<ToolUnion> TOOLS = new ArrayList<>();

    static {
        OBJECT_MAPPER = new ObjectMapper();
        OBJECT_MAPPER.enable(SerializationFeature.INDENT_OUTPUT);
        BG = new BackgroundManager(WORKDIR);
        Runtime.getRuntime().addShutdownHook(new Thread(BG::shutdown));

        TOOL_HANDLERS.put("bash", new TeammateToolWrapper<>(EnhancedBashExecutor::runBash, EnhancedBashExecutor.BashCommand.class, TEAM));
        TOOL_HANDLERS.put("readFile", new TeammateToolWrapper<>(PathUtils::runRead, PathUtils.ReadCommand.class, TEAM));
        TOOL_HANDLERS.put("writeFile", new TeammateToolWrapper<>(PathUtils::runWrite, PathUtils.WriteCommand.class, TEAM));
        TOOL_HANDLERS.put("editFile", new TeammateToolWrapper<>(PathUtils::runEdit, PathUtils.EditCommand.class, TEAM));
        TOOL_HANDLERS.put("todo", new TeammateToolWrapper<>(TODO::update, TodoManager.TodoUpdateCommand.class, TEAM));
        TOOL_HANDLERS.put("loadSkill", new TeammateToolWrapper<>(SKILLS::getContent, SkillLoader.GetContentParam.class, TEAM));
        TOOL_HANDLERS.put("compact", new TeammateToolWrapper<>(_ -> "Manual compression requested.", CompactCommand.class, TEAM));
        TOOL_HANDLERS.put("backgroundRun", new TeammateToolWrapper<>(BG::run, BackgroundManager.RunBackgroundTaskCommand.class, TEAM));
        TOOL_HANDLERS.put("checkBackground", new TeammateToolWrapper<>(BG::check, BackgroundManager.CheckBackgroundTaskCommand.class, TEAM));
        TOOL_HANDLERS.put("taskCreate", new TeammateToolWrapper<>(TASKS::create, TaskManager.CreateTaskCommand.class, TEAM));
        TOOL_HANDLERS.put("taskUpdate", new TeammateToolWrapper<>(TASKS::update, TaskManager.UpdateTaskCommand.class, TEAM));
        TOOL_HANDLERS.put("taskList", new TeammateToolWrapper<>(_ -> TASKS.listAll(), Object.class, TEAM));
        TOOL_HANDLERS.put("taskGet", new TeammateToolWrapper<>(TASKS::get, TaskManager.GetTaskCommand.class, TEAM));
        TOOL_HANDLERS.put("claimTask", new TeammateToolWrapper<>(TASKS::claimTask, TaskManager.ClaimTaskCommand.class, TEAM));
        TOOL_HANDLERS.put("spawnTeammate", new TeammateToolWrapper<>(TEAM::spawn, TeammateManagerS11.SpawnCommand.class, TEAM));
        TOOL_HANDLERS.put("listTeammates", new TeammateToolWrapper<>(_ -> TEAM.listAll(), Object.class, TEAM));
        TOOL_HANDLERS.put("sendMessage", new TeammateToolWrapper<>(BUS::send, MessageBus.SendCommand.class, TEAM));
        TOOL_HANDLERS.put("readInbox", new TeammateToolWrapper<>(arg -> {
            try {
                return OBJECT_MAPPER.writeValueAsString(BUS.readInbox(arg));
            } catch (JsonProcessingException e) {
                return "{}";
            }
        }, MessageBus.ReadInboxCommand.class, TEAM));
        TOOL_HANDLERS.put("broadcast", new TeammateToolWrapper<>(BUS::broadcast, MessageBus.BroadcastCommand.class, TEAM));
        TOOL_HANDLERS.put("shutdownRequest", new TeammateToolWrapper<>(TEAM::handleShutdownRequest, TeammateManagerS11.HandleShutdownRequestCommand.class, TEAM));
        TOOL_HANDLERS.put("shutdownResponse", new TeammateToolWrapper<>(TEAM::checkShutdownStatus, TeammateManagerS11.CheckShutdownStatusCommand.class, TEAM));
        TOOL_HANDLERS.put("planApproval", new TeammateToolWrapper<>(TEAM::handlePlanReview, TeammateManagerS11.HandlePlanReviewCommand.class, TEAM));




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
                Tool.builder().name("todo").description("Update task list. Track progress on multi-step tasks.")
                        .inputSchema(
                                Tool.InputSchema.builder().type(JsonValue.from("object"))
                                        .properties( Tool.InputSchema.Properties.builder()
                                                .putAdditionalProperty("items", JsonValue.from(Map.of(
                                                        "type", "array",
                                                        "items", Map.of(
                                                                "type", "object",
                                                                "properties", Map.of(
                                                                        "id", Map.of("type", "string"),
                                                                        "text", Map.of("type", "string"),
                                                                        "status", Map.of("type", "string", "enum", List.of("pending", "in_progress", "completed"),
                                                                                "required", List.of("id", "text", "status")
                                                                        )
                                                                ))
                                                ))).build()
                                        )
                                        .required(List.of("items"))
                                        .build()
                        ).build()
        ));
        TOOLS.add(ToolUnion.ofTool(
                Tool.builder().name("loadSkill").description("Load specialized knowledge by name.")
                        .inputSchema(
                                Tool.InputSchema.builder().type(JsonValue.from("object"))

                                        .properties(
                                                Tool.InputSchema.Properties.builder()
                                                        .putAdditionalProperty("name", JsonValue.from(Map.of("type", "string",
                                                                "description", "Skill name to load")))
                                                        .build()
                                        )
                                        .required(List.of("name"))
                                        .build()
                        ).build()
        ));
        TOOLS.add(
                ToolUnion.ofTool(
                        Tool.builder().name("compact").description("Trigger manual conversation compression.")
                                .inputSchema(
                                        Tool.InputSchema.builder().type(JsonValue.from("object"))

                                                .properties(
                                                        Tool.InputSchema.Properties.builder()
                                                                .putAdditionalProperty("focus", JsonValue.from(Map.of("type", "string",
                                                                        "description", "What to preserve in the summary")))
                                                                .build()
                                                )
                                                .build()
                                ).build()
                )
        );


        TOOLS.add(
                ToolUnion.ofTool(
                        Tool.builder().name("backgroundRun").description("Run command in background thread. Returns task_id immediately.")
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
                )
        );

        TOOLS.add(
                ToolUnion.ofTool(
                        Tool.builder().name("checkBackground").description("Check background task status. Omit task_id to list all.")
                                .inputSchema(
                                        Tool.InputSchema.builder().type(JsonValue.from("object"))

                                                .properties(
                                                        Tool.InputSchema.Properties.builder()
                                                                .putAdditionalProperty("taskId", JsonValue.from(Map.of("type", "string")))
                                                                .build()
                                                )
                                                .build()
                                ).build()
                )
        );

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

    }

    private static final String SYSTEM = String.format("""
            You are a coding agent at {WORKDIR}. Use tools to solve tasks.
            Prefer task_create/task_update/task_list for multi-step work. Use TodoWrite for short checklists.
            Use task for subagent delegation. Use load_skill for specialized knowledge.
            Skills: %s
            """, SKILLS.getDescriptions());


    private static void agentLoop(List<MessageParam> messages) {
        boolean isWaitingMemberWorkingStop = false;
        var roundsSinceTodo = 0;
        boolean waitResponse = false;
        var waitResponseCnt = 0;
        while (true) {
            messages = microCompact(messages);
            if (estimateTokens(messages) > THRESHOLD) {
                IO.println("[auto_compact triggered]");
                messages = autoCompact(messages);
            }
            if (isWaitingMemberWorkingStop) {
                IO.println(">>>>>>>>>>>>>>>>等待子agent结束");
                try {
                    TimeUnit.SECONDS.sleep(1);
                } catch (InterruptedException _) {
                }
                isWaitingMemberWorkingStop = TEAM.listAllMembers().stream()
                        .anyMatch(m -> !m.getStatus().equals("idle") && !m.getStatus().equals("shutdown"));
                continue;
            }
            if (roundsSinceTodo > 3 && !messages.isEmpty())  {
                var last = messages.getLast();
                if (last.role().equals(MessageParam.Role.USER) && last.content().isBlockParams()) {
                    var list = new ArrayList<ContentBlockParam>();
                    list.addAll(last.content().asBlockParams());
                    list.add(ContentBlockParam.ofText(TextBlockParam.builder().text("<reminder>Update your todos.</reminder>").build()));
                    var newLast = MessageParam.builder().role(last.role()).content(MessageParam.Content.ofBlockParams(list)).build();
                    messages.set(messages.size() - 1, newLast);
                }
            }
            List<BackgroundManager.Notification> notifications = BG.drainNotifications();
            StringBuilder sb = new StringBuilder();
            for (BackgroundManager.Notification notification : notifications) {
                sb.append("\n").append("[bg:").append(notification.taskId()).append("] ")
                        .append(notification.status()).append(": ")
                        .append(notification.result());
            }
            if (!sb.isEmpty()) {
                messages.add(MessageParam.builder().role(MessageParam.Role.USER)
                        .content(String.format("<background-results>\n%s\n</background-results>", sb))
                        .build());
                messages.add(MessageParam.builder().role(MessageParam.Role.ASSISTANT)
                        .content("Noted background results.")
                        .build());
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
                waitResponseCnt = 0;
            } else if (waitResponse) {
                IO.println(">>>>>>>>>>>>>>>>等待响应结果");
                try {
                    TimeUnit.SECONDS.sleep(1);
                } catch (InterruptedException _) {
                }
                if ((waitResponseCnt++) > 60) {
                    break;
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
                isWaitingMemberWorkingStop = TEAM.listAllMembers().stream()
                        .anyMatch(m -> !m.getStatus().equals("idle") && !m.getStatus().equals("shutdown"));
                if (isWaitingMemberWorkingStop) {
                    messages.add(MessageParam.builder().role(MessageParam.Role.USER).content("Please wait for all teammate finished, then give the final result").build());
                    continue;
                }
                return;
            }

            List<ContentBlockParam> results = new ArrayList<>();
            // Execute each tool call, collect results
            var usedTodo = false;
            boolean manualCompact = false;
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
                        if (toolUseBlock.name().equalsIgnoreCase("todo")) {
                            usedTodo = true;
                        }

                        roundsSinceTodo = usedTodo ? 0 : roundsSinceTodo + 1;

                        if (toolUseBlock.name().equals("compact")) {
                            manualCompact = true;
                        }
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

            if (manualCompact) {
                IO.println("[manual compact]");
                messages = autoCompact(messages);
            }
        }
    }

    /**
     * Rough token count: ~4 chars per token.
     */
    private static int estimateTokens(List<MessageParam> messages) {
        var str = messages.stream().map(MessageParam::toString).collect(Collectors.joining());
        return str.length() / 4;
    }

    private record ToolResult(int msgIndex, int partIndex, ContentBlockParam part) {}
    /**
     * -- Layer 1: micro_compact - replace old tool results with placeholders --
     */
    private static List<MessageParam> microCompact(List<MessageParam> messages) {
        // Collect (msg_index, part_index, tool_result_dict) for all tool_result entries
        var toolResults = new ArrayList<ToolResult>();
        for (int i = 0; i < messages.size(); i++) {
            var msg = messages.get(i);
            if (msg.role().equals(MessageParam.Role.USER) && msg.content().isBlockParams()) {
                var contentBlockParams = msg.content().asBlockParams();
                for (int j = 0; j < contentBlockParams.size(); j++) {
                    var part = contentBlockParams.get(j);
                    if (part.isToolResult()) {
                        toolResults.add(new ToolResult(i, j, part));
                    }
                }
            }
        }
        if (toolResults.size() <= KEEP_RECENT) {
            return messages;
        }
        // Find tool_name for each result by matching tool_use_id in prior assistant messages
        var toolNameMap = new HashMap<String, String>();
        for (MessageParam msg : messages) {
            if (msg.role().equals(MessageParam.Role.ASSISTANT) && msg.content().isBlockParams()) {
                var contentBlockParams = msg.content().asBlockParams();
                for (ContentBlockParam block : contentBlockParams) {
                    if (block.isToolUse()) {
                        var blockToolUse = block.asToolUse();
                        toolNameMap.put(blockToolUse.id(), blockToolUse.name());
                    }
                }
            }
        }
        // Clear old results (keep last KEEP_RECENT)
        var toClear = toolResults.subList(0, toolResults.size() - KEEP_RECENT);
        for (ToolResult toolResult : toClear) {
            var result = toolResult.part();
            if (result.isText() && result.asText().text().length() > 100) {
                var toolId = result.asToolResult().toolUseId();
                var toolName = toolNameMap.getOrDefault(toolId, "unknown");
                var oldMessageParam = messages.get(toolResult.msgIndex());
                var newContentBlockParams = new ArrayList<ContentBlockParam>();
                var blockParams = oldMessageParam.content().asBlockParams();
                for (int i = 0; i < blockParams.size(); i++) {
                    if (i == toolResult.partIndex()) {
                        var newBlockParam = blockParams.get(i).asToolResult().toBuilder().content("[Previous: used "+ toolName +"]").build();
                        newContentBlockParams.add(ContentBlockParam.ofToolResult(newBlockParam));
                    } else {
                        newContentBlockParams.add(blockParams.get(i));
                    }
                }
                var newMessageParam = oldMessageParam.toBuilder().contentOfBlockParams(newContentBlockParams).build();
                messages.set(toolResult.msgIndex(), newMessageParam);
            }
        }
        return messages;
    }


    /**
     * -- Layer 2: auto_compact - save transcript, summarize, replace messages --
     */
    private static List<MessageParam> autoCompact(List<MessageParam> messages) {
        //Save full transcript to disk
        var mkdirRes = new File(TRANSCRIPT_DIR).mkdirs();
        assert mkdirRes;
        var transcriptPath = TRANSCRIPT_DIR + File.separator + "transcript_" + (System.currentTimeMillis() / 1000) + ".jsonl";
        var mapper = JsonMapper.builder().build();
        try (var writer = new FileWriter(transcriptPath)) {
            for (var msg : messages) {
                writer.write(mapper.writeValueAsString(msg) + "\n");
            }
            writer.flush();
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            IO.println("[transcript saved: "+ transcriptPath + "]");
            //Ask LLM to summarize
            var conversationText = mapper.writeValueAsString(messages);
            conversationText = conversationText.substring(0, Math.min(conversationText.length(), 80000));
            var paramsBuilder = MessageCreateParams.builder()
                    .model(MODEL)
                    .messages(List.of(
                            MessageParam.builder().role(MessageParam.Role.USER)
                                    .content("Summarize this conversation for continuity. Include: " +
                                            "1) What was accomplished, 2) Current state, 3) Key decisions made. " +
                                            "Be concise but preserve critical details.\n\n" + conversationText)
                                    .build()
                    ))
                    .maxTokens(2000);

            var response = CLIENT.messages().create(paramsBuilder.build());
            var summary = response.content().getFirst().asText().text();
            //Replace all messages with compressed summary
            List<MessageParam> result = new ArrayList<>(2);
            result.add(MessageParam.builder().role(MessageParam.Role.USER).content("[Conversation compressed. Transcript:"
                    + transcriptPath +"]\n\n" + summary).build());
            result.add(MessageParam.builder().role(MessageParam.Role.ASSISTANT).content("Understood. I have the context from the summary. Continuing.")
                    .build());
            return result;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return messages;
    }


    public static void main(String[] ignoredArgs) {
        List<MessageParam> history = new ArrayList<>();
        while (true) {
            var query = "";
            try {
                query = IO.readln("\033[36mfull >> \033[0m").strip();
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
            if (query.equalsIgnoreCase("/compact")) {
                IO.println("[manual compact via /compact]");
                history = autoCompact(history);
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
