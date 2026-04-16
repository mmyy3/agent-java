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
import com.limy.util.EnhancedBashExecutor;
import com.limy.util.EventBus;
import com.limy.util.PathUtils;
import com.limy.util.TaskManager;
import com.limy.util.ToolWrapper;
import com.limy.util.WorktreeManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * s12_worktree_task_isolation.py - Worktree + Task Isolation
 *
 * Directory-level isolation for parallel task execution.
 * Tasks are the control plane and worktrees are the execution plane.
 *
 *     .tasks/task_12.json
 *       {
 *         "id": 12,
 *         "subject": "Implement auth refactor",
 *         "status": "in_progress",
 *         "worktree": "auth-refactor"
 *       }
 *
 *     .worktrees/index.json
 *       {
 *         "worktrees": [
 *           {
 *             "name": "auth-refactor",
 *             "path": ".../.worktrees/auth-refactor",
 *             "branch": "wt/auth-refactor",
 *             "task_id": 12,
 *             "status": "active"
 *           }
 *         ]
 *       }
 *
 * Key insight: "Isolate by directory, coordinate by task ID."
 */
public class S12WorktreeTaskIsolation extends Base {

    private static Path REPO_ROOT = detectRepoRoot(Paths.get(WORKDIR));
    private static String SYSTEM = String.format("""
            You are a coding agent at %s.
            Use task + worktree tools for multi-task work.
            For parallel or risky changes: create tasks, allocate worktree lanes,
            run commands in those lanes, then choose keep/remove for closeout.
            Use worktree_events when you need lifecycle visibility.
            """, WORKDIR);
    private static TaskManager TASKS = new TaskManager(REPO_ROOT.resolve(".tasks"));
    private static EventBus EVENTS = new EventBus(REPO_ROOT.resolve(".worktrees", "events.jsonl"));
    private static WorktreeManager WORKTREES = new WorktreeManager(REPO_ROOT, TASKS, EVENTS);
    private static final Map<String, ToolWrapper<?>> TOOL_HANDLERS = new HashMap<>();
    private static final List<ToolUnion> TOOLS = new ArrayList<>();

    static {
        TOOL_HANDLERS.put("bash", new ToolWrapper<>(EnhancedBashExecutor::runBash, EnhancedBashExecutor.BashCommand.class));
        TOOL_HANDLERS.put("readFile", new ToolWrapper<>(PathUtils::runRead, PathUtils.ReadCommand.class));
        TOOL_HANDLERS.put("writeFile", new ToolWrapper<>(PathUtils::runWrite, PathUtils.WriteCommand.class));
        TOOL_HANDLERS.put("editFile", new ToolWrapper<>(PathUtils::runEdit, PathUtils.EditCommand.class));
        TOOL_HANDLERS.put("taskCreate", new ToolWrapper<>(TASKS::create, TaskManager.CreateTaskCommand.class));
        TOOL_HANDLERS.put("taskUpdate", new ToolWrapper<>(TASKS::update, TaskManager.UpdateTaskCommand.class));
        TOOL_HANDLERS.put("taskList", new ToolWrapper<>(_ -> TASKS.listAll(), Object.class));
        TOOL_HANDLERS.put("taskGet", new ToolWrapper<>(TASKS::get, TaskManager.GetTaskCommand.class));
        TOOL_HANDLERS.put("taskBindWorktree", new ToolWrapper<>(TASKS::bindWorktree, TaskManager.BindWorktreeCommand.class));
        TOOL_HANDLERS.put("worktreeCreate", new ToolWrapper<>(WORKTREES::create, WorktreeManager.CreateCommand.class));
        TOOL_HANDLERS.put("worktreeList", new ToolWrapper<>(o -> WORKTREES.listAll(), Object.class));
        TOOL_HANDLERS.put("worktreeStatus", new ToolWrapper<>(WORKTREES::status, WorktreeManager.StatusCommand.class));
        TOOL_HANDLERS.put("worktreeRun", new ToolWrapper<>(WORKTREES::run, WorktreeManager.RunCommand.class));
        TOOL_HANDLERS.put("worktreeKeep", new ToolWrapper<>(WORKTREES::keep, WorktreeManager.KeepCommand.class));
        TOOL_HANDLERS.put("worktreeRemove", new ToolWrapper<>(WORKTREES::remove, WorktreeManager.RemoveCommand.class));
        TOOL_HANDLERS.put("worktreeEvents", new ToolWrapper<>(EVENTS::listRecent, EventBus.ListRecentCommand.class));


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
                Tool.builder().name("taskBindWorktree").description("Bind a task to a worktree name.")
                        .inputSchema(
                                Tool.InputSchema.builder().type(JsonValue.from("object"))
                                        .properties(
                                                Tool.InputSchema.Properties.builder()
                                                        .putAdditionalProperty("taskId", JsonValue.from(Map.of("type", "integer")))
                                                        .putAdditionalProperty("worktree", JsonValue.from(Map.of("type", "string")))
                                                        .putAdditionalProperty("owner", JsonValue.from(Map.of("type", "string")))
                                                        .build()
                                        )
                                        .required(List.of("taskId", "worktree"))
                                        .build()
                        ).build()
        ));

        TOOLS.add(ToolUnion.ofTool(
                Tool.builder().name("worktreeCreate").description("Create a git worktree and optionally bind it to a task.")
                        .inputSchema(
                                Tool.InputSchema.builder().type(JsonValue.from("object"))
                                        .properties(
                                                Tool.InputSchema.Properties.builder()
                                                        .putAdditionalProperty("name", JsonValue.from(Map.of("type", "string")))
                                                        .putAdditionalProperty("taskId", JsonValue.from(Map.of("type", "integer")))
                                                        .putAdditionalProperty("baseRef", JsonValue.from(Map.of("type", "string")))
                                                        .build()
                                        )
                                        .required(List.of("name"))
                                        .build()
                        ).build()
        ));

        TOOLS.add(ToolUnion.ofTool(
                Tool.builder().name("worktreeList").description("List worktrees tracked in .worktrees/index.json.")
                        .inputSchema(
                                Tool.InputSchema.builder().type(JsonValue.from("object"))
                                        .build()
                        ).build()
        ));

        TOOLS.add(ToolUnion.ofTool(
                Tool.builder().name("worktreeStatus").description("Show git status for one worktree.")
                        .inputSchema(
                                Tool.InputSchema.builder().type(JsonValue.from("object"))
                                        .properties(
                                                Tool.InputSchema.Properties.builder()
                                                        .putAdditionalProperty("name", JsonValue.from(Map.of("type", "string")))
                                                        .build()
                                        )
                                        .required(List.of("name"))
                                        .build()
                        ).build()
        ));


        TOOLS.add(ToolUnion.ofTool(
                Tool.builder().name("worktreeRun").description("Run a shell command in a named worktree directory.")
                        .inputSchema(
                                Tool.InputSchema.builder().type(JsonValue.from("object"))
                                        .properties(
                                                Tool.InputSchema.Properties.builder()
                                                        .putAdditionalProperty("name", JsonValue.from(Map.of("type", "string")))
                                                        .putAdditionalProperty("command", JsonValue.from(Map.of("type", "string")))
                                                        .build()
                                        )
                                        .required(List.of("name", "command"))
                                        .build()
                        ).build()
        ));

        TOOLS.add(ToolUnion.ofTool(
                Tool.builder().name("worktreeRemove").description("Remove a worktree and optionally mark its bound task completed.")
                        .inputSchema(
                                Tool.InputSchema.builder().type(JsonValue.from("object"))
                                        .properties(
                                                Tool.InputSchema.Properties.builder()
                                                        .putAdditionalProperty("name", JsonValue.from(Map.of("type", "string")))
                                                        .putAdditionalProperty("force", JsonValue.from(Map.of("type", "boolean")))
                                                        .putAdditionalProperty("completeTask", JsonValue.from(Map.of("type", "boolean")))
                                                        .build()
                                        )
                                        .required(List.of("name"))
                                        .build()
                        ).build()
        ));

        TOOLS.add(ToolUnion.ofTool(
                Tool.builder().name("worktreeKeep").description("Mark a worktree as kept in lifecycle state without removing it.")
                        .inputSchema(
                                Tool.InputSchema.builder().type(JsonValue.from("object"))
                                        .properties(
                                                Tool.InputSchema.Properties.builder()
                                                        .putAdditionalProperty("name", JsonValue.from(Map.of("type", "string")))
                                                        .build()
                                        )
                                        .required(List.of("name"))
                                        .build()
                        ).build()
        ));

        TOOLS.add(ToolUnion.ofTool(
                Tool.builder().name("worktreeEvents").description("List recent worktree/task lifecycle events from .worktrees/events.jsonl.")
                        .inputSchema(
                                Tool.InputSchema.builder().type(JsonValue.from("object"))
                                        .properties(
                                                Tool.InputSchema.Properties.builder()
                                                        .putAdditionalProperty("limit", JsonValue.from(Map.of("type", "integer")))
                                                        .build()
                                        )
                                        .build()
                        ).build()
        ));
    }

    private static void agentLoop(List<MessageParam> messages) {
        while (true) {
            var paramsBuilder = MessageCreateParams.builder()
                    .model(MODEL).system(SYSTEM)
                    .messages(messages)
                    .tools(TOOLS).maxTokens(8000);

            var response = CLIENT.messages().create(paramsBuilder.build());

            addAssistants(messages, response);

            // If the model didn't call a tool, we're done
            if (!response.stopReason().orElse(StopReason.END_TURN).equals(StopReason.TOOL_USE)) {
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
                        output = handler.executeCommand(toolUseBlock._input());
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
        }
    }

    private static Path detectRepoRoot(Path cwd) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "--show-toplevel");
            pb.directory(cwd.toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();

            // Wait for process completion with timeout (10 seconds)
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return cwd;
            }

            // Check exit code
            if (process.exitValue() != 0) {
                return cwd;
            }

            // Read output
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line);
                }
            }

            // Parse and validate path
            String repoRoot = output.toString().trim();
            if (repoRoot.isEmpty()) {
                return cwd;
            }

            Path rootPath = Paths.get(repoRoot);
            return Files.exists(rootPath) ? rootPath : cwd;

        } catch (IOException | InterruptedException e) {
            return cwd;
        }
    }


    public static void main(String[] ignoredArgs) {
        IO.println(String.format("Repo root for s12: %s", REPO_ROOT));
        if (!WORKTREES.isGitAvailable()) {
            IO.println("Note: Not in a git repo. worktree_* tools will return errors.");
        }
        var history = new ArrayList<MessageParam>();
        while (true) {
            var query = "";
            try {
                query = IO.readln("\033[36ms12 >> \033[0m").strip();
            } catch (Exception e) {
                break;
            }
            if (query.equalsIgnoreCase("q") || query.equalsIgnoreCase("exit") || query.equalsIgnoreCase("")) {
                break;
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
