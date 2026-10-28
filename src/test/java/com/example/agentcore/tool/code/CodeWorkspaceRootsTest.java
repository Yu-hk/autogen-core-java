package com.example.agentcore.tool.code;

import com.example.agentcore.agent.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工作区多根：用户选的目录可写、额外目录可读或可写。
 *
 * <p>原来只有一个写死的沙箱 + 若干只读目录，结果是"能看不能改"——真实项目的需求
 * 根本落不下去。改成用户选目录当主根之后，这里钉住三件事：选中的目录真的能写、
 * 只读目录仍然不能写、越界仍然被拦。第三件最关键：放开写权限不能让模型自己扩权。</p>
 */
class CodeWorkspaceRootsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("用户选的目录成为主根：可读可写，默认起点就是它")
    void userPickedDirIsWritableRoot(@TempDir Path picked, @TempDir Path other) throws IOException {
        CodeWorkspace ws = CodeWorkspace.forUserSelection(picked);
        assertEquals(picked.toAbsolutePath().normalize(), ws.root());
        assertTrue(ws.writable(ws.root().resolve("a.txt")));

        Files.writeString(picked.resolve("Hello.java"), "class Hello {}\n", StandardCharsets.UTF_8);
        ToolRegistry registry = ToolRegistry.of(
                CodeTools.all(ws, 120, com.example.agentcore.tool.db.DbTool.fromConfig(), false));

        // 不带 dir 的 list_files 就应该列出选中目录里的东西
        JsonNode listed = MAPPER.readTree(registry.invoke("list_files", "{\"dir\":\".\"}"));
        assertEquals("Hello.java", listed.path("entries").get(0).path("path").asText());

        // 真的能写进去，而不只是"报错说只读"
        String raw = registry.invoke("write_file",
                "{\"path\":\"New.java\",\"content\":\"class New {}\"}");
        assertTrue(MAPPER.readTree(raw).path("ok").asBoolean(), raw);
        assertTrue(Files.exists(picked.resolve("New.java")));
        assertFalse(Files.exists(other.resolve("New.java")));
    }

    @Test
    @DisplayName("只读根：读得到，写会明确拒绝并说明怎么办")
    void readOnlyRootStaysReadOnly(@TempDir Path root, @TempDir Path ref) throws IOException {
        Files.writeString(ref.resolve("Ref.java"), "class Ref {}\n", StandardCharsets.UTF_8);
        CodeWorkspace ws = new CodeWorkspace(root, List.of(ref));
        ToolRegistry registry = ToolRegistry.of(
                CodeTools.all(ws, 120, com.example.agentcore.tool.db.DbTool.fromConfig(), false));

        // 读：绝对路径放行
        String read = registry.invoke("read_file",
                "{\"path\":\"" + ref.resolve("Ref.java").toString().replace("\\", "/") + "\"}");
        assertTrue(read.contains("class Ref"), read);

        // 写：拒绝，且提示"把该目录选为工作目录"而不是笼统的越界
        String write = registry.invoke("write_file",
                "{\"path\":\"" + ref.resolve("Ref.java").toString().replace("\\", "/")
                        + "\",\"content\":\"x\"}");
        assertTrue(write.startsWith("ERROR"), write);
        assertTrue(write.contains("只读"), write);
        assertEquals("class Ref {}\n", Files.readString(ref.resolve("Ref.java")));
    }

    @Test
    @DisplayName("额外可写目录：不在主根内，但确实能写")
    void extraWritableRoot(@TempDir Path root, @TempDir Path second) throws IOException {
        CodeWorkspace ws = new CodeWorkspace(root, List.of(), List.of(second));
        assertTrue(ws.writableRoots().contains(second.toAbsolutePath().normalize()));

        ToolRegistry registry = ToolRegistry.of(
                CodeTools.all(ws, 120, com.example.agentcore.tool.db.DbTool.fromConfig(), false));
        String raw = registry.invoke("write_file",
                "{\"path\":\"" + second.resolve("B.java").toString().replace("\\", "/")
                        + "\",\"content\":\"class B {}\"}");
        assertTrue(MAPPER.readTree(raw).path("ok").asBoolean(), raw);
        assertTrue(Files.exists(second.resolve("B.java")));
    }

    @Test
    @DisplayName("越界：一个没被授权的目录，读写都不行")
    void unauthorizedDirIsBlocked(@TempDir Path root, @TempDir Path outside) throws IOException {
        CodeWorkspace ws = new CodeWorkspace(root);
        String abs = outside.resolve("x.txt").toString().replace("\\", "/");
        ToolRegistry registry = ToolRegistry.of(
                CodeTools.all(ws, 120, com.example.agentcore.tool.db.DbTool.fromConfig(), false));

        String read = registry.invoke("read_file", "{\"path\":\"" + abs + "\"}");
        assertTrue(read.startsWith("ERROR"), read);
        assertTrue(read.contains("不在任何已授权目录内"), read);
        assertTrue(read.contains("选为工作目录"), "要告诉用户怎么解决：" + read);
    }

    @Test
    @DisplayName("不能把盘符根、用户主目录、系统目录当工作区")
    void rejectSensitiveDirs(@TempDir Path ok) {
        // 盘符根：parent 为 null
        Path driveRoot = ok.toAbsolutePath().normalize().getRoot();
        assertNotNull(CodeWorkspace.rejectReason(driveRoot), "盘符根必须被拒");

        // 用户主目录本身
        Path home = Path.of(System.getProperty("user.home"));
        assertNotNull(CodeWorkspace.rejectReason(home), "用户主目录本身必须被拒");

        // 普通目录放行
        assertNull(CodeWorkspace.rejectReason(ok), "普通项目目录应放行：" + ok);

        // 不存在的目录
        assertNotNull(CodeWorkspace.rejectReason(ok.resolve("not-exists-yet")));
    }

    @Test
    @DisplayName("没选目录时退回配置：行为与之前一致")
    void nullSelectionFallsBackToConfig() {
        CodeWorkspace ws = CodeWorkspace.forUserSelection(null);
        assertEquals(CodeWorkspace.fromConfig().root(), ws.root());
    }
}
