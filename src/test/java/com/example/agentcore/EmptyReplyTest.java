package com.example.agentcore;

import com.example.agentcore.agent.ChatAgent;
import com.example.agentcore.messaging.ChatMsg;
import com.example.agentcore.runtime.AgentId;
import com.example.agentcore.runtime.AgentRuntime;
import com.example.agentcore.support.MockChatServer;
import com.example.agentcore.support.ModelConfig;
import com.example.agentcore.team.RoundRobinTeam;
import com.example.agentcore.team.TaskResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 小模型（实测 qwen2.5:7b）在「上下文里已经有答案」时会回一条空消息：既没有文本，也没有工具调用。
 *
 * <p>之前这种回复落进了「已达工具调用上限 12 次」的兜底文案，把排查方向带偏——实际一次工具都没调。
 * 这里锁住两条行为：空回复要催一次重试；真拿不到内容时也不能再谎报工具上限。</p>
 */
class EmptyReplyTest {

    @Test
    void emptyReplyIsRetriedInsteadOfReportedAsToolLimit() throws Exception {
        try (MockChatServer mock = MockChatServer.start()) {
            // 第一次给空回复，第二次给真答案——对应「催一句就能接着说」的常见情况
            mock.enqueueText("");
            mock.enqueueText("岳麓山在长沙市岳麓区，经度 112.936，纬度 28.184。");

            AgentRuntime runtime = new AgentRuntime();
            AgentId agent = runtime.register("planner", "travel",
                    ChatAgent.builder("travel", ModelConfig.forMock(mock.baseUrl()).newChatModel(0.0))
                            .systemMessage("你是旅游规划师")
                            .build());

            TaskResult result = new RoundRobinTeam(runtime, List.of(agent))
                    .maxRounds(1)
                    .run("岳麓山在哪");

            String reply = lastAgentText(result, "travel");

            assertTrue(reply.contains("112.936"),
                    "空回复后应被催促重试并拿到真答案，实际拿到: " + reply);
            assertFalse(reply.contains("工具调用达到上限"),
                    "空回复不是工具上限，不该这么报: " + reply);
            assertEquals(2, mock.requestCount(),
                    "空回复后应再请求一次模型，实际请求次数: " + mock.requestCount());

            // 空消息一旦进了历史，Ollama 会整条请求拒收（invalid message content type: <nil>），
            // 所以必须断言第二次请求里没有 content 为空的 assistant 消息。
            JsonNode messages = new ObjectMapper().readTree(mock.lastRequest()).path("messages");
            for (JsonNode message : messages) {
                if ("assistant".equals(message.path("role").asText())) {
                    assertFalse(message.path("content").isNull(),
                            "空内容不能进历史，Ollama 会拒收：" + message);
                }
            }
        }
    }

    /** 连续两次空回复时，文案要如实说明是模型没产出，而不是工具调太多。 */
    @Test
    void repeatedEmptyReplyIsReportedAsModelIssue() throws Exception {
        try (MockChatServer mock = MockChatServer.start()) {
            mock.enqueueText("");
            mock.enqueueText("");

            AgentRuntime runtime = new AgentRuntime();
            AgentId agent = runtime.register("planner", "travel",
                    ChatAgent.builder("travel", ModelConfig.forMock(mock.baseUrl()).newChatModel(0.0))
                            .systemMessage("你是旅游规划师")
                            .build());

            TaskResult result = new RoundRobinTeam(runtime, List.of(agent))
                    .maxRounds(1)
                    .run("岳麓山在哪");

            String reply = lastAgentText(result, "travel");

            assertTrue(reply.contains("空回复"), "应说明是模型返回空回复: " + reply);
            assertFalse(reply.contains("工具调用达到上限"), "不能栽给工具上限: " + reply);
            assertEquals(2, mock.requestCount(), "只催一次，不无限重试: " + mock.requestCount());
        }
    }

    private static String lastAgentText(TaskResult result, String source) {
        String text = "";
        for (ChatMsg m : result.messages()) {
            if (m instanceof ChatMsg.Text t && source.equals(t.source())) {
                text = t.text();
            }
        }
        return text;
    }
}
