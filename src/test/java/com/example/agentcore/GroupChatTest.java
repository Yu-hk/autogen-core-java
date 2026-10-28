package com.example.agentcore;

import com.example.agentcore.agent.ChatAgent;
import com.example.agentcore.agent.EchoAgent;
import com.example.agentcore.messaging.AgentEvent;
import com.example.agentcore.messaging.ChatMsg;
import com.example.agentcore.messaging.Topic;
import com.example.agentcore.runtime.AgentId;
import com.example.agentcore.runtime.AgentRuntime;
import com.example.agentcore.support.ModelConfig;
import com.example.agentcore.support.MockChatServer;
import com.example.agentcore.team.RoundRobinTeam;
import com.example.agentcore.team.SelectorTeam;
import com.example.agentcore.team.TaskResult;
import com.example.agentcore.team.Team;
import com.example.agentcore.termination.StopCondition;
import com.example.agentcore.tool.CalculatorTool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 用本地 Mock 验证 AutoGen 的四条核心机制在 Java 实现里是否真的成立。 */
class GroupChatTest {

    /** 机制一：轮询发言人 + 文本终止条件。 */
    @Test
    void roundRobinStopsOnTextMention() throws Exception {
        try (MockChatServer mock = MockChatServer.start()) {
            mock.enqueueText("初版方案：同步导出。");
            mock.enqueueText("方案可行。APPROVE");

            AgentRuntime runtime = new AgentRuntime();
            List<AgentId> members = List.of(
                    runtime.register("pm", "planner", agent("planner", mock, "你是产品经理")),
                    runtime.register("reviewer", "reviewer", agent("reviewer", mock, "你是评审人")));

            Team team = new RoundRobinTeam(runtime, members)
                    .onStop(StopCondition.textMention("APPROVE").or(StopCondition.maxMessages(6)))
                    .maxRounds(4);

            TaskResult result = team.run("设计一个导出方案");

            assertEquals(3, result.messages().size(), "任务消息 + 两轮发言");
            assertEquals(List.of("user", "planner", "reviewer"),
                    result.messages().stream().map(ChatMsg::source).collect(Collectors.toList()));
            assertTrue(result.stopReason().contains("APPROVE"), "应命中文本终止条件，实际: " + result.stopReason());
        }
    }

    /** 机制二：工具调用的事件流，且执行结果被回灌给模型。 */
    @Test
    void toolCallLoopEmitsEventsAndFeedsResultBack() throws Exception {
        try (MockChatServer mock = MockChatServer.start()) {
            mock.enqueueToolCall("multiply", "{\"a\":6,\"b\":7}");
            mock.enqueueText("6 乘 7 等于 42。DONE");

            AgentRuntime runtime = new AgentRuntime();
            AgentId coder = runtime.register("engineer", "coder",
                    ChatAgent.builder("coder", ModelConfig.forMock(mock.baseUrl()).newChatModel())
                            .systemMessage("你是工程师，需要计算时调用工具。")
                            .tools(new CalculatorTool())
                            .build());

            Team team = new RoundRobinTeam(runtime, List.of(coder))
                    .onStop(StopCondition.textMention("DONE"))
                    .maxRounds(3);
            TaskResult result = team.run("算一下 6 乘 7");

            List<AgentEvent.ToolCallRequested> requested = result.events().stream()
                    .filter(e -> e instanceof AgentEvent.ToolCallRequested)
                    .map(e -> (AgentEvent.ToolCallRequested) e)
                    .collect(Collectors.toList());
            List<AgentEvent.ToolExecuted> executed = result.events().stream()
                    .filter(e -> e instanceof AgentEvent.ToolExecuted)
                    .map(e -> (AgentEvent.ToolExecuted) e)
                    .collect(Collectors.toList());

            assertEquals(1, requested.size(), "应发出一次工具请求事件");
            assertEquals("multiply", requested.get(0).tool());
            assertEquals(1, executed.size(), "应发出一次工具执行事件");
            assertEquals("42", executed.get(0).result());

            assertTrue(mock.requestCount() >= 2, "工具调用后应再次请求模型");
            assertTrue(mock.lastRequest().contains("42"), "工具结果必须回灌到下一次请求里");
        }
    }

    /** 机制三：无订阅的 topic 静默丢弃，订阅后才真正投递。 */
    @Test
    void publishWithoutSubscriptionIsDropped() throws Exception {
        AgentRuntime runtime = new AgentRuntime();
        // TypeSubscription 的 agent key 取自 topic source，用简写注册时 key 为 default
        runtime.register("auditor", new EchoAgent("auditor"));

        runtime.publish(Topic.of("audit"), new ChatMsg.Text("planner", "无订阅"));
        assertEquals(1, runtime.droppedMessages(), "无订阅应被静默丢弃并计数");
        assertEquals(0, runtime.drain());
        assertEquals(0, runtime.transcript().size());

        runtime.subscribe("audit", "auditor");
        runtime.publish(Topic.of("audit"), new ChatMsg.Text("planner", "已订阅"));
        assertEquals(1, runtime.droppedMessages(), "丢弃计数不应再增加");
        assertEquals(1, runtime.drain());
        assertEquals(1, runtime.transcript().size());
    }

    /** 机制三补充：多租户下目标 agent 不存在时按工厂自动创建。 */
    @Test
    void typeSubscriptionAutoCreatesAgentPerSource() throws Exception {
        AgentRuntime runtime = new AgentRuntime();
        runtime.registerFactory("auditor", id -> new EchoAgent("auditor-" + id.key()));
        runtime.subscribe("audit", "auditor");

        runtime.publish(Topic.of("audit", "tenant-a"), new ChatMsg.Text("planner", "租户 A 的事件"));
        runtime.publish(Topic.of("audit", "tenant-b"), new ChatMsg.Text("planner", "租户 B 的事件"));
        assertEquals(2, runtime.drain());
        assertEquals(2, runtime.agentCount(), "两个 source 应各自实例化一个 agent");

        EchoAgent a = (EchoAgent) runtime.find(AgentId.of("auditor", "tenant-a")).orElseThrow();
        EchoAgent b = (EchoAgent) runtime.find(AgentId.of("auditor", "tenant-b")).orElseThrow();
        assertEquals(1, a.received().size());
        assertEquals(1, b.received().size());
        assertTrue(a.received().get(0).contains("租户 A"));
    }

    /** 机制四：由模型决定下一位发言人。 */
    @Test
    void selectorTeamUsesModelChoice() throws Exception {
        try (MockChatServer mock = MockChatServer.start()) {
            mock.enqueueSelector("reviewer");
            mock.enqueueText("同意上线。APPROVE");

            AgentRuntime runtime = new AgentRuntime();
            List<AgentId> members = List.of(
                    runtime.register("pm", "planner", agent("planner", mock, "你是产品经理")),
                    runtime.register("reviewer", "reviewer", agent("reviewer", mock, "你是评审人")));

            Team team = new SelectorTeam(runtime, members,
                    ModelConfig.forMock(mock.baseUrl()).newChatModel(0.0))
                    .onStop(StopCondition.textMention("APPROVE"))
                    .maxRounds(3);
            TaskResult result = team.run("能否上线");

            String firstSpeaker = result.events().stream()
                    .filter(e -> e instanceof AgentEvent.SpeakingStarted)
                    .map(e -> ((AgentEvent.SpeakingStarted) e).agent())
                    .findFirst()
                    .orElse("none");
            assertEquals("reviewer", firstSpeaker, "应由模型选出 reviewer 先发言");
            assertTrue(result.stopReason().contains("APPROVE"));
            assertEquals(2, mock.requestCount(), "一次选发言人 + 一次正式发言");
        }
    }

    private ChatAgent agent(String name, MockChatServer mock, String system) {
        return ChatAgent.builder(name, ModelConfig.forMock(mock.baseUrl()).newChatModel())
                .systemMessage(system)
                .build();
    }
}
