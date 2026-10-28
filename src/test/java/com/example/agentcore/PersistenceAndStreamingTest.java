package com.example.agentcore;

import com.example.agentcore.agent.ChatAgent;
import com.example.agentcore.messaging.AgentEvent;
import com.example.agentcore.messaging.ChatMsg;
import com.example.agentcore.runtime.AgentId;
import com.example.agentcore.runtime.AgentRuntime;
import com.example.agentcore.runtime.SessionState;
import com.example.agentcore.support.MockChatServer;
import com.example.agentcore.support.ModelConfig;
import com.example.agentcore.support.TeamSnapshot;
import com.example.agentcore.team.RoundRobinTeam;
import com.example.agentcore.team.TaskResult;
import com.example.agentcore.team.Team;
import com.example.agentcore.termination.StopCondition;
import com.example.agentcore.tool.CalculatorTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证两块新增能力：流式输出（增量事件 + 工具调用）与快照续跑（含"历史不误触发终止"）。
 */
class PersistenceAndStreamingTest {

    // ---------- 流式 ----------

    /** 流式：增量事件逐片到达，拼接结果与最终落库文本一致。 */
    @Test
    void streamingEmitsTextDeltasAndMatchesFinalText() throws Exception {
        try (MockChatServer mock = MockChatServer.start()) {
            String answer = "流式输出测试：这句话会被切成多片逐步送达客户端。";
            mock.enqueueText(answer);

            AgentRuntime runtime = new AgentRuntime();
            AgentId coder = runtime.register("engineer", "coder",
                    ChatAgent.streamingBuilder("coder",
                                    ModelConfig.forMock(mock.baseUrl()).newStreamingChatModel(0.0))
                            .systemMessage("你是工程师")
                            .build());

            TaskResult result = new RoundRobinTeam(runtime, List.of(coder))
                    .onStop(StopCondition.textMention("DONE"))
                    .maxRounds(1)
                    .run("随便说点什么");

            List<String> deltas = result.events().stream()
                    .filter(e -> e instanceof AgentEvent.TextDelta)
                    .map(e -> ((AgentEvent.TextDelta) e).delta())
                    .collect(Collectors.toList());

            assertTrue(deltas.size() > 1, "应收到多个增量分片，实际: " + deltas.size());
            assertEquals(answer, String.join("", deltas), "增量拼接应等于完整文本");
            assertEquals(answer, result.lastText(), "最终消息仍以完整响应为准");

            // 事件顺序：开始 → 若干增量 → 结束
            assertTrue(result.events().get(0) instanceof AgentEvent.SpeakingStarted);
            assertTrue(result.events().get(result.events().size() - 1)
                    instanceof AgentEvent.SpeakingFinished);
        }
    }

    /** 流式 + 工具调用：分片的 tool_calls 被聚合成一次调用，工具结果照常回灌。 */
    @Test
    void streamingStillRunsToolLoop() throws Exception {
        try (MockChatServer mock = MockChatServer.start()) {
            mock.enqueueToolCall("multiply", "{\"a\":6,\"b\":7}");
            mock.enqueueText("结果是 42。DONE");

            AgentRuntime runtime = new AgentRuntime();
            AgentId coder = runtime.register("engineer", "coder",
                    ChatAgent.streamingBuilder("coder",
                                    ModelConfig.forMock(mock.baseUrl()).newStreamingChatModel(0.0))
                            .systemMessage("你是工程师，需要计算时调用工具。")
                            .tools(new CalculatorTool())
                            .build());

            TaskResult result = new RoundRobinTeam(runtime, List.of(coder))
                    .onStop(StopCondition.textMention("DONE"))
                    .maxRounds(2)
                    .run("算一下 6 乘 7");

            List<AgentEvent.ToolExecuted> executed = result.events().stream()
                    .filter(e -> e instanceof AgentEvent.ToolExecuted)
                    .map(e -> (AgentEvent.ToolExecuted) e)
                    .collect(Collectors.toList());

            assertEquals(1, executed.size(), "流式下也应执行一次工具");
            assertEquals("42", executed.get(0).result());
            assertTrue(mock.lastRequest().contains("42"), "工具结果必须回灌到下一次请求");
            assertTrue(result.lastText().contains("42"));
        }
    }

    // ---------- 持久化续跑 ----------

    /** 快照往返：消息、轮次、停止原因完整保留；流式碎片不入快照。 */
    @Test
    void snapshotRoundTrips(@TempDir Path dir) throws Exception {
        try (MockChatServer mock = MockChatServer.start()) {
            mock.enqueueText("第一段回答，长度够切几片。");

            AgentRuntime runtime = new AgentRuntime();
            AgentId coder = runtime.register("engineer", "coder",
                    ChatAgent.streamingBuilder("coder",
                                    ModelConfig.forMock(mock.baseUrl()).newStreamingChatModel(0.0))
                            .systemMessage("你是工程师")
                            .build());
            Team team = new RoundRobinTeam(runtime, List.of(coder))
                    .onStop(StopCondition.never())
                    .maxRounds(1);
            TaskResult result = team.run("任务");

            Path file = TeamSnapshot.write(dir, "RoundRobinTeam", team.snapshot(result));
            SessionState restored = TeamSnapshot.read(file);

            assertEquals(result.messages().size(), restored.messageCount());
            assertEquals(result.round(), restored.round());
            assertEquals(result.stopReason(), restored.lastStopReason());
            assertEquals("任务", restored.messages().get(0).text());

            long deltasInResult = result.events().stream()
                    .filter(e -> e instanceof AgentEvent.TextDelta).count();
            long deltasInSnapshot = restored.events().stream()
                    .filter(e -> e instanceof AgentEvent.TextDelta).count();
            assertTrue(deltasInResult > 0, "运行时应产生流式碎片");
            assertEquals(0, deltasInSnapshot, "流式碎片不应落盘");
        }
    }

    /** 续跑：接上轮次与上下文继续跑，消息累加而不是重来。 */
    @Test
    void resumeContinuesFromSnapshot(@TempDir Path dir) throws Exception {
        try (MockChatServer mock = MockChatServer.start()) {
            mock.enqueueText("初版方案：同步导出。");
            mock.enqueueText("意见：缺少异步任务设计。");

            // 第一段：跑满 2 轮，未命中终止词
            AgentRuntime first = new AgentRuntime();
            Team firstTeam = new RoundRobinTeam(first, members(first, mock))
                    .onStop(StopCondition.textMention("APPROVE"))
                    .maxRounds(2);
            TaskResult part1 = firstTeam.run("做一个导出功能");

            assertEquals(3, part1.messages().size(), "任务 + 两轮发言");
            assertEquals(2, part1.round());
            assertFalse(part1.hitStopCondition(), "应停在轮次上限");

            Path file = TeamSnapshot.write(dir, "RoundRobinTeam", firstTeam.snapshot(part1));

            // 第二段：全新的 runtime 与 team，只靠快照恢复
            mock.enqueueText("修订版：改异步任务表。APPROVE");
            AgentRuntime second = new AgentRuntime();
            Team secondTeam = new RoundRobinTeam(second, members(second, mock))
                    .onStop(StopCondition.textMention("APPROVE"))
                    .maxRounds(4);
            TaskResult part2 = secondTeam.resume(TeamSnapshot.read(file));

            assertEquals(4, part2.messages().size(), "应在原有 3 条基础上追加，而不是重来");
            assertEquals(3, part2.round(), "轮次应接着数");
            assertTrue(part2.stopReason().contains("APPROVE"));

            List<String> sources = part2.messages().stream()
                    .map(ChatMsg::source).collect(Collectors.toList());
            assertEquals(List.of("user", "planner", "reviewer", "planner"), sources,
                    "续跑应由第三位成员接着发言（轮次 3 → members[2 % 2] = planner）");
        }
    }

    /** 续跑的关键陷阱：历史里的 APPROVE 不能让条件立刻命中。 */
    @Test
    void restoredHistoryDoesNotRetriggerTextMention() throws Exception {
        try (MockChatServer mock = MockChatServer.start()) {
            mock.enqueueText("继续讨论一下监控指标。");

            SessionState history = new SessionState(
                    List.of(new ChatMsg.Text("user", "评审一下方案"),
                            new ChatMsg.Text("reviewer", "方案可行。APPROVE")),
                    List.of(),
                    1, "命中终止词 APPROVE（来自 reviewer）", 0L);

            AgentRuntime runtime = new AgentRuntime();
            Team team = new RoundRobinTeam(runtime, members(runtime, mock))
                    .onStop(StopCondition.textMention("APPROVE"))
                    .maxRounds(2);
            TaskResult result = team.resume(history);

            assertEquals(2, result.round(), "历史轮次 1 + 本次 1 轮");
            assertEquals(3, result.messages().size(), "历史 2 条 + 本次 1 条");
            assertFalse(result.stopReason().contains("APPROVE"),
                    "不能因为历史里的 APPROVE 立刻停止，实际: " + result.stopReason());
        }
    }

    /** 同一陷阱的另一面：历史里的工具调用不能让 toolCalled 立刻命中。 */
    @Test
    void restoredHistoryDoesNotRetriggerToolCalled() throws Exception {
        try (MockChatServer mock = MockChatServer.start()) {
            mock.enqueueText("继续。");

            SessionState history = new SessionState(
                    List.of(new ChatMsg.Text("user", "算一下")),
                    List.of(new AgentEvent.ToolExecuted("coder", "multiply", "42", 3L)),
                    1, "", 0L);

            AgentRuntime runtime = new AgentRuntime();
            Team team = new RoundRobinTeam(runtime, members(runtime, mock))
                    .onStop(StopCondition.toolCalled("multiply"))
                    .maxRounds(2);
            TaskResult result = team.resume(history);

            assertEquals(2, result.messages().size());
            assertFalse(result.stopReason().contains("multiply"),
                    "历史工具调用不应触发停止，实际: " + result.stopReason());
        }
    }

    /** 续跑可追加新指令，等同于"接着上次，再补一个要求"。 */
    @Test
    void resumeWithNewInstruction(@TempDir Path dir) throws Exception {
        try (MockChatServer mock = MockChatServer.start()) {
            mock.enqueueText("初版。");
            AgentRuntime first = new AgentRuntime();
            Team firstTeam = new RoundRobinTeam(first, members(first, mock))
                    .maxRounds(1);
            Path file = TeamSnapshot.write(dir, "rr", firstTeam.snapshot(firstTeam.run("先做初版")));

            mock.enqueueText("已补充监控。");
            AgentRuntime second = new AgentRuntime();
            Team secondTeam = new RoundRobinTeam(second, members(second, mock))
                    .maxRounds(3);
            TaskResult result = secondTeam.resume(TeamSnapshot.read(file), "再补上监控告警");

            List<String> texts = result.messages().stream()
                    .map(ChatMsg::text).collect(Collectors.toList());
            assertTrue(texts.contains("再补上监控告警"), "新指令应进入共享上下文");
            assertEquals("再补上监控告警", result.messages().get(2).text(),
                    "新指令应追加在历史之后、本轮发言之前");
            assertEquals(5, result.messages().size(),
                    "历史 2 条 + 新指令 1 条 + 续跑 2 轮发言");
        }
    }

    private List<AgentId> members(AgentRuntime runtime, MockChatServer mock) {
        ModelConfig config = ModelConfig.forMock(mock.baseUrl());
        return List.of(
                runtime.register("pm", "planner", ChatAgent.builder("planner", config.newChatModel(0.0))
                        .systemMessage("你是产品经理").build()),
                runtime.register("reviewer", "reviewer", ChatAgent.builder("reviewer", config.newChatModel(0.0))
                        .systemMessage("你是评审人").build()));
    }
}
