package com.example.agentcore;

import com.example.agentcore.agent.ChatAgent;
import com.example.agentcore.messaging.AgentEvent;
import com.example.agentcore.messaging.ChatMsg;
import com.example.agentcore.runtime.AgentId;
import com.example.agentcore.runtime.AgentRuntime;
import com.example.agentcore.support.RetryPolicy;
import com.example.agentcore.team.RoundRobinTeam;
import com.example.agentcore.team.TaskResult;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型调用的重试：该重试的必须真的重试，不该重试的一次都不能多打。
 *
 * <p>这里不接 MockChatServer，而是用假模型直接抛异常——因为要验证的正是"失败之后的行为"，
 * 真端点很难稳定复现 503。</p>
 */
class ChatAgentRetryTest {

    /** 退避给 10ms：验证的是"重试了"，不是"等够了"。 */
    private static final RetryPolicy FAST = new RetryPolicy(1, 10);

    @Test
    void transientFailureIsRetriedAndSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel flaky = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                if (calls.incrementAndGet() == 1) {
                    throw new InternalServerException("503 Service Unavailable");
                }
                return ChatResponse.builder().aiMessage(AiMessage.from("第二次成功")).build();
            }
        };

        TaskResult result = runOnce(ChatAgent.builder("planner", flaky)
                .systemMessage("你是助手")
                .retryPolicy(FAST)
                .build());

        assertEquals("第二次成功", lastText(result), "重试后应拿到第二次的回复");
        assertEquals(2, calls.get(), "第一次 503、第二次成功，共请求 2 次");
        assertTrue(result.events().stream().anyMatch(e -> e instanceof AgentEvent.Retrying),
                "重试必须作为事件外抛，否则页面在重试期间和卡死没区别");
    }

    @Test
    void authenticationFailureIsNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel denied = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                calls.incrementAndGet();
                throw new AuthenticationException("401 Invalid API key");
            }
        };

        ChatAgent agent = ChatAgent.builder("planner", denied).systemMessage("你是助手").retryPolicy(FAST).build();
        assertThrows(RuntimeException.class, () -> runOnce(agent),
                "密钥错应该直接把错误抛出去，而不是重试");
        assertEquals(1, calls.get(), "401 只该打一次");
    }

    @Test
    void exhaustedRetriesSurfaceTheLastFailure() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel alwaysDown = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                calls.incrementAndGet();
                throw new InternalServerException("503 still down");
            }
        };

        ChatAgent agent = ChatAgent.builder("planner", alwaysDown).systemMessage("你是助手").retryPolicy(FAST).build();
        assertThrows(RuntimeException.class, () -> runOnce(agent));
        assertEquals(2, calls.get(), "配了 1 次重试，总共 2 次尝试后就该放弃");
    }

    /**
     * 流式已经开始输出后不重试。
     *
     * <p>这是设计上唯一放弃重试的情形：重来一遍会让同一段文字在页面上出现两次，
     * 比直接报错更让人困惑。能安全重试的只是"一个字都没吐出来就失败"。</p>
     */
    @Test
    void streamingFailureAfterPartialOutputIsNotRetried() {
        AtomicInteger attempts = new AtomicInteger();
        StreamingChatModel halfBroken = new StreamingChatModel() {
            @Override
            public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
                attempts.incrementAndGet();
                handler.onPartialResponse("这是已经显示出去的半句话");
                handler.onError(new InternalServerException("503 中途断开"));
            }
        };

        AgentRuntime runtime = new AgentRuntime();
        AgentId agent = runtime.register("planner", "planner",
                ChatAgent.streamingBuilder("planner", halfBroken)
                        .systemMessage("你是助手")
                        .retryPolicy(FAST)
                        .build());

        List<AgentEvent> events = new java.util.ArrayList<>();
        runtime.onEvent(events::add);

        assertThrows(RuntimeException.class, () -> new RoundRobinTeam(runtime, List.of(agent))
                .maxRounds(1)
                .run("随便问点什么"));

        assertEquals(1, attempts.get(), "已经推过增量就不能重来，否则页面出现两遍同样的半句话");
        assertFalse(events.stream().anyMatch(e -> e instanceof AgentEvent.Retrying),
                "这次不该发出重试事件");
    }

    @Test
    void streamingFailureBeforeAnyOutputIsRetried() {
        AtomicInteger attempts = new AtomicInteger();
        StreamingChatModel connectFail = new StreamingChatModel() {
            @Override
            public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
                if (attempts.incrementAndGet() == 1) {
                    handler.onError(new InternalServerException("503 首包前就失败"));
                    return;
                }
                handler.onCompleteResponse(
                        ChatResponse.builder().aiMessage(AiMessage.from("重试后正常")).build());
            }
        };

        AgentRuntime runtime = new AgentRuntime();
        AgentId agent = runtime.register("planner", "planner",
                ChatAgent.streamingBuilder("planner", connectFail)
                        .systemMessage("你是助手")
                        .retryPolicy(FAST)
                        .build());

        TaskResult result = new RoundRobinTeam(runtime, List.of(agent)).maxRounds(1).run("问点什么");

        assertEquals("重试后正常", lastText(result));
        assertEquals(2, attempts.get(), "一个字都没吐出来时，重试是安全的");
    }

    private static TaskResult runOnce(ChatAgent agent) {
        AgentRuntime runtime = new AgentRuntime();
        AgentId id = runtime.register("planner", "planner", agent);
        return new RoundRobinTeam(runtime, List.of(id)).maxRounds(1).run("你好");
    }

    private static String lastText(TaskResult result) {
        String text = "";
        for (ChatMsg m : result.messages()) {
            if (m instanceof ChatMsg.Text t) {
                text = t.text();
            }
        }
        return text;
    }
}
