package com.example.agentcore;

import com.example.agentcore.agent.ChatAgent;
import com.example.agentcore.messaging.AgentEvent;
import com.example.agentcore.runtime.AgentId;
import com.example.agentcore.runtime.AgentRuntime;
import com.example.agentcore.support.MockChatServer;
import com.example.agentcore.support.ModelConfig;
import com.example.agentcore.team.RoundRobinTeam;
import com.example.agentcore.team.TaskResult;
import com.example.agentcore.tool.code.CodeTools;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 参数写错不该把工具禁掉。
 *
 * <p>实测事故：demo-project 的源文件是 CRLF，模型用 {@code \n} 拼 {@code old_text}，
 * 连续两次对不上就把 {@code edit_file} 熔断了——模型接下来只能写 Python 脚本按字节改文件，
 * 工作目录里还留下两个删不掉的临时脚本。</p>
 *
 * <p>现在参数级失败走 {@code RetryableToolError} → {@code retryable=true} 的 JSON，
 * 不进熔断计数。这里钉住：连着失败 3 次，工具仍然在。</p>
 */
class RetryableToolFailureTest {

    @Test
    @DisplayName("同一个工具连续 3 次参数错，仍然可用（熔断只认 ERROR）")
    void retryableFailuresDoNotTripBreaker(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("A.java"), "class A {\r\n    int a = 1;\r\n}\r\n",
                StandardCharsets.UTF_8);

        try (MockChatServer mock = MockChatServer.start()) {
            // 三次都缺 expected_sha256，都属于"改了参数就能成功"
            for (int i = 0; i < 3; i++) {
                mock.enqueueToolCall("edit_file",
                        "{\"path\":\"A.java\",\"oldText\":\"    int a = " + i + ";\",\"newText\":\"x\"}");
            }
            mock.enqueueText("改不动，我先停下。DONE");

            AgentRuntime runtime = new AgentRuntime();
            AgentId coder = runtime.register("coder", "coder",
                    ChatAgent.builder("coder", ModelConfig.forMock(mock.baseUrl()).newChatModel())
                            .systemMessage("你是编码工程师")
                            .tools(CodeTools.all(new com.example.agentcore.tool.code.CodeWorkspace(root),
                                    60, com.example.agentcore.tool.db.DbTool.fromConfig(), true))
                            .maxToolIterations(10)
                            .build());

            TaskResult result = new RoundRobinTeam(runtime, List.of(coder))
                    .maxRounds(1)
                    .run("把 a 改成 2");

            List<String> results = result.events().stream()
                    .filter(e -> e instanceof AgentEvent.ToolExecuted)
                    .map(e -> ((AgentEvent.ToolExecuted) e).result())
                    .toList();

            assertEquals(3, results.size(), "三次调用都应该真的执行了：" + results);
            for (String r : results) {
                assertTrue(r.contains("\"retryable\":true"), "应回可重试失败而不是 ERROR：" + r);
                assertTrue(r.contains("expected_sha256"), r);
                assertFalse(r.contains("未注册的工具"), "工具不该被熔断移除：" + r);
            }
            // 文件一个字都没动，这是对的：没拿到哈希就不许写
            assertEquals("class A {\r\n    int a = 1;\r\n}\r\n", Files.readString(root.resolve("A.java")));
        }
    }
}
