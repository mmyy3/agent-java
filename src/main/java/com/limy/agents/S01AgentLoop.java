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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * s01_agent_loop.py - The Agent Loop
 * <p>
 * The entire secret of an AI coding agent in one pattern:
 * <p>
 *     while stop_reason == "tool_use":
 *         response = LLM(messages, tools)
 *         execute tools
 *         append results
 * <p>
 *     +----------+      +-------+      +---------+
 *     |   User   | ---> |  LLM  | ---> |  Tool   |
 *     |  prompt  |      |       |      | execute |
 *     +----------+      +---+---+      +----+----+
 *                           ^               |
 *                           |   tool_result |
 *                           +---------------+
 *                           (loop continues)
 * <p>
 * This is the core loop: feed tool results back to the model
 * until the model decides to stop. Production agents layer
 * policy, hooks, and lifecycle controls on top.
 */
public class S01AgentLoop extends Base {

    private static final String SYSTEM = "You are a coding agent at " + WORKDIR + ". Use bash to solve tasks. Act, don't explain.";

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
            )
    );

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

            // Execute each tool call, collect results
            for (ContentBlock contentBlock : response.content()) {
                if (contentBlock.isToolUse()) {
                    var toolUseBlock = contentBlock.toolUse().orElseThrow();
                    if (toolUseBlock.name().equals("bash")) {
                        var command = toolUseBlock._input().convert(EnhancedBashExecutor.BashCommand.class);
                        IO.println(String.format("\033[33m$ %s\033[0m", command));
                        assert command != null;
                        var output = EnhancedBashExecutor.runBash(command);
                        IO.println(output.substring(0, Math.min(output.length(), 200)).trim());

                        messages.add(MessageParam.builder().role(MessageParam.Role.USER).content(MessageParam.Content.ofBlockParams(
                                List.of(ContentBlockParam.ofToolResult(
                                        ToolResultBlockParam.builder()
                                                .toolUseId(toolUseBlock.id())
                                                .content(output)
                                                .build()
                                ))
                        )).build());
                        break;
                    }
                }
            }
        }
    }

    public static void main(String[] ignoredArgs) {
        var history = new ArrayList<MessageParam>();
        while (true) {
            var query = "";
            try {
                query = IO.readln("\033[36ms01 >> \033[0m").strip();
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
