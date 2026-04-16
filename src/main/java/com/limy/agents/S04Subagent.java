package com.limy.agents;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.limy.util.EnhancedBashExecutor;
import com.limy.util.PathUtils;
import com.limy.util.ToolWrapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * s04_subagent.py - Subagents
 * <p>
 * Spawn a child agent with fresh messages=[]. The child works in its own
 * context, sharing the filesystem, then returns only a summary to the parent.
 * <p>
 *     Parent agent                     Subagent
 *     +------------------+             +------------------+
 *     | messages=[...]   |             | messages=[]      |  <-- fresh
 *     |                  |  dispatch   |                  |
 *     | tool: task       | ---------->| while tool_use:  |
 *     |   prompt="..."   |            |   call tools     |
 *     |   description="" |            |   append results |
 *     |                  |  summary   |                  |
 *     |   result = "..." | <--------- | return last text |
 *     +------------------+             +------------------+
 *               |
 *     Parent context stays clean.
 *     Subagent context is discarded.
 * <p>
 * Key insight: "Process isolation gives context isolation for free."
 */
public class S04Subagent extends Base {

    private static final String SYSTEM = "You are a coding agent at " + WORKDIR + ". Use the task tool to delegate exploration or subtasks.";
    private static final String SUBAGENT_SYSTEM = "You are a coding subagent at " + WORKDIR + ". Complete the given task, then summarize your findings.";
    private static final Map<String, ToolWrapper<?>> TOOL_HANDLERS = Map.of(
            "bash", new ToolWrapper<>(EnhancedBashExecutor::runBash, EnhancedBashExecutor.BashCommand.class),
            "readFile", new ToolWrapper<>(PathUtils::runRead, PathUtils.ReadCommand.class),
            "writeFile", new ToolWrapper<>(PathUtils::runWrite, PathUtils.WriteCommand.class),
            "editFile", new ToolWrapper<>(PathUtils::runEdit, PathUtils.EditCommand.class)
    );
    // Child gets all base tools except task (no recursive spawning)
    private static final List<ToolUnion> CHILD_TOOLS = List.of(
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
            )
    );

    // -- Parent tools: base tools + task dispatcher --
    private static final List<ToolUnion> PARENT_TOOLS =new ArrayList<>(CHILD_TOOLS);
    static {
        PARENT_TOOLS.addLast(ToolUnion.ofTool(
                Tool.builder().name("task").description("Spawn a subagent with fresh context. It shares the filesystem but not conversation history.")
                        .inputSchema(
                                Tool.InputSchema.builder().type(JsonValue.from("object"))
                                        .properties(
                                                Tool.InputSchema.Properties.builder()
                                                        .putAdditionalProperty("prompt", JsonValue.from(Map.of("type", "string")))
                                                        .putAdditionalProperty("description", JsonValue.from(Map.of("type", "string", "description", "Short description of the task")))
                                                        .build()
                                        )
                                        .required(Collections.singletonList("prompt"))
                                        .build()
                        ).build()
        ));
    }

    /**
     * -- Subagent: fresh context, filtered tools, summary-only return --
     */
    private static String runSubagent(String prompt) {
        // 创建新的消息列表（fresh context）
        List<MessageParam> subMessages = new ArrayList<>();
        subMessages.add(MessageParam.builder().role(MessageParam.Role.USER).content(prompt).build());

        Message response = null;
        // 安全限制：最多30次循环
        for (int i = 0; i < 30; i++) {
            try {
                // 创建消息请求
                MessageCreateParams params = MessageCreateParams.builder()
                        .model(MODEL).system(SUBAGENT_SYSTEM).messages(subMessages)
                        .maxTokens(8000L).tools(CHILD_TOOLS).build();

                // 发送请求并获取响应
                response = CLIENT.messages().create(params);

                addAssistants(subMessages, response);

                // If the model didn't call a tool, we're done
                if (!response.stopReason().orElse(StopReason.END_TURN).equals(StopReason.TOOL_USE)) {
                    break;
                }

                // 处理工具调用结果
                List<ContentBlockParam> toolResults = new ArrayList<>();
                
                for (ContentBlock block : response.content()) {
                    if (block.isToolUse()) {
                        var toolUseBlock = block.toolUse().orElseThrow();
                        var handler = TOOL_HANDLERS.get(toolUseBlock.name());
                        var output = "";
                        if (null == handler) {
                            output = "Unknown tool: " + toolUseBlock.name();
                        } else {
                            output = handler.executeCommand(toolUseBlock._input());
                            IO.println(String.format(">[subagent] %s: %s}", toolUseBlock.name(), output.substring(0, Math.min(output.length(), 200)).trim()));
                        }
                        toolResults.add(
                                ContentBlockParam.ofToolResult(
                                        ToolResultBlockParam.builder()
                                                .toolUseId(toolUseBlock.id())
                                                .content(output)
                                                .build()
                                )
                        );
                    }
                }

                // 将工具结果添加到消息历史中
                if (!toolResults.isEmpty()) {
                    subMessages.add(
                            MessageParam.builder()
                                    .role(MessageParam.Role.USER)
                                    .content(MessageParam.Content.ofBlockParams(toolResults))
                                    .build()
                    );
                }

            } catch (Exception e) {
                // 处理异常情况
                throw new RuntimeException("Error in subagent execution: " + e.getMessage(), e);
            }
        }

        // 提取并返回最终的文本内容
        StringBuilder result = new StringBuilder();
        for (ContentBlock block : response.content()) {
            if (block.isText()) {
                result.append(block.asText().text());
            }
        }
        return !result.isEmpty() ? result.toString() : "(no summary)";
    }

    private static void agentLoop(List<MessageParam> messages) {
        while (true) {
            var paramsBuilder = MessageCreateParams.builder()
                    .model(MODEL).system(SYSTEM)
                    .messages(messages)
                    .tools(PARENT_TOOLS).maxTokens(8000);

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
                       if (toolUseBlock.name().equals("task")) {
                           Map<String, String> taskParamMap = toolUseBlock._input().convert(new TypeReference<>() {
                           });
                           assert taskParamMap != null;
                           var desc = taskParamMap.getOrDefault("description", "subtask");
                           var prompt = taskParamMap.get("prompt");
                           var input = "task (" + desc + "): " + prompt.substring(0, Math.min(prompt.length(), 80));
                           output = runSubagent(input);
                       } else {
                           output = "Unknown tool: " + toolUseBlock.name();
                       }
                    } else {
                        output = handler.executeCommand(toolUseBlock._input());
                    }
                    IO.println(String.format(">[agent] %s: %s}", toolUseBlock.name(), output.substring(0, Math.min(output.length(), 200)).trim()));
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
                query = IO.readln("\033[36ms04 >> \033[0m").strip();
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
