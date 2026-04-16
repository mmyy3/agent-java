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
import com.limy.util.PathUtils;
import com.limy.util.SkillLoader;
import com.limy.util.ToolWrapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * s05_skill_loading.py - Skills
 * <p>
 * Two-layer skill injection that avoids bloating the system prompt:
 * <p>
 *     Layer 1 (cheap): skill names in system prompt (~100 tokens/skill)
 *     Layer 2 (on demand): full skill body in tool_result
 * <p>
 *     System prompt:
 *     +--------------------------------------+
 *     | You are a coding agent.              |
 *     | Skills available:                    |
 *     |   - git: Git workflow helpers        |  <-- Layer 1: metadata only
 *     |   - test: Testing best practices     |
 *     +--------------------------------------+
 * <p>
 *     When model calls load_skill("git"):
 *     +--------------------------------------+
 *     | tool_result:                         |
 *     | <skill>                              |
 *     |   Full git workflow instructions...  |  <-- Layer 2: full body
 *     |   Step 1: ...                        |
 *     |   Step 2: ...                        |
 *     | </skill>                             |
 *     +--------------------------------------+
 * <p>
 * Key insight: "Don't put everything in the system prompt. Load on demand."
 */
public class S05SkillLoading extends Base {

    private static final SkillLoader SKILL_LOADER = new SkillLoader(SKILLS_DIR);

    // Layer 1: skill metadata injected into system prompt
    private static final String SYSTEM = "You are a coding agent at " + WORKDIR + "." +
" Use loadSkill to access specialized knowledge before tackling unfamiliar topics.\n\nSkills available:\n" + SKILL_LOADER.getDescriptions();

    private static final Map<String, ToolWrapper<?>> TOOL_HANDLERS = Map.of(
            "bash", new ToolWrapper<>(EnhancedBashExecutor::runBash, EnhancedBashExecutor.BashCommand.class),
            "readFile", new ToolWrapper<>(PathUtils::runRead, PathUtils.ReadCommand.class),
            "writeFile", new ToolWrapper<>(PathUtils::runWrite, PathUtils.WriteCommand.class),
            "editFile", new ToolWrapper<>(PathUtils::runEdit, PathUtils.EditCommand.class),
            "loadSkill", new ToolWrapper<>(SKILL_LOADER::getContent, SkillLoader.GetContentParam.class)
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
                query = IO.readln("\033[36ms05 >> \033[0m").strip();
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
