package com.limy.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

public class TaskManager {
    private static final List<String> statusList = List.of("pending", "in_progress", "completed");
    private final Path tasksDir;
    private int nextId;
    private final ObjectMapper objectMapper;
    private final ReentrantLock claimLock = new ReentrantLock();

    public TaskManager(Path tasksDir) {
        this.tasksDir = tasksDir;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        try {
            Files.createDirectories(tasksDir);
            this.nextId = maxId() + 1;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private int maxId() throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(tasksDir, "task_*.json")) {
            List<Integer> ids = new ArrayList<>();
            for (Path file : stream) {
                String fileName = file.getFileName().toString();
                // Remove "task_" and ".json"
                String idStr = fileName.substring(5, fileName.length() - 5);
                try {
                    ids.add(Integer.parseInt(idStr));
                } catch (NumberFormatException e) {
                    // Skip invalid file names
                }
            }
            return ids.isEmpty() ? 0 : Collections.max(ids);
        }
    }

    private Task load(int taskId) throws IOException {
        Path path = tasksDir.resolve("task_" + taskId + ".json");
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("Task " + taskId + " not found");
        }
        return objectMapper.readValue(path.toFile(), Task.class);
    }

    private void save(Task task) throws IOException {
        Path path = tasksDir.resolve("task_" + task.getId() + ".json");
        objectMapper.writeValue(path.toFile(), task);
    }

    public record CreateTaskCommand(String subject, String description) {}

    public String create(CreateTaskCommand command) {
        try {
            Task task = new Task(nextId, command.subject(), null == command.description() ? "" : command.description());
            save(task);
            nextId++;
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(task);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public record GetTaskCommand(int taskId) {}

    public String get(GetTaskCommand command) {
        try {
            Task task = load(command.taskId());
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(task);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public record UpdateTaskCommand(int taskId, String status, List<Integer> addBlockedBy, List<Integer> addBlocks) {}

    public String update(UpdateTaskCommand command) {
        try {
            Task task = load(command.taskId());

            if (command.status() != null) {
                if (!statusList.contains(command.status())) {
                    throw new IllegalArgumentException("Invalid status: " + command.status());
                }
                task.setStatus(command.status());

                // When a task is completed, remove it from all other tasks' blockedBy
                if ("completed".equals(command.status())) {
                    clearDependency(command.taskId());
                }
            }

            if (command.addBlockedBy() != null && !command.addBlockedBy().isEmpty()) {
                Set<Integer> currentBlockedBy = new HashSet<>(task.getBlockedBy());
                currentBlockedBy.addAll(command.addBlockedBy());
                task.setBlockedBy(new ArrayList<>(currentBlockedBy));
            }

            if (command.addBlocks() != null && !command.addBlocks().isEmpty()) {
                Set<Integer> currentBlocks = new HashSet<>(task.getBlocks());
                currentBlocks.addAll(command.addBlocks());
                task.setBlocks(new ArrayList<>(currentBlocks));

                // Bidirectional: also update the blocked tasks' blockedBy lists
                for (Integer blockedId : command.addBlocks()) {
                    try {
                        Task blockedTask = load(blockedId);
                        if (!blockedTask.getBlockedBy().contains(command.taskId())) {
                            blockedTask.getBlockedBy().add(command.taskId());
                            save(blockedTask);
                        }
                    } catch (IllegalArgumentException e) {
                        // Blocked task doesn't exist, skip
                    }
                }
            }

            save(task);
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(task);
        } catch (Exception e) {
            throw new RuntimeException("Error updating task: " + e.getMessage(), e);
        }
    }

    private void clearDependency(int completedId) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(tasksDir, "task_*.json")) {
            for (Path file : stream) {
                Task task = objectMapper.readValue(file.toFile(), Task.class);
                if (task.getBlockedBy().contains(completedId)) {
                    task.getBlockedBy().remove(Integer.valueOf(completedId));
                    save(task);
                }
            }
        }
    }

    public String listAll() {
        try {
            List<Task> tasks = new ArrayList<>();

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(tasksDir, "task_*.json")) {
                List<Path> sortedFiles = new ArrayList<>();
                for (Path file : stream) {
                    sortedFiles.add(file);
                }
                sortedFiles.sort(Comparator.comparing(Path::getFileName));

                for (Path file : sortedFiles) {
                    tasks.add(objectMapper.readValue(file.toFile(), Task.class));
                }
            }

            if (tasks.isEmpty()) {
                return "No tasks.";
            }

            List<String> lines = getLines(tasks);

            return String.join("\n", lines);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // -- Task board scanning --
    public List<Task> scanUnclaimedTasks() {
        try {
            List<Task> tasks = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(tasksDir, "task_*.json")) {
                List<Path> sortedFiles = new ArrayList<>();
                for (Path file : stream) {
                    sortedFiles.add(file);
                }
                sortedFiles.sort(Comparator.comparing(Path::getFileName));

                for (Path file : sortedFiles) {
                    var task = objectMapper.readValue(file.toFile(), Task.class);
                    if (task.getStatus().equals("pending") && null == task.getOwner() && (null == task.getBlockedBy() || task.getBlockedBy().isEmpty())) {
                        tasks.add(task);
                    }
                }
            }
            return tasks;

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public record ClaimTaskCommand(int taskId, String owner) {}
    public String claimTask(ClaimTaskCommand command) {
        this.claimLock.lock();
        try {
            Task task = load(command.taskId());
            if (null != task.getOwner()) {
                return String.format("[FAIL] Task already claimed #%s for %s", command.taskId(), task.getOwner());
            }
            task.setOwner(command.owner());
            task.setStatus("in_progress");
            save(task);
        } catch (IOException e) {
            return String.format("[FAIL] Failed to claim task #%s for reason %s", command.taskId(), e.getMessage());
        } finally {
            this.claimLock.unlock();
        }
        return String.format("[SUCCESS] Claimed task #%s for %s", command.taskId(), command.owner());
    }

    public record BindWorktreeCommand(int taskId, String worktree, String owner) {}

    public String bindWorktree(BindWorktreeCommand command) {
        this.claimLock.lock();
        try {
            Task task = load(command.taskId());
            if (null != command.owner() && null != task.getOwner()) {
                return String.format("[FAIL] Task already claimed #%s for %s", command.taskId(), task.getOwner());
            }
            if (null != command.owner()) {
                task.setOwner(command.owner());
            }
            assert command.worktree() != null;
            task.setWorktree(command.worktree());
            if ("pending".equalsIgnoreCase(task.getStatus())) {
                task.setStatus("in_progress");
            }
            task.setUpdatedAt(System.currentTimeMillis());
            save(task);
            return this.objectMapper.writeValueAsString(task);
        } catch (IOException e) {
            return String.format("[FAIL] Failed to bind task #%s for reason %s", command.taskId(), e.getMessage());
        } finally {
            this.claimLock.unlock();
        }
    }

    public record UnbindWorktreeCommand(int taskId) {}

    public String unbindWorktree(UnbindWorktreeCommand command) {
        this.claimLock.lock();
        try {
            Task task = load(command.taskId());
            task.setWorktree("");
            task.setUpdatedAt(System.currentTimeMillis());
            save(task);
            return this.objectMapper.writeValueAsString(task);
        } catch (IOException e) {
            return String.format("[FAIL] Failed to bind task #%s for reason %s", command.taskId(), e.getMessage());
        } finally {
            this.claimLock.unlock();
        }
    }

    private static List<String> getLines(List<Task> tasks) {
        List<String> lines = new ArrayList<>();
        lines.add("\n");
        for (Task task : tasks) {
            String marker = switch (task.getStatus()) {
                case "pending" -> "[ ]";
                case "in_progress" -> "[>]";
                case "completed" -> "[x]";
                default -> "[?]";
            };

            var blocked = task.getBlockedBy().isEmpty() ? "" :
                " (blocked by: " + task.getBlockedBy() + ")";
            var owner = " @" + (task.getOwner() == null ? "-": task.getOwner()) + " ";
            lines.add(marker + " #" + task.getId() + ": " + task.getSubject() + owner + blocked);
        }
        return lines;
    }
}