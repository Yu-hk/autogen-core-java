package com.example.agentcore;

import com.example.agentcore.agent.ToolRegistry;
import com.example.agentcore.tool.code.CodeFilesTool;
import com.example.agentcore.tool.code.CodeTools;
import com.example.agentcore.tool.code.CodeWorkspace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具子集：只挂一个工具类里的某几个方法。
 *
 * <p>这个能力是给"能读不能写"的助手用的。它必须做到两件事，缺一不可：</p>
 * <ol>
 *   <li>schema 里只有被选中的那几个——模型<b>看不见</b>写工具，才不会去写；</li>
 *   <li>没被选中的名字即使被模型硬报上来，也调不动（不是靠参数校验拒绝，而是根本没注册）。</li>
 * </ol>
 *
 * <p>如果只做 2 不做 1，模型会先试一次写、被拒、再试一次，把轮次耗在注定失败的动作上。</p>
 */
class ToolRegistrySubsetTest {

    @Test
    @DisplayName("只读子集：只剩 4 个读方法，写与命令都不在 schema 里")
    void readOnlySubsetHidesWrites(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.txt"), "hello");
        CodeWorkspace workspace = new CodeWorkspace(dir);

        ToolRegistry registry = CodeTools.readOnly(workspace);
        List<String> names = registry.specifications().stream()
                .map(s -> s.name()).sorted().toList();

        assertEquals(List.of("list_files", "read_dir", "read_file", "search_code"), names);
        assertFalse(names.contains("write_file"), "只读子集不该暴露写文件：" + names);
        assertFalse(names.contains("edit_file"), "只读子集不该暴露改文件：" + names);
        assertFalse(names.contains("run_command"), "只读子集本来就没有命令工具：" + names);
    }

    @Test
    @DisplayName("子集外的工具被调到时是「未注册」，不是「执行了再拒」")
    void subsetRejectsUnregisteredTool(@TempDir Path dir) throws Exception {
        ToolRegistry registry = CodeTools.readOnly(new CodeWorkspace(dir));

        String result = registry.invoke("write_file",
                "{\"path\":\"x.txt\",\"content\":\"boom\"}");
        assertTrue(result.contains("未注册"), "应是未注册，而不是真的写了文件：" + result);
        assertFalse(Files.exists(dir.resolve("x.txt")), "文件不该被创建");
    }

    @Test
    @DisplayName("子集仍能正常读：读文件拿到内容与 sha256")
    void subsetStillReads(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.txt"), "hello\n");
        ToolRegistry registry = CodeTools.readOnly(new CodeWorkspace(dir));

        String result = registry.invoke("read_file", "{\"path\":\"a.txt\"}");
        assertTrue(result.contains("hello"), result);
        assertTrue(result.contains("sha256"), "只读也要能给出哈希（模型可能接着要求改）：" + result);
    }

    @Test
    @DisplayName("subset 忽略不存在的名字，顺序按传入顺序")
    void subsetIgnoresUnknownNames() {
        Object bare = new CodeFilesTool(new CodeWorkspace(java.nio.file.Path.of(".")), false);
        ToolRegistry registry = ToolRegistry.of(bare)
                .subset("read_file", "no_such_tool", "list_files");

        List<String> names = registry.specifications().stream().map(s -> s.name()).toList();
        assertEquals(List.of("read_file", "list_files"), names);
    }
}
