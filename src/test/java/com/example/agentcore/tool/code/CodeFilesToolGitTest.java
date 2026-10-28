package com.example.agentcore.tool.code;

import com.example.agentcore.agent.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 写完之后的 git 回显：给模型一个"你到底改了多少行"的客观数字。
 *
 * <p>这里踩过一个坑，值得钉死：{@code git -C <文件所在目录> diff -- <相对仓库根的路径>}
 * 会把路径拼成 {@code util/src/main/java/...}，git 找不到就吐一行
 * "could not open directory" 到 stdout，**退出码仍然是 0**——于是工具把警告当成
 * status 字段回给了模型。模型看到的是"status: warning: could not open directory"，
 * 完全不知道自己的改动有多大。修法是 -C 一律用仓库根。</p>
 */
class CodeFilesToolGitTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static boolean gitWorks(Path dir) {
        try {
            Process p = new ProcessBuilder("git", "--version")
                    .directory(dir.toFile()).redirectErrorStream(true).start();
            p.waitFor(5, TimeUnit.SECONDS);
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> cmd = new java.util.ArrayList<>();
        cmd.add("git");
        cmd.add("-C");
        cmd.add(dir.toString());
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertTrue(p.waitFor(20, TimeUnit.SECONDS), "git 命令超时：" + String.join(" ", args));
        assertEquals(0, p.exitValue(), "git 失败：" + String.join(" ", args));
    }

    private static JsonNode call(ToolRegistry registry, String tool, String json) {
        try {
            return MAPPER.readTree(registry.invoke(tool, json));
        } catch (Exception e) {
            throw new AssertionError("工具 " + tool + " 返回的不是 JSON", e);
        }
    }

    @Test
    @DisplayName("在 git 仓库里改文件：返回真实增删行数，且不把警告当 status")
    void gitStateAfterEdit(@TempDir Path root) throws Exception {
        Assumptions.assumeTrue(gitWorks(root), "本机没有 git，跳过");

        Files.writeString(root.resolve("A.java"),
                "class A {\n    int a = 1;\n}\n", StandardCharsets.UTF_8);
        git(root, "init");
        git(root, "config", "user.email", "test@example.com");
        git(root, "config", "user.name", "test");
        git(root, "add", "-A");
        git(root, "commit", "-m", "baseline");

        ToolRegistry registry = ToolRegistry.of(CodeTools.all(
                new CodeWorkspace(root), 60,
                com.example.agentcore.tool.db.DbTool.fromConfig(), true));

        String sha = call(registry, "read_file", "{\"path\":\"A.java\"}").path("sha256").asText();
        JsonNode edited = call(registry, "edit_file", "{\"path\":\"A.java\","
                + "\"oldText\":\"    int a = 1;\",\"newText\":\"    int a = 2;\","
                + "\"expectedSha256\":\"" + sha + "\"}");

        assertTrue(edited.path("ok").asBoolean(), edited.toString());
        JsonNode git = edited.path("git");
        assertTrue(git.isObject(), "改完必须回显 git 状态，否则模型不知道改了多大：" + edited);
        assertEquals("1", git.path("added").asText(), git.toString());
        assertEquals("1", git.path("deleted").asText(), git.toString());
        String status = git.path("status").asText();
        assertFalse(status.contains("could not open directory"),
                "status 里不该出现 git 的路径警告：" + status);

        // 新建文件（untracked）也要能显示出来
        JsonNode created = call(registry, "write_file",
                "{\"path\":\"B.java\",\"content\":\"class B {}\"}");
        assertEquals("?? B.java", created.path("git").path("status").asText(), created.toString());
    }
}
