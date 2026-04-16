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
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.limy.util.EnhancedBashExecutor;
import com.limy.util.PathUtils;
import com.limy.util.ToolWrapper;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * s06_context_compact.py - Compact
 * <p>
 * Three-layer compression pipeline so the agent can work forever:
 * <p>
 *     Every turn:
 *     +------------------+
 *     | Tool call result |
 *     +------------------+
 *             |
 *             v
 *     [Layer 1: micro_compact]        (silent, every turn)
 *       Replace tool_result content older than last 3
 *       with "[Previous: used {tool_name}]"
 *             |
 *             v
 *     [Check: tokens > 50000?]
 *        |               |
 *        no              yes
 *        |               |
 *        v               v
 *     continue    [Layer 2: auto_compact]
 *                   Save full transcript to .transcripts/
 *                   Ask LLM to summarize conversation.
 *                   Replace all messages with [summary].
 *                         |
 *                         v
 *                 [Layer 3: compact tool]
 *                   Model calls compact -> immediate summarization.
 *                   Same as auto, triggered manually.
 * <p>
 * Key insight: "The agent can forget strategically and keep working forever."
 */
public class S06ContextCompact extends Base {

    private static final String SYSTEM = "You are a coding agent at " + WORKDIR + ". Use tools to solve tasks.";
    private static final String TRANSCRIPT_DIR = WORKDIR + File.separator + ".transcripts";

    private static final int THRESHOLD = 50000;
    private static final int KEEP_RECENT = 3;

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


    private static final Map<String, ToolWrapper<?>> TOOL_HANDLERS = Map.of(
            "bash", new ToolWrapper<>(EnhancedBashExecutor::runBash, EnhancedBashExecutor.BashCommand.class),
            "readFile", new ToolWrapper<>(PathUtils::runRead, PathUtils.ReadCommand.class),
            "writeFile", new ToolWrapper<>(PathUtils::runWrite, PathUtils.WriteCommand.class),
            "editFile", new ToolWrapper<>(PathUtils::runEdit, PathUtils.EditCommand.class),
            "compact", new ToolWrapper<>(_ -> "Manual compression requested.", CompactCommand.class)
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

    private static void agentLoop(List<MessageParam> messages) {
        while (true) {
            //Layer 1: micro_compact before each LLM call
            messages = microCompact(messages);

            //Layer 2: auto_compact if token estimate exceeds threshold
            if (estimateTokens(messages) > THRESHOLD) {
                IO.println("[auto_compact triggered]");
                messages = autoCompact(messages);
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
            boolean manualCompact = false;
            // Execute each tool call, collect results
            for (ContentBlock contentBlock : response.content()) {
                if (contentBlock.isToolUse()) {
                    var toolUseBlock = contentBlock.toolUse().orElseThrow();
                    var handler = TOOL_HANDLERS.get(toolUseBlock.name());
                    var output = "";
                    if (null == handler) {
                        output = "Unknown tool: " + toolUseBlock.name();
                    } else {
                        if (toolUseBlock.name().equals("compact")) {
                            manualCompact = true;
                        }
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

            //Layer 3: manual compact triggered by the compact tool
            if (manualCompact) {
                IO.println("[manual compact]");
                messages = autoCompact(messages);
            }
        }
    }

    public static void main(String[] ignoredArgs) {
        var history = new ArrayList<MessageParam>();
        while (true) {
            var query = "";
            try {
                query = IO.readln("\033[36ms06 >> \033[0m").strip();
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
