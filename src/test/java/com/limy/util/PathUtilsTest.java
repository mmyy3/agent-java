package com.limy.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.nio.file.Paths;

public class PathUtilsTest {
    
    private Path testWorkDir;
    
    @BeforeEach
    void setUp() {
        // 设置测试工作目录
        testWorkDir = Paths.get(System.getProperty("user.dir"));
    }
    
    @Test
    void testSafePathValidRelativePath() {
        // 测试有效的相对路径
        Path result = PathUtils.safePath("test/file.txt");
        assertNotNull(result);
        assertTrue(result.isAbsolute());
        assertTrue(result.startsWith(testWorkDir));
    }
    
    @Test
    void testSafePathValidSubdirectory() {
        // 测试有效的子目录路径
        Path result = PathUtils.safePath("src/main/java");
        assertNotNull(result);
        assertTrue(result.isAbsolute());
        assertTrue(result.startsWith(testWorkDir));
    }
    
    @Test
    void testSafePathDirectoryTraversalBlocked() {
        // 测试路径逃逸应该抛出异常
        assertThrows(IllegalArgumentException.class, () -> {
            PathUtils.safePath("./././../../../etc/passwd");
        });
    }
    
    @Test
    void testSafePathAbsolutePathBlocked() {
        // 测试绝对路径逃逸应该抛出异常
        assertThrows(IllegalArgumentException.class, () -> {
            PathUtils.safePath("/etc/passwd");
        });
    }
    
    @Test
    void testSafePathCurrentDirectory() {
        // 测试当前目录
        Path result = PathUtils.safePath(".");
        assertNotNull(result);
        assertEquals(testWorkDir.normalize(), result.normalize());
    }
    
    @Test
    void testSafePathEmptyPath() {
        // 测试空路径
        Path result = PathUtils.safePath("");
        assertNotNull(result);
        assertEquals(testWorkDir.normalize(), result.normalize());
    }
}