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
import com.limy.util.BackgroundManager;
import com.limy.util.EnhancedBashExecutor;
import com.limy.util.PathUtils;
import com.limy.util.ToolWrapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * s08_background_tasks.py - Background Tasks
 * <p>
 * Run commands in background threads. A notification queue is drained
 * before each LLM call to deliver results.
 * <p>
 *     Main thread                Background thread
 *     +-----------------+        +-----------------+
 *     | agent loop      |        | task executes   |
 *     | ...             |        | ...             |
 *     | [LLM call] <---+------- | enqueue(result) |
 *     |  ^drain queue   |        +-----------------+
 *     +-----------------+
 * <p>
 *     Timeline:
 *     Agent ----[spawn A]----[spawn B]----[other work]----
 *                  |              |
 *                  v              v
 *               [A runs]      [B runs]        (parallel)
 *                  |              |
 *                  +-- notification queue --> [results injected]
 * <p>
 * Key insight: "Fire and forget -- the agent doesn't block while the command runs."
 */
public class S08BackgroundTasks extends Base {

    private static final String SYSTEM = "You are a coding agent at "+ WORKDIR +". Use background_run for long-running commands.";

    private static final BackgroundManager BG;

    static {
        BG = new BackgroundManager(WORKDIR);
        Runtime.getRuntime().addShutdownHook(new Thread(BG::shutdown));
    }

    private static final Map<String, ToolWrapper<?>> TOOL_HANDLERS = Map.of(
            "bash", new ToolWrapper<>(EnhancedBashExecutor::runBash, EnhancedBashExecutor.BashCommand.class),
            "readFile", new ToolWrapper<>(PathUtils::runRead, PathUtils.ReadCommand.class),
            "writeFile", new ToolWrapper<>(PathUtils::runWrite, PathUtils.WriteCommand.class),
            "editFile", new ToolWrapper<>(PathUtils::runEdit, PathUtils.EditCommand.class),
            "checkBackground", new ToolWrapper<>(BG::check, BackgroundManager.CheckBackgroundTaskCommand.class),
            "backgroundRun", new ToolWrapper<>(BG::run, BackgroundManager.RunBackgroundTaskCommand.class)
    );

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
            ),
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

    private static void agentLoop(List<MessageParam> messages) {
        while (true) {
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

    public static void main(String[] ignoredArgs) {
        var history = new ArrayList<MessageParam>();
        while (true) {
            var query = "";
            try {
                query = IO.readln("\033[36ms08 >> \033[0m").strip();
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
