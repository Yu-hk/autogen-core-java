package com.example.agentcore;

import com.example.agentcore.agent.ChatAgent;
import com.example.agentcore.messaging.ChatMsg;
import com.example.agentcore.runtime.AgentId;
import com.example.agentcore.runtime.AgentRuntime;
import com.example.agentcore.support.MockChatServer;
import com.example.agentcore.support.ModelConfig;
import com.example.agentcore.team.RoundRobinTeam;
import com.example.agentcore.team.TaskResult;
import com.example.agentcore.tool.CalculatorTool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「只写计划不干活」的兜底纠偏。
 *
 * <p>回归的是内网那一轮：qwen2.5:3b 调了一次 list_files，把"接下来，我将分析 frequencyAnaly
 * 目录下的内容"当成最终回答交卷，整轮结束，用户看到的就是分析做到一半没有下文。
 * 提示词已经加了硬约束，但小模型并不总听话，所以进程内还要有一层：识别出这种收尾后，
 * 追一句"现在就去做"，且只追有限次。</p>
 */
class PromiseNudgeTest {

    @Test
    void promiseOnlyReplyIsNudgedIntoContinuing() throws Exception {
        try (MockChatServer mock = MockChatServer.start()) {
            mock.enqueueToolCall("multiply", "{\"a\":6,\"b\":7}");
            // 第一次收尾只有计划，没有结论 —— 触发纠偏
            mock.enqueueText("接下来，我将再核对一遍 6 乘 8 的结果。");
            // 被催之后真给了结论
            mock.enqueueText("6 乘 7 等于 42。");

            AgentRuntime runtime = new AgentRuntime();
            AgentId coder = register(runtime, mock);
            TaskResult result = new RoundRobinTeam(runtime, List.of(coder))
                    .maxRounds(1)
                    .run("算一下 6 乘 7");

            assertEquals(3, mock.requestCount(),
                    "计划句之后应再请求一次模型，实际: " + mock.requestCount());
            assertTrue(lastAgentText(result).contains("42"),
                    "应拿到真结论，而不是停在计划上: " + lastAgentText(result));
            assertTrue(mock.lastRequest().contains("[系统]"),
                    "纠偏提示应作为一条消息进入上下文");
        }
    }

    /** 正常给结论的回答不能被拽回去重做——多一次请求就等于让用户多等几分钟。 */
    @Test
    void completeAnswerIsNotNudged() throws Exception {
        try (MockChatServer mock = MockChatServer.start()) {
            mock.enqueueToolCall("multiply", "{\"a\":6,\"b\":7}");
            mock.enqueueText("6 乘 7 等于 42。");

            AgentRuntime runtime = new AgentRuntime();
            AgentId coder = register(runtime, mock);
            TaskResult result = new RoundRobinTeam(runtime, List.of(coder))
                    .maxRounds(1)
                    .run("算一下 6 乘 7");

            assertEquals(2, mock.requestCount(),
                    "已经给出结论就不该再请求，实际: " + mock.requestCount());
            assertTrue(lastAgentText(result).contains("42"), lastAgentText(result));
        }
    }

    /** 模型一直只写计划时不能无限追：追满两次就收工，把原话交给用户。 */
    @Test
    void nudgesAreBounded() throws Exception {
        try (MockChatServer mock = MockChatServer.start()) {
            mock.enqueueToolCall("multiply", "{\"a\":6,\"b\":7}");
            for (int i = 0; i < 6; i++) {
                mock.enqueueText("接下来，我将继续分析这个结果。");
            }

            AgentRuntime runtime = new AgentRuntime();
            AgentId coder = register(runtime, mock);
            TaskResult result = new RoundRobinTeam(runtime, List.of(coder))
                    .maxRounds(1)
                    .run("算一下 6 乘 7");

            assertEquals(4, mock.requestCount(),
                    "最多追两次：1 次工具 + 首条计划 + 2 次追问，实际: " + mock.requestCount());
            assertTrue(lastAgentText(result).contains("接下来"),
                    "追满之后把模型原话交出去，不要编一个假结论: " + lastAgentText(result));
        }
    }

    private static AgentId register(AgentRuntime runtime, MockChatServer mock) {
        return runtime.register("engineer", "coder",
                ChatAgent.builder("coder", ModelConfig.forMock(mock.baseUrl()).newChatModel())
                        .systemMessage("你是工程师")
                        .tools(new CalculatorTool())
                        .maxToolIterations(6)
                        .build());
    }

    private static String lastAgentText(TaskResult result) {
        String text = "";
        for (ChatMsg m : result.messages()) {
            if (m instanceof ChatMsg.Text t && "coder".equals(t.source())) {
                text = t.text();
            }
        }
        return text;
    }
}
