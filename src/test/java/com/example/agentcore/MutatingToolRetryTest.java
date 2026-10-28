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

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "改完再跑一遍"必须放行。
 *
 * <p>这是实测踩出来的坑：编码场景里最正常的节奏就是 {@code 编译→报错→改代码→再编译}，
 * 而第二次编译的命令**一个字都没变**。{@link ChatAgent} 的重复调用拦截原本只认参数指纹，
 * 于是把第二次编译当成重复请求挡掉了——模型只好给命令加个假参数（{@code java -cp out Main run1}）
 * 来绕过，白烧 6 次调用，还在最终回答里写了一堆"工具拒绝了重复调用"。</p>
 *
 * <p>修法是给状态记版本号：写文件的工具成功执行后版本号加一，指纹里记着当时的版本，
 * 版本变过就放行。这里钉住三件事：真重复要拦、改过之后要放、改的差异要真的生效。</p>
 */
class MutatingToolRetryTest {

    private static List<AgentEvent.ToolExecuted> run(Path workspace, MockChatServer mock)
            throws Exception {
        AgentRuntime runtime = new AgentRuntime();
        AgentId coder = runtime.register("coder", "coder",
                ChatAgent.builder("coder", ModelConfig.forMock(mock.baseUrl()).newChatModel())
                        .systemMessage("你是编码工程师")
                        // 这里测的是"同参去重 + 版本号"，与 sha256 写保护无关：
                        // 打开写保护会让 mock 里那几个固定 JSON 缺 expected_sha256 而全部失败，
                        // 反而测不到去重逻辑。写保护单独有 CodeToolsTest 覆盖。
                        .tools(CodeTools.allWithoutWriteProtect(workspace))
                        .maxToolIterations(10)
                        .build());

        TaskResult result = new RoundRobinTeam(runtime, List.of(coder))
                .maxRounds(1)
                .run("把 a.txt 改成 v2，然后读一下确认");

        return result.events().stream()
                .filter(e -> e instanceof AgentEvent.ToolExecuted)
                .map(e -> (AgentEvent.ToolExecuted) e)
                .collect(Collectors.toList());
    }

    @Test
    @DisplayName("同参重复读被拦；改文件之后同参读放行，且读到的是新内容")
    void sameArgsAllowedAgainAfterMutation(@TempDir Path workspace) throws Exception {
        try (MockChatServer mock = MockChatServer.start()) {
            mock.enqueueToolCall("write_file", "{\"path\":\"a.txt\",\"content\":\"v1\"}");
            // 第一次读：真的执行
            mock.enqueueToolCall("read_file", "{\"path\":\"a.txt\"}");
            // 第二次读：参数完全一样，期间没有任何改动 → 该拦
            mock.enqueueToolCall("read_file", "{\"path\":\"a.txt\"}");
            // 改内容（有副作用）→ 状态版本号变化
            mock.enqueueToolCall("edit_file",
                    "{\"path\":\"a.txt\",\"oldText\":\"v1\",\"newText\":\"v2\"}");
            // 第三次读：参数与前两次一样，但文件变了 → 必须真的执行
            mock.enqueueToolCall("read_file", "{\"path\":\"a.txt\"}");
            mock.enqueueText("已改成 v2。DONE");

            List<AgentEvent.ToolExecuted> executed = run(workspace, mock);
            assertEquals(5, executed.size(), "五个请求都应留下事件：" + executed.size());

            assertTrue(executed.get(1).result().contains("v1"), executed.get(1).result());
            assertTrue(executed.get(2).result().contains("重复调用"),
                    "期间没改动，同参读应被拦：" + executed.get(2).result());
            assertEquals(0L, executed.get(2).millis(), "被拦的调用不该真的耗时");
            assertTrue(executed.get(4).result().contains("v2"),
                    "改过之后同参读必须真的执行，并读到新内容：" + executed.get(4).result());
        }
    }

    @Test
    @DisplayName("写文件本身也算改动：连着写两次同样的内容，第二次应被拦")
    void identicalWriteIsStillBlocked(@TempDir Path workspace) throws Exception {
        try (MockChatServer mock = MockChatServer.start()) {
            mock.enqueueToolCall("write_file", "{\"path\":\"b.txt\",\"content\":\"same\"}");
            mock.enqueueToolCall("write_file", "{\"path\":\"b.txt\",\"content\":\"same\"}");
            mock.enqueueText("写好了。DONE");

            List<AgentEvent.ToolExecuted> executed = run(workspace, mock);
            assertEquals(2, executed.size());
            assertTrue(executed.get(1).result().contains("重复调用"),
                    "同样的内容写第二遍是纯浪费：" + executed.get(1).result());
        }
    }
}
