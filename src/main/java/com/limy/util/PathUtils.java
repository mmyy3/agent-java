package com.limy.util;

import com.limy.agents.Base;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class PathUtils {
    
    private static final Path WORKDIR = Paths.get(Base.WORKDIR);
    
    /**
     * 安全路径验证函数 - Python safe_path的Java实现
     * 
     * @param p 相对路径字符串
     * @return 解析后的Path对象
     * @throws IllegalArgumentException 如果路径逃逸工作目录
     */
    public static Path safePath(String p) {
        // 解析路径并获取绝对路径
        Path path = WORKDIR.resolve(p).toAbsolutePath().normalize();
        
        // 检查路径是否在工作目录下
        if (!path.startsWith(WORKDIR)) {
            throw new IllegalArgumentException("Path escapes workspace: " + p);
        }
        
        return path;
    }
    
    /**
     * 读取文件内容函数 - Python run_read的Java实现
     */
    public static String runRead(ReadCommand readCommand) {
        try {
            Path filePath = safePath(readCommand.path());
            List<String> lines = Files.readAllLines(filePath);
            
            if (readCommand.limit() != null && readCommand.limit() < lines.size()) {
                lines = lines.subList(0, readCommand.limit());
                lines.add(String.format("... (%d more lines)", lines.size() - readCommand.limit()));
            }
            
            String content = String.join("\n", lines);
            return content.length() > 50000 ? content.substring(0, 50000) : content;
            
        } catch (IOException | IllegalArgumentException e) {
            return String.format("Error: %s", e.getMessage());
        }
    }

    public record ReadCommand(String path, Integer limit) {}

    /**
     * 安全写入文件函数 - Python run_write的Java实现
     */
    public static String runWrite(WriteCommand writeCommand) {
        try {
            Path filePath = safePath(writeCommand.path());
            Files.createDirectories(filePath.getParent());
            Files.write(filePath, writeCommand.content().getBytes());
            return String.format("Wrote %d bytes to %s", writeCommand.content().length(), writeCommand.path());
        } catch (IOException | IllegalArgumentException e) {
            return String.format("Error: %s", e.getMessage());
        }
    }

    public record WriteCommand(String path, String content) {}
    /**
     * 编辑文件内容函数 - Python run_edit的Java实现
     * 读取文件内容，替换指定的旧文本为新文本，然后写回文件
     */
    public static String runEdit(EditCommand editCommand) {
        try {
            Path filePath = safePath(editCommand.path());
            String content = Files.readString(filePath);
            
            if (!content.contains(editCommand.oldText())) {
                return String.format("Error: Text not found in %s", editCommand.path());
            }
            
            String newContent = content.replace(editCommand.oldText(), editCommand.newText());
            Files.writeString(filePath, newContent);
            
            return String.format("Edited %s", editCommand.path());
        } catch (IOException | IllegalArgumentException e) {
            return String.format("Error: %s", e.getMessage());
        }
    }

    public record EditCommand(String path, String oldText, String newText) {}
}