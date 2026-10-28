package com.example.agentcore.tool.code;

import com.example.agentcore.agent.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 命令工具在 Windows / Linux 下的行为差异。
 *
 * <p>只测"决策"，不测"另一个平台真的能跑"——这台机器只有一种操作系统，
 * 假装能跑另一种平台的命令是自欺欺人。真正需要钉死的是：<b>命令写错平台时，
 * 工具会不会给出本平台的等价写法</b>。实测模型第一次写 {@code ls -l} 是常态，
 * 一句"用 dir /b"能省掉一整轮试错。</p>
 */
class CodeCommandToolPlatformTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ToolRegistry registry(Path root, Platform platform) {
        return ToolRegistry.of(new CodeCommandTool(new CodeWorkspace(root), 30, platform));
    }

    private static JsonNode call(ToolRegistry registry, String json) {
        String raw = registry.invoke("run_command", json);
        assertFalse(raw.startsWith("ERROR"), "拒绝/失败都该是结果，不该是工具故障：" + raw);
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new AssertionError("返回的不是 JSON：" + raw, e);
        }
    }

    @Test
    @DisplayName("Windows 上写 ls，拒绝并直接给出 dir 的写法")
    void lsOnWindowsPointsToDir(@TempDir Path root) {
        JsonNode result = call(registry(root, Platform.WINDOWS),
                "{\"command\":\"ls -l src\"}");

        assertFalse(result.path("ok").asBoolean(), result.toString());
        assertTrue(result.path("blocked").asBoolean(), result.toString());
        assertTrue(result.path("reason").asText().contains("dir"), result.toString());
        assertEquals("dir /b（只看名字）或 dir（带大小和时间）",
                result.path("suggestion").asText().split("；")[0], result.toString());
        assertEquals("Windows", result.path("platform").asText(), result.toString());
        assertEquals("cmd", result.path("shell").asText(), result.toString());
    }

    @Test
    @DisplayName("Linux 上写 dir / type / findstr，也给出 POSIX 等价写法")
    void windowsCommandsOnLinuxPointToPosix(@TempDir Path root) {
        ToolRegistry registry = registry(root, Platform.LINUX);

        JsonNode dir = call(registry, "{\"command\":\"dir /b\"}");
        assertTrue(dir.path("reason").asText().contains("ls -l"), dir.toString());

        JsonNode type = call(registry, "{\"command\":\"type Main.java\"}");
        assertTrue(type.path("reason").asText().contains("cat"), type.toString());

        JsonNode findstr = call(registry, "{\"command\":\"findstr /s TODO\"}");
        assertTrue(findstr.path("reason").asText().contains("grep"), findstr.toString());

        assertEquals("Linux", dir.path("platform").asText(), dir.toString());
        assertEquals("sh", dir.path("shell").asText(), dir.toString());
    }

    @Test
    @DisplayName("指定 Linux 不存在的 shell（cmd）时，说明当前能用什么")
    void askingForCmdOnLinuxIsRejectedWithAHint(@TempDir Path root) {
        JsonNode result = call(registry(root, Platform.LINUX),
                "{\"command\":\"echo hi\",\"shell\":\"cmd\"}");

        assertFalse(result.path("ok").asBoolean(), result.toString());
        assertTrue(result.path("blocked").asBoolean(), result.toString());
        assertTrue(result.path("reason").asText().contains("没有 cmd"), result.toString());
        assertEquals("sh", result.path("shell").asText(),
                "拒绝时也要告诉它默认 shell 是哪个：" + result);
    }

    @Test
    @DisplayName("删除类命令一律拒绝，但 perform 这类单词不能被误伤")
    void deleteIsRejectedWithoutFalsePositives(@TempDir Path root) {
        ToolRegistry registry = registry(root, Platform.WINDOWS);

        JsonNode rm = call(registry, "{\"command\":\"rm build/out.class\"}");
        assertTrue(rm.path("blocked").asBoolean(), rm.toString());
        assertTrue(rm.path("reason").asText().contains("删除"), rm.toString());

        JsonNode del = call(registry, "{\"command\":\"del Main.java\"}");
        assertTrue(del.path("blocked").asBoolean(), del.toString());

        // "perform" 里有 "rm "，不能因为子串匹配就把一句正常的命令拦掉
        JsonNode harmless = call(registry, "{\"command\":\"java -version\"}");
        assertFalse(harmless.path("blocked").asBoolean(), harmless.toString());
    }

    @Test
    @DisplayName("真的执行一条命令：走当前平台的 shell，返回 platform/shell/executed")
    void runsOnTheCurrentPlatform(@TempDir Path root) {
        Platform platform = Platform.current();
        ToolRegistry registry = registry(root, platform);

        JsonNode result = call(registry, "{\"command\":\"echo autogen-platform-check\"}");

        assertEquals(platform.label(), result.path("platform").asText(), result.toString());
        assertEquals(platform.defaultShell().displayName(), result.path("shell").asText(), result.toString());
        assertTrue(result.path("ok").asBoolean(), result.toString());
        assertEquals(0, result.path("exitCode").asInt(), result.toString());
        assertTrue(result.path("stdout").asText().contains("autogen-platform-check"), result.toString());

        String executed = result.path("executed").asText();
        if (platform.isWindows()) {
            assertTrue(executed.startsWith("cmd /c"), executed);
            assertTrue(executed.contains("chcp 65001"), "Windows 下必须切 UTF-8 代码页，否则中文报错乱码：" + executed);
        } else {
            assertTrue(executed.startsWith("sh -c") || executed.startsWith("bash -lc"), executed);
        }
    }

    @Test
    @DisplayName("首词白名单挡不住的套壳写法也要拒：xargs / find -delete / cmd 套壳 / powershell 删文件")
    void shellWrappersCannotSmuggleDeletes(@TempDir Path root) {
        ToolRegistry linux = registry(root, Platform.LINUX);
        for (String command : new String[]{
                "ls | xargs rm",
                "find . -name \"*.class\" -delete",
                "grep -rn TODO . -exec rm {} \\;"}) {
            JsonNode node = call(linux, "{\"command\":" + quote(command) + "}");
            assertTrue(node.path("blocked").asBoolean(), "没挡住：" + command + " → " + node);
        }

        // Windows 上再套一层 cmd 是想绕过首词检查的经典手法，现在 cmd 本身不在白名单里
        ToolRegistry windows = registry(root, Platform.WINDOWS);
        JsonNode nested = call(windows, "{\"command\":\"cmd /c del EmployeeAvg.java\"}");
        assertTrue(nested.path("blocked").asBoolean(), nested.toString());

        JsonNode ps = call(windows,
                "{\"command\":\"powershell -Command Remove-Item EmployeeAvg.java\"}");
        assertTrue(ps.path("blocked").asBoolean(), ps.toString());
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @Test
    @DisplayName("workdir 不存在时明确报错，不悄悄在根目录跑")
    void missingWorkdirFailsLoudly(@TempDir Path root) {
        ToolRegistry registry = registry(root, Platform.current());
        String raw = registry.invoke("run_command",
                "{\"command\":\"echo hi\",\"workdir\":\"no-such-dir\"}");
        assertTrue(raw.startsWith("ERROR"), raw);
        assertTrue(raw.contains("workdir 不存在"), raw);
    }
}
