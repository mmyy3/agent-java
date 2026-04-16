package com.limy.util;

import com.limy.agents.Base;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class EnhancedBashExecutor {

    private static final Set<String> DANGEROUS_PATTERNS = Set.of(
            "rm -rf /", "sudo", "shutdown", "reboot", "> /dev/", "mkfs",
            "dd if=", ":(){ :|:& };:", "wget", "curl", "nc -", "nmap"
    );

    public record ExecutionResult(String output, int exitCode, boolean timedOut) {
        public boolean isSuccess() {
            return exitCode == 0 && !timedOut;
        }
    }

    /**
     * 执行命令并返回详细结果
     */
    private static ExecutionResult executeCommand(String command) {
        // 安全检查
        for (String pattern : DANGEROUS_PATTERNS) {
            if (command.contains(pattern)) {
                return new ExecutionResult("Error: Dangerous command blocked", -1, false);
            }
        }

        try {
            String currentDir = Base.WORKDIR;

            ProcessBuilder pb = new ProcessBuilder();
            boolean isWindows = System.getProperty("os.name").toLowerCase().startsWith("windows");

            if (isWindows) {
                pb.command("cmd.exe", "/c", command);
            } else {
                pb.command("bash", "-c", command);
            }

            pb.directory(new File(currentDir));
            pb.redirectErrorStream(true); // 合并stdout和stderr

            Process process = pb.start();

            // 读取输出
            String output = readProcessOutput(process);

            // 等待完成或超时
            boolean finished = process.waitFor(120, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                return new ExecutionResult("Error: Timeout (120s)", -1, true);
            }

            int exitCode = process.exitValue();

            // 限制输出长度
            if (output.length() > 50000) {
                output = output.substring(0, 50000) + "\n... (truncated)";
            }

            if (output.isEmpty()) {
                output = "(no output)";
            }

            return new ExecutionResult(output, exitCode, false);

        } catch (IOException | InterruptedException e) {
            return new ExecutionResult("Error: " + e.getMessage(), -1, false);
        }
    }

    private static String readProcessOutput(Process process) throws IOException {
        StringBuilder output = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        return output.toString();
    }

    /**
     * 简化的runBash方法（与Python版本保持一致）
     */
    public static String runBash(BashCommand command) {
        ExecutionResult result = executeCommand(command.command());
        return result.output();
    }

    public record BashCommand(String command) {
    }
}
