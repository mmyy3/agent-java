package com.limy.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
/**
 * BackgroundManager provides threaded execution of system commands with notification queue.
 */
public class BackgroundManager {

    private final ConcurrentHashMap<String, TaskInfo> tasks;
    private final List<Notification> notificationQueue;
    private final Lock lock;
    private final String workDir;
    private final ExecutorService executorService;

    public BackgroundManager(String workDir) {
        this.tasks = new ConcurrentHashMap<>();
        this.notificationQueue = new ArrayList<>();
        this.lock = new ReentrantLock();
        this.workDir = workDir;
        this.executorService = Executors.newFixedThreadPool(10);
    }

    public record RunBackgroundTaskCommand(String command) {}

    /**
     * Start a background thread to execute command, return task_id immediately.
     */
    public String run(RunBackgroundTaskCommand command) {
        String taskId = generateTaskId();
        TaskInfo taskInfo = new TaskInfo(taskId, "running", command.command());
        tasks.put(taskId, taskInfo);
        executorService.execute(() -> execute(taskId, command.command()));
        return String.format("Background task %s started: %s", taskId, truncate(command.command(), 80));
    }

    /**
     * Thread target: run subprocess, capture output, push to queue.
     */
    private void execute(String taskId, String command) {
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
            pb.directory(new java.io.File(workDir));
            Process process = pb.start();

            // Capture output
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            try (BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = errorReader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(300, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                updateTask(taskId, "timeout", "Error: Timeout (300s)");
                addNotification(taskId, "timeout", command, "Error: Timeout (300s)");
                return;
            }

            String result = output.toString().trim();
            if (result.isEmpty()) {
                result = "(no output)";
            }
            result = truncate(result, 50000);

            updateTask(taskId, "completed", result);
            addNotification(taskId, "completed", command, result);

        } catch (InterruptedException e) {
            String error = "Error: Interrupted";
            updateTask(taskId, "error", error);
            addNotification(taskId, "error", command, error);
        } catch (Exception e) {
            String error = "Error: " + e.getMessage();
            updateTask(taskId, "error", error);
            addNotification(taskId, "error", command, error);
        }
    }

    /**
     * Check status of one task or list all.
     */
    public String check(CheckBackgroundTaskCommand command) {
        if (command.taskId() != null && !command.taskId().isEmpty()) {
            TaskInfo task = tasks.get(command.taskId());
            if (task == null) {
                return String.format("Error: Unknown task %s", command.taskId());
            }
            return String.format("[%s] %s\n%s",
                    task.getStatus(),
                    truncate(task.getCommand(), 60),
                    task.getResult() != null ? task.getResult() : "(running)");
        }

        if (tasks.isEmpty()) {
            return "No background tasks.";
        }

        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, TaskInfo> entry : tasks.entrySet()) {
            TaskInfo task = entry.getValue();
            result.append(String.format("%s: [%s] %s%n",
                    entry.getKey(),
                    task.getStatus(),
                    truncate(task.getCommand(), 60)));
        }
        return result.toString().trim();
    }

    public record CheckBackgroundTaskCommand(String taskId) {}

    /**
     * Return and clear all pending completion notifications.
     */
    public List<Notification> drainNotifications() {
        lock.lock();
        try {
            List<Notification> notifications = new ArrayList<>(notificationQueue);
            notificationQueue.clear();
            return notifications;
        } finally {
            lock.unlock();
        }
    }

    private void updateTask(String taskId, String status, String result) {
        tasks.computeIfPresent(taskId, (key, v) -> {
            v.setStatus(status);
            v.setResult(result);
            return v;
        });
    }

    private void addNotification(String taskId, String status, String command, String result) {
        lock.lock();
        try {
            notificationQueue.add(new Notification(taskId, status, command, result));
        } finally {
            lock.unlock();
        }
    }

    public void shutdown() {
        executorService.shutdown();
    }

    private String generateTaskId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String truncate(String str, int maxLength) {
        if (str == null) return "";
        if (str.length() <= maxLength) return str;
        return str.substring(0, maxLength);
    }

    /**
     * Inner class to hold task information.
     */
    private static class TaskInfo {
        private final String taskId;
        private String status;
        private String result;
        private final String command;

        public TaskInfo(String taskId, String status, String command) {
            this.taskId = taskId;
            this.status = status;
            this.command = command;
            this.result = null;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public String getResult() {
            return result;
        }

        public void setResult(String result) {
            this.result = result;
        }

        public String getCommand() {
            return command;
        }
    }

    /**
     * Notification class for completed tasks.
     */
    public record Notification(String taskId, String status, String command, String result) {
    }
}