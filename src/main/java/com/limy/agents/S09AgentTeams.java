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
import com.limy.util.TeammateManagerS09;
import com.limy.util.TeammateToolWrapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * s09_agent_teams.py - Agent Teams
 * <p>
 * Persistent named agents with file-based JSONL inboxes. Each teammate runs
 * its own agent loop in a separate thread. Communication via append-only inboxes.
 * <p>
 * Subagent (s04):  spawn -> execute -> return summary -> destroyed
 * Teammate (s09):  spawn -> work -> idle -> work -> ... -> shutdown
 * <p>
 * .team/config.json                   .team/inbox/
 * +----------------------------+      +------------------+
 * | {"team_name": "default",   |      | alice.jsonl      |
 * |  "members": [              |      | bob.jsonl        |
 * |    {"name":"alice",        |      | lead.jsonl       |
 * |     "role":"coder",        |      +------------------+
 * |     "status":"idle"}       |
 * |  ]}                        |      send_message("alice", "fix bug"):
 * +----------------------------+        open("alice.jsonl", "a").write(msg)
 * <p>
 * read_inbox("alice"):
 * spawn_teammate("alice","coder",...)   msgs = [json.loads(l) for l in ...]
 * |                                open("alice.jsonl", "w").close()
 * v                                return msgs  # drain
 * Thread: alice             Thread: bob
 * +------------------+      +------------------+
 * | agent_loop       |      | agent_loop       |
 * | status: working  |      | status: idle     |
 * | ... runs tools   |      | ... waits ...    |
 * | status -> idle   |      |                  |
 * +------------------+      +------------------+
 * <p>
 * 5 message types (all declared, not all handled here):
 * +-------------------------+-----------------------------------+
 * | message                 | Normal text message               |
 * | broadcast               | Sent to all teammates             |
 * | shutdown_request        | Request graceful shutdown (s10)   |
 * | shutdown_response       | Approve/reject shutdown (s10)     |
 * | plan_approval_response  | Approve/reject plan (s10)         |
 * +-------------------------+-----------------------------------+
 * <p>
 * Key insight: "Teammates that can talk to each other."
 */
public class S09AgentTeams extends Base {

    private static final Path TEAM_DIR = Path.of(WORKDIR, ".team");
    private static final Path INBOX_DIR = TEAM_DIR.resolve("inbox");
    private static final String SYSTEM = "You are a team lead at " + WORKDIR + ". Spawn teammates and communicate via inboxes.";
    private static final ObjectMapper OBJECT_MAPPER;
    private static final MessageBus BUS = new MessageBus(INBOX_DIR);
    private static final TeammateManagerS09 TEAM = new TeammateManagerS09(TEAM_DIR, BUS);

    static {
        OBJECT_MAPPER = new ObjectMapper();
        OBJECT_MAPPER.enable(SerializationFeature.INDENT_OUTPUT);
    }


    private static final Map<String, TeammateToolWrapper<?>> TOOL_HANDLERS = Map.of(
            "bash", new TeammateToolWrapper<>(EnhancedBashExecutor::runBash, EnhancedBashExecutor.BashCommand.class, TEAM),
            "readFile", new TeammateToolWrapper<>(PathUtils::runRead, PathUtils.ReadCommand.class, TEAM),
            "writeFile", new TeammateToolWrapper<>(PathUtils::runWrite, PathUtils.WriteCommand.class, TEAM),
            "editFile", new TeammateToolWrapper<>(PathUtils::runEdit, PathUtils.EditCommand.class, TEAM),
            "sendMessage", new TeammateToolWrapper<>(BUS::send, MessageBus.SendCommand.class, TEAM),
            "readInbox", new TeammateToolWrapper<>(arg -> {
                try {
                    return OBJECT_MAPPER.writeValueAsString(BUS.readInbox(arg));
                } catch (JsonProcessingException e) {
                    return "{}";
                }
            }, MessageBus.ReadInboxCommand.class, TEAM),
            "broadcast", new TeammateToolWrapper<>(BUS::broadcast, MessageBus.BroadcastCommand.class, TEAM),
            "spawnTeammate", new TeammateToolWrapper<>(TEAM::spawn, TeammateManagerS09.SpawnCommand.class, TEAM),
            "listTeammates", new TeammateToolWrapper<>(_ -> TEAM.listAll(), Object.class, TEAM)
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
                                            .required(List.of("to", "content","msgType"))
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
                    Tool.builder().name("broadcast").description("Send a message to all teammates.")
                            .inputSchema(
                                    Tool.InputSchema.builder().type(JsonValue.from("object"))
                                            .putAdditionalProperty("content", JsonValue.from(Map.of("type", "string")))
                                            .required(List.of("content"))
                                            .build()
                            ).build()
            ),
            ToolUnion.ofTool(
                    Tool.builder().name("spawnTeammate").description("Spawn a persistent teammate that runs in its own thread.")
                            .inputSchema(
                                    Tool.InputSchema.builder().type(JsonValue.from("object"))
                                            .putAdditionalProperty("name", JsonValue.from(Map.of("type", "string")))
                                            .putAdditionalProperty("role", JsonValue.from(Map.of("type", "string")))
                                            .putAdditionalProperty("prompt", JsonValue.from(Map.of("type", "string")))
                                            .required(List.of("name", "role", "prompt"))
                                            .build()
                            ).build()
            ),
            ToolUnion.ofTool(
                    Tool.builder().name("listTeammates").description("List all teammates with name, role, status.")
                            .inputSchema(
                                    Tool.InputSchema.builder().type(JsonValue.from("object"))
                                            .build()
                            ).build()
            )
    );


    private static void agentLoop(List<MessageParam> messages) {
        while (true) {
            var inbox = BUS.readInbox(new MessageBus.ReadInboxCommand("lead"));
            if (null != inbox && !inbox.isEmpty()) {
                try {
                    messages.addLast(MessageParam.builder().role(MessageParam.Role.USER).content(String.format("<inbox>"+ OBJECT_MAPPER.writeValueAsString(inbox) +"</inbox>")).build());
                    messages.addLast(MessageParam.builder().role(MessageParam.Role.ASSISTANT).content("Noted inbox messages.").build());
                } catch (JsonProcessingException e) {
                    throw new RuntimeException(e);
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

    public static void main(String[] args) {
        var history = new ArrayList<MessageParam>();
        while (true) {
            var query = "";
            try {
                query = IO.readln("\033[36ms09 >> \033[0m").strip();
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
