
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
import com.limy.util.EnhancedBashExecutor;
import com.limy.util.PathUtils;
import com.limy.util.TodoManager;
import com.limy.util.ToolWrapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * s03_todo_write.py - TodoWrite
 * <p>
 * The model tracks its own progress via a TodoManager. A nag reminder
 * forces it to keep updating when it forgets.
 * <p>
 *     +----------+      +-------+      +---------+
 *     |   User   | ---> |  LLM  | ---> | Tools   |
 *     |  prompt  |      |       |      | + todo  |
 *     +----------+      +---+---+      +----+----+
 *                           ^               |
 *                           |   tool_result |
 *                           +---------------+
 *                                 |
 *                     +-----------+-----------+
 *                     | TodoManager state     |
 *                     | [ ] task A            |
 *                     | [>] task B <- doing   |
 *                     | [x] task C            |
 *                     +-----------------------+
 *                                 |
 *                     if rounds_since_todo >= 3:
 *                       inject <reminder>
 * <p>
 * Key insight: "The agent can track its own progress -- and I can see it."
 */
public class S03ToDoWrite extends Base {

    private static final String SYSTEM = """
            You are a coding agent at\s"""
            + WORKDIR +
            """
            .
            Use the todo tool to plan multi-step tasks. Mark in_progress before starting, completed when done.
            Prefer tools over prose.""";

    private static final TodoManager TODO = new TodoManager();

    private static final Map<String, ToolWrapper<?>> TOOL_HANDLERS = Map.of(
            "bash", new ToolWrapper<>(EnhancedBashExecutor::runBash, EnhancedBashExecutor.BashCommand.class),
            "readFile", new ToolWrapper<>(PathUtils::runRead, PathUtils.ReadCommand.class),
            "writeFile", new ToolWrapper<>(PathUtils::runWrite, PathUtils.WriteCommand.class),
            "editFile", new ToolWrapper<>(PathUtils::runEdit, PathUtils.EditCommand.class),
            "todo", new ToolWrapper<>(TODO::update, TodoManager.TodoUpdateCommand.class)
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
            )
    );

    private static void agentLoop(List<MessageParam> messages) {
        var roundsSinceTodo = 0;
        while (true) {
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
            var usedTodo = false;
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
                        if (toolUseBlock.name().equalsIgnoreCase("todo")) {
                            usedTodo = true;
                        }
                        roundsSinceTodo = usedTodo ? 0 : roundsSinceTodo + 1;
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
                query = IO.readln("\033[36ms03 >> \033[0m").strip();
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
