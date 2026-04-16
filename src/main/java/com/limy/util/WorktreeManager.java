package com.limy.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class WorktreeManager {
    private final Path repoRoot;
    private final TaskManager tasks;
    private final EventBus events;
    private final Path dir;
    private final Path indexPath;
    private final boolean gitAvailable;
    private final ObjectMapper objectMapper;
    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,40}");
    public static final List<String> dangerousCommands = Arrays.asList("rm -rf /", "sudo", "shutdown", "reboot", "> /dev/");

    public WorktreeManager(Path repoRoot, TaskManager tasks, EventBus events) {
        try {
            this.repoRoot = repoRoot;
            this.tasks = tasks;
            this.events = events;
            this.dir = repoRoot.resolve(".worktrees");
            Files.createDirectories(dir);
            this.indexPath = dir.resolve("index.json");
            this.objectMapper = new ObjectMapper();

            if (!Files.exists(indexPath)) {
                Map<String, Object> initialIndex = new HashMap<>();
                initialIndex.put("worktrees", new ArrayList<>());
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(indexPath.toFile(), initialIndex);
            }

            this.gitAvailable = isGitRepo();
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize WorktreeManager", e);
        }
    }

    public boolean isGitAvailable() {
        return gitAvailable;
    }

    private boolean isGitRepo() {
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "--is-inside-work-tree")
                    .directory(repoRoot.toFile())
                    .redirectErrorStream(true)
                    .start();

            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            return finished && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private String runGit(List<String> args) throws IOException, InterruptedException {
        if (!gitAvailable) {
            throw new RuntimeException("Not in a git repository. worktree tools require git.");
        }

        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(args);

        Process process = new ProcessBuilder(command)
                .directory(repoRoot.toFile())
                .redirectErrorStream(true)
                .start();

        boolean finished = process.waitFor(120, TimeUnit.SECONDS);
        var output = new String(process.getInputStream().readAllBytes()).trim();

        if (!finished || process.exitValue() != 0) {
            String errorMsg = output.isEmpty() ? "git " + String.join(" ", args) + " failed" : output;
            throw new RuntimeException(errorMsg);
        }

        return output.isEmpty() ? "(no output)" : output;
    }

    private Map<String, List<Map<String, Object>>> loadIndex() throws IOException {
        return objectMapper.readValue(indexPath.toFile(), new TypeReference<>() {
        });
    }

    private void saveIndex(Map<String, List<Map<String, Object>>> data) throws IOException {
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(indexPath.toFile(), data);
    }

    private Map<String, Object> find(String name) throws IOException {
        Map<String, List<Map<String, Object>>> index = loadIndex();
        List<Map<String, Object>> worktrees = index.getOrDefault("worktrees", new ArrayList<>());

        for (Map<String, Object> wt : worktrees) {
            if (name.equals(wt.get("name"))) {
                return wt;
            }
        }
        return null;
    }

    private void validateName(String name) {
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid worktree name. Use 1-40 chars: letters, numbers, ., _, -");
        }
    }

    public record CreateCommand(String name, Integer taskId, String baseRef) {
    }

    public String create(CreateCommand command) {
        var baseRef = command.baseRef() == null ? "HEAD" : command.baseRef();
        var name = command.name();
        var taskId = command.taskId();
        Map<String, Object> taskPayload = Collections.emptyMap();
        try {
            validateName(name);

            if (find(name) != null) {
                throw new IllegalArgumentException("Worktree '" + name + "' already exists in index");
            }

            if (taskId != null) {
                var task = tasks.get(new TaskManager.GetTaskCommand(taskId));
                if (null == task) {
                    throw new IllegalArgumentException("Task " + taskId + " not found");
                }
            }

            Path path = dir.resolve(name);
            String branch = "wt/" + name;

            taskPayload = taskId != null ?
                    Collections.singletonMap("id", taskId) : Collections.emptyMap();

            Map<String, Object> worktreePayload = new HashMap<>();
            worktreePayload.put("name", name);
            worktreePayload.put("base_ref", baseRef);
            events.emit(new EventBus.EmitCommand("worktree.create.before", taskPayload, worktreePayload, null));
            runGit(Arrays.asList("worktree", "add", "-b", branch, path.toString(), baseRef));

            Map<String, Object> entry = new HashMap<>();
            entry.put("name", name);
            entry.put("path", path.toString());
            entry.put("branch", branch);
            entry.put("task_id", taskId);
            entry.put("status", "active");
            entry.put("created_at", System.currentTimeMillis() / 1000.0);

            Map<String, List<Map<String, Object>>> index = loadIndex();
            List<Map<String, Object>> worktrees = index.getOrDefault("worktrees", new ArrayList<>());
            worktrees.add(entry);
            saveIndex(index);

            if (taskId != null) {
                tasks.bindWorktree(new TaskManager.BindWorktreeCommand(taskId, name, null));
            }

            Map<String, Object> afterWorktreePayload = new HashMap<>();
            afterWorktreePayload.put("name", name);
            afterWorktreePayload.put("path", path.toString());
            afterWorktreePayload.put("branch", branch);
            afterWorktreePayload.put("status", "active");

            events.emit(new EventBus.EmitCommand("worktree.create.after", taskPayload, afterWorktreePayload, null));

            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(entry);
        } catch (Exception e) {
            Map<String, Object> errorPayload = new HashMap<>();
            errorPayload.put("name", name);
            errorPayload.put("base_ref", baseRef);
            errorPayload.put("error", e.getMessage());
            events.emit(new EventBus.EmitCommand("worktree.create.failed", taskPayload, null, e.getMessage()));
            throw new RuntimeException(e);
        }
    }

    public String listAll() {
        try {
            Map<String, List<Map<String, Object>>> index = loadIndex();
            List<Map<String, Object>> worktrees = index.getOrDefault("worktrees", new ArrayList<>());

            if (worktrees.isEmpty()) {
                return "No worktrees in index.";
            }

            List<String> lines = new ArrayList<>();
            for (Map<String, Object> wt : worktrees) {
                String suffix = wt.containsKey("task_id") ? " task=" + wt.get("task_id") : "";
                String status = (String) wt.getOrDefault("status", "unknown");
                String name = (String) wt.get("name");
                String path = (String) wt.get("path");
                String branch = (String) wt.getOrDefault("branch", "-");

                lines.add(String.format("[%s] %s -> %s (%s)%s",
                        status, name, path, branch, suffix));
            }

            return String.join("\n", lines);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public record StatusCommand(String name) {
    }

    public String status(StatusCommand command) {
        try {
            var name = command.name();
            Map<String, Object> wt = find(name);
            if (wt == null) {
                return "Error: Unknown worktree '" + name + "'";
            }

            Path path = Paths.get((String) wt.get("path"));
            if (!Files.exists(path)) {
                return "Error: Worktree path missing: " + path;
            }

            Process process = new ProcessBuilder("git", "status", "--short", "--branch")
                    .directory(path.toFile())
                    .redirectErrorStream(true)
                    .start();

            process.waitFor(60, TimeUnit.SECONDS);
            String output = new String(process.getInputStream().readAllBytes()).trim();

            return output.isEmpty() ? "Clean worktree" : output;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public record RunCommand(String name, String command) {
    }


    public String run(RunCommand runCommand) {
        var name = runCommand.name();
        var command = runCommand.command();
        try {
            for (String dangerous : dangerousCommands) {
                if (command.contains(dangerous)) {
                    return "Error: Dangerous command blocked";
                }
            }

            Map<String, Object> wt = find(name);
            if (wt == null) {
                return "Error: Unknown worktree '" + name + "'";
            }

            Path path = Paths.get((String) wt.get("path"));
            if (!Files.exists(path)) {
                return "Error: Worktree path missing: " + path;
            }

            Process process = new ProcessBuilder("sh", "-c", command)
                    .directory(path.toFile())
                    .redirectErrorStream(true)
                    .start();

            boolean finished = process.waitFor(300, TimeUnit.SECONDS);
            if (!finished) {
                return "Error: Timeout (300s)";
            }
            String output = new String(process.getInputStream().readAllBytes()).trim();

            return output.isEmpty() ? "(no output)" :
                    (output.length() > 50000 ? output.substring(0, 50000) : output);
        } catch (Exception e) {
            return "Error: Timeout (300s)";
        }
    }

    public record RemoveCommand(String name, boolean force, boolean completeTask) {
    }

    public String remove(RemoveCommand removeCommand) {
        var name = removeCommand.name();
        var force = removeCommand.force();
        var completeTask = removeCommand.completeTask();
        Map<String, Object> taskPayload = Collections.emptyMap();
        Map<String, Object> wt = null;
        try {
            wt = find(name);
            if (wt == null) {
                return "Error: Unknown worktree '" + name + "'";
            }

            taskPayload = wt.containsKey("task_id") ?
                    Collections.singletonMap("id", wt.get("task_id")) : Collections.emptyMap();

            Map<String, Object> worktreePayload = new HashMap<>();
            worktreePayload.put("name", name);
            worktreePayload.put("path", wt.get("path"));
            events.emit(new EventBus.EmitCommand("worktree.remove.before", taskPayload, worktreePayload, null));


            List<String> args = new ArrayList<>(Arrays.asList("worktree", "remove"));
            if (force) {
                args.add("--force");
            }
            args.add((String) wt.get("path"));
            runGit(args);

            if (completeTask && wt.containsKey("task_id")) {
                int taskId = (Integer) wt.get("task_id");
                String beforeJson = tasks.get(new TaskManager.GetTaskCommand(taskId));
                Map<String, Object> before = objectMapper.readValue(beforeJson, new TypeReference<>() {
                });

                tasks.update(new TaskManager.UpdateTaskCommand(taskId, "completed", null, null));
                tasks.unbindWorktree(new TaskManager.UnbindWorktreeCommand(taskId));

                Map<String, Object> completedTask = new HashMap<>();
                completedTask.put("id", taskId);
                completedTask.put("subject", before.getOrDefault("subject", ""));
                completedTask.put("status", "completed");

                Map<String, Object> completedWorktree = Collections.singletonMap("name", name);

                events.emit(new EventBus.EmitCommand("task.completed", completedTask, completedWorktree, null));
            }

            Map<String, List<Map<String, Object>>> index = loadIndex();
            List<Map<String, Object>> worktrees = index.getOrDefault("worktrees", new ArrayList<>());

            for (Map<String, Object> item : worktrees) {
                if (name.equals(item.get("name"))) {
                    item.put("status", "removed");
                    item.put("removed_at", System.currentTimeMillis() / 1000);
                }
            }
            saveIndex(index);

            Map<String, Object> afterWorktreePayload = new HashMap<>();
            afterWorktreePayload.put("name", name);
            afterWorktreePayload.put("path", wt.get("path"));
            afterWorktreePayload.put("status", "removed");

            events.emit(new EventBus.EmitCommand("worktree.remove.after", taskPayload, afterWorktreePayload, null));
            return "Removed worktree '" + name + "'";
        } catch (Exception e) {
            if (wt == null) {
                return "Error: Unknown worktree '" + name + "'";
            }
            Map<String, Object> errorPayload = new HashMap<>();
            errorPayload.put("name", name);
            errorPayload.put("path", wt.get("path"));
            errorPayload.put("error", e.getMessage());
            events.emit(new EventBus.EmitCommand("worktree.remove.failed", taskPayload, null, e.getMessage()));
            throw new RuntimeException(e);
        }
    }

    public record KeepCommand(String name) {
    }

    public String keep(KeepCommand command) {
        try {
            var name = command.name();

            Map<String, Object> wt = find(name);
            if (wt == null) {
                return "Error: Unknown worktree '" + name + "'";
            }

            Map<String, List<Map<String, Object>>> index = loadIndex();
            List<Map<String, Object>> worktrees = index.getOrDefault("worktrees", new ArrayList<>());

            Map<String, Object> kept = null;
            for (Map<String, Object> item : worktrees) {
                if (name.equals(item.get("name"))) {
                    item.put("status", "kept");
                    item.put("kept_at", System.currentTimeMillis() / 1000);
                    kept = item;
                    break;
                }
            }
            saveIndex(index);

            Map<String, Object> taskPayload = wt.containsKey("task_id") ?
                    Collections.singletonMap("id", wt.get("task_id")) : Collections.emptyMap();

            Map<String, Object> worktreePayload = new HashMap<>();
            worktreePayload.put("name", name);
            worktreePayload.put("path", wt.get("path"));
            worktreePayload.put("status", "kept");

            events.emit(new EventBus.EmitCommand("worktree.keep", taskPayload, worktreePayload, null));

            return kept != null ? objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(kept) :
                    "Error: Unknown worktree '" + name + "'";
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}