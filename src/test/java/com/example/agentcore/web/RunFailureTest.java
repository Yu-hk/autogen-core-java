package com.example.agentcore.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 失败分类：判据是"用户下一步该做什么"，不是异常类名。
 *
 * <p>这个类看起来只是几个 if，但它决定了用户在看到错误之后会不会白点五次重发。
 * 所以每条规则都单独钉住——尤其"顺着 cause 链找"这一条，一旦退化成只看最外层类型，
 * 所有失败都会变成 {@code internal}，分类静默失效而测试仍然全绿（如果只测顶层异常的话）。</p>
 */
class RunFailureTest {

    @Test
    void modelUnavailableIsItsOwnCode() {
        assertEquals(RunFailure.MODEL_UNAVAILABLE,
                RunFailure.classify(new ModelUnavailable("连不上 http://127.0.0.1:11434，先执行 ollama serve")));
    }

    /**
     * 真实链路：{@code ChatAgent} 会把底层异常包一层 {@code IllegalStateException}。
     *
     * <p>这是最容易被漏测的形状——只测裸 {@code SocketTimeoutException} 会误以为分类能用。</p>
     */
    @Test
    void wrappedTimeoutIsStillTimeout() {
        Throwable wrapped = new IllegalStateException("writer 流式调用失败（等待响应）: request timed out",
                new SocketTimeoutException("Read timed out"));
        assertEquals(RunFailure.TIMEOUT, RunFailure.classify(wrapped));

        // 走到超时分支的不只有 Socket 那一支：StreamingChatModel 的 CompletableFuture
        // 超时抛的是 java.util.concurrent.TimeoutException
        assertEquals(RunFailure.TIMEOUT,
                RunFailure.classify(new IllegalStateException("超时", new TimeoutException("future timed out"))));
    }

    @Test
    void unansweredPortMeansModelIsDown() {
        assertEquals(RunFailure.MODEL_UNAVAILABLE, RunFailure.classify(new ConnectException("Connection refused")));
        assertEquals(RunFailure.MODEL_UNAVAILABLE,
                RunFailure.classify(new UnknownHostException("api.deepseek.com")));
    }

    @Test
    void illegalArgumentMeansTheRequestItselfIsWrong() {
        assertEquals(RunFailure.BAD_REQUEST,
                RunFailure.classify(new IllegalArgumentException("工作目录不在白名单内")));
        // 包装层是 IllegalStateException 时，仍要认里面那个参数错误
        assertEquals(RunFailure.BAD_REQUEST,
                RunFailure.classify(new IllegalStateException("恢复失败",
                        new IllegalArgumentException("快照缺少 messages 字段"))));
    }

    /** 内网模型（Ollama / vLLM）报超时的原文不统一，文案兜底要覆盖中英文两种。 */
    @Test
    void messageFallbackCoversBothLocales() {
        assertEquals(RunFailure.TIMEOUT, RunFailure.fromMessage("Request Timeout"));
        assertEquals(RunFailure.TIMEOUT, RunFailure.fromMessage("模型调用超时，已重试 3 次"));
        assertEquals(RunFailure.MODEL_UNAVAILABLE, RunFailure.fromMessage("连不上 http://10.0.0.8:8000"));
        assertEquals(RunFailure.INTERNAL, RunFailure.fromMessage("NullPointerException"));
        assertEquals(RunFailure.INTERNAL, RunFailure.fromMessage(null));
    }

    @Test
    void unrecognizedFailureIsInternal() {
        assertEquals(RunFailure.INTERNAL, RunFailure.classify(new IllegalStateException("没有可用的起始消息")));
        assertEquals(RunFailure.INTERNAL, RunFailure.classify(new RuntimeException((String) null)));
    }

    /**
     * 有些库（尤其包装过的 IO 异常）会把 cause 指回自己。
     *
     * <p>不判自环就是死循环——一次失败会把线程池里一个线程彻底卡住，
     * 现场表现是"服务偶发不再响应"，比报错难查得多。</p>
     */
    @Test
    void selfReferencingCauseTerminates() {
        Throwable loop = new IOException("自环") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        assertEquals(RunFailure.INTERNAL, RunFailure.classify(loop));
    }

    /** code 是前端选文案的键，改名等于让旧页面拿到未知值——这里钉死。 */
    @Test
    void codesAreStableStrings() {
        assertEquals("model_unavailable", RunFailure.MODEL_UNAVAILABLE.code());
        assertEquals("timeout", RunFailure.TIMEOUT.code());
        assertEquals("bad_request", RunFailure.BAD_REQUEST.code());
        assertEquals("internal", RunFailure.INTERNAL.code());
    }
}
