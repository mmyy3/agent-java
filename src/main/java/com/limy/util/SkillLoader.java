package com.limy.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class SkillLoader {
    private final Path skillsDir;
    private final Map<String, SkillData> skills;

    public SkillLoader(Path skillsDir) {
        this.skillsDir = skillsDir;
        this.skills = new HashMap<>();
        loadAll();
    }

    private void loadAll() {
        if (!Files.exists(skillsDir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(skillsDir, 2)) {
            paths.filter(path -> path.toString().endsWith(".md"))
                 .sorted()
                 .forEach(this::loadSkill);
        } catch (IOException e) {
            System.err.println("Error loading skills: " + e.getMessage());
        }
    }

    private void loadSkill(Path file) {
        try {
            String name = file.getParent().getFileName().toString();
            String content = Files.readString(file);

            FrontmatterData frontmatter = parseFrontmatter(content);
            skills.put(name, new SkillData(frontmatter.meta, frontmatter.body, file.getParent().toString()));
        } catch (IOException e) {
            System.err.println("Error loading skill file: " + file + " - " + e.getMessage());
        }
    }

    private FrontmatterData parseFrontmatter(String text) {
        Pattern pattern = Pattern.compile("^---\\n(.*?)\\n---\\n(.*)", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(text);

        if (!matcher.find()) {
            return new FrontmatterData(new HashMap<>(), text);
        }

        Map<String, String> meta = new HashMap<>();
        String[] lines = matcher.group(1).trim().split("\n");

        var sb = new StringBuilder();
        var curKey = "";
        for (String line : lines) {
            if (line.contains(":") && !line.startsWith(" ")) {
                String[] parts = line.split(":", 2);
                if (!curKey.equalsIgnoreCase("")) {
                    meta.put(curKey, sb.toString());
                    sb.setLength(0);
                }
                curKey = parts[0].trim();
                sb.append(parts[1].trim());
            } else {
                sb.append("\n").append(line);
            }
        }
        if (!curKey.equalsIgnoreCase("")) {
            meta.put(curKey, sb.toString());
        }

        return new FrontmatterData(meta, matcher.group(2).trim());
    }

    public String getDescriptions() {
        if (skills.isEmpty()) {
            return "(no skills available)";
        }

        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, SkillData> entry : skills.entrySet()) {
            String name = entry.getKey();
            SkillData skill = entry.getValue();

            String desc = skill.meta.getOrDefault("description", "No description");
            String tags = skill.meta.getOrDefault("tags", "");

            result.append("- ").append(name).append(": ").append(desc);
            if (!tags.isEmpty()) {
                result.append(" [").append(tags).append("]");
            }
            result.append("\n");
            result.append("  [skillResourceRootDir " + skill.path() + "]\n");
        }

        return result.toString().stripTrailing();
    }

    public String getContent(GetContentParam name) {
        SkillData skill = skills.get(name.name());
        if (skill == null) {
            return String.format("Error: Unknown skill '%s'. Available: %s",
                name, String.join(", ", skills.keySet()));
        }

        return String.format("<skill name=\"%s\">\n%s\n</skill>", name, skill.body);
    }

    // 内部类用于存储技能数据
    private record SkillData(Map<String, String> meta, String body, String path) {}

    // 内部类用于存储前置元数据解析结果
    private record FrontmatterData(Map<String, String> meta, String body) {}

    public record GetContentParam(String name) {}
}