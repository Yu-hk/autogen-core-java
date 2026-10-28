package com.example.agentcore;

import com.example.agentcore.agent.ChatAgent;
import com.example.agentcore.messaging.AgentEvent;
import com.example.agentcore.runtime.AgentId;
import com.example.agentcore.runtime.AgentRuntime;
import com.example.agentcore.support.MockChatServer;
import com.example.agentcore.support.ModelConfig;
import com.example.agentcore.team.RoundRobinTeam;
import com.example.agentcore.team.TaskResult;
import com.example.agentcore.tool.CalculatorTool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同参重复调用的拦截。
 *
 * <p>实测 qwen2.5:7b 在多轮旅游场景里，会把 {@code get_location_coordinate("岳麓山","长沙")}
 * 用一模一样的参数连调三轮。提示词管不住，只能在这一层拦：第二次起不再真的发请求，
 * 回一条「已经查过了」，把轮次预算留给真正没做过的事。</p>
 */
class RepeatedToolCallTest {

    @Test
    void identicalCallIsNotExecutedTwice() throws Exception {
        try (MockChatServer mock = MockChatServer.start()) {
            String args = "{\"a\":6,\"b\":7}";
            mock.enqueueToolCall("multiply", args);
            mock.enqueueToolCall("multiply", args);
            mock.enqueueText("结果是 42。DONE");

            AgentRuntime runtime = new AgentRuntime();
            AgentId coder = runtime.register("engineer", "coder",
                    ChatAgent.builder("coder", ModelConfig.forMock(mock.baseUrl()).newChatModel())
                            .systemMessage("你是工程师")
                            .tools(new CalculatorTool())
                            .maxToolIterations(5)
                            .build());

            TaskResult result = new RoundRobinTeam(runtime, List.of(coder))
                    .maxRounds(1)
                    .run("算一下 6 乘 7");

            List<AgentEvent.ToolExecuted> executed = result.events().stream()
                    .filter(e -> e instanceof AgentEvent.ToolExecuted)
                    .map(e -> (AgentEvent.ToolExecuted) e)
                    .collect(Collectors.toList());

            assertEquals(2, executed.size(), "两次请求都应留下事件，第二次是拦截提示");
            assertEquals("42", executed.get(0).result().trim(), "第一次真的执行了工具");
            assertTrue(executed.get(1).result().contains("重复调用"),
                    "第二次应被拦下，实际: " + executed.get(1).result());
            assertEquals(0L, executed.get(1).millis(), "拦截的调用不该真的耗时");
        }
    }

    /**
     * 参数不同就必须放行——否则「换个关键词再查一次」这种正当重试会被误杀。
     */
    @Test
    void differentArgumentsStillExecute() throws Exception {
        try (MockChatServer mock = MockChatServer.start()) {
            mock.enqueueToolCall("multiply", "{\"a\":6,\"b\":7}");
            mock.enqueueToolCall("multiply", "{\"a\":6,\"b\":8}");
            mock.enqueueText("分别是 42 和 48。DONE");

            AgentRuntime runtime = new AgentRuntime();
            AgentId coder = runtime.register("engineer", "coder",
                    ChatAgent.builder("coder", ModelConfig.forMock(mock.baseUrl()).newChatModel())
                            .systemMessage("你是工程师")
                            .tools(new CalculatorTool())
                            .maxToolIterations(5)
                            .build());

            TaskResult result = new RoundRobinTeam(runtime, List.of(coder))
                    .maxRounds(1)
                    .run("算 6×7 和 6×8");

            List<String> results = result.events().stream()
                    .filter(e -> e instanceof AgentEvent.ToolExecuted)
                    .map(e -> ((AgentEvent.ToolExecuted) e).result().trim())
                    .collect(Collectors.toList());

            assertEquals(List.of("42", "48"), results, "参数不同应各自执行");
            assertFalse(results.stream().anyMatch(r -> r.contains("重复调用")), results.toString());
        }
    }
}
