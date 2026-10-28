package com.example.agentcore;

import com.example.agentcore.support.RetryPolicy;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.RateLimitException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重试策略的边界：什么该重试、什么不该。
 *
 * <p>这一层判错的代价是双向的——把 401 当可重试，用户要多等一个超时才知道密钥错了；
 * 把 503 当不可重试，一次上游抖动就丢掉整轮工具成果。</p>
 */
class RetryPolicyTest {

    private final RetryPolicy policy = RetryPolicy.defaults();

    @Test
    void transientFailuresAreRetried() {
        assertTrue(policy.retryable(new InternalServerException("503 Service Unavailable")),
                "5xx 是上游问题，值得再试");
        assertTrue(policy.retryable(new RateLimitException("429 Too Many Requests")),
                "限流退避后通常能过");
        assertTrue(policy.retryable(new ConnectException("Connection refused")),
                "连不上多半是瞬时故障");
        assertTrue(policy.retryable(new java.net.SocketTimeoutException("Read timed out")),
                "读超时可以重试");
    }

    @Test
    void clientSideErrorsAreNotRetried() {
        assertFalse(policy.retryable(new AuthenticationException("401 Invalid API key")),
                "密钥错重试一百次也一样");
        assertFalse(policy.retryable(new InvalidRequestException("400 messages too long")),
                "请求本身不合法，要改请求而不是重发");
    }

    @Test
    void wrappedCausesAreUnwrapped() {
        // ChatAgent 会把流式失败包成 IllegalStateException，判定必须能穿透到根因
        RuntimeException wrapped = new IllegalStateException("流式调用失败",
                new InternalServerException("502 Bad Gateway"));
        assertTrue(policy.retryable(wrapped), "包装层不该掩盖可重试的根因");

        RuntimeException blocked = new IllegalStateException("流式调用失败",
                new AuthenticationException("401"));
        assertFalse(blocked == null);
        assertFalse(policy.retryable(blocked), "包装层也不该把不可重试的说成可重试");
    }

    @Test
    void messageFallbackOnlyMatchesTransientWords() {
        assertTrue(policy.retryable(new RuntimeException("upstream returned 503")),
                "没有异常类型时，明确的 503 算可重试");
        assertFalse(policy.retryable(new RuntimeException("model not found")),
                "普通错误不该被当成瞬时故障");
    }

    @Test
    void nonRetriableExceptionWins() {
        // 反过来构造：外层可重试、内层不可重试。按"先判显式分类"的顺序，应判成不可重试
        RuntimeException mixed = new IllegalStateException("wrapper",
                new InvalidRequestException("bad request"));
        assertFalse(policy.retryable(mixed));
    }

    @Test
    void jdkTimeoutCountsAsTransient() {
        assertTrue(policy.retryable(new TimeoutException("timeout")),
                "java.util.concurrent.TimeoutException 也要认");
    }

    @Test
    void zeroRetriesMeansNever() {
        RetryPolicy none = RetryPolicy.NONE;
        assertFalse(none.retryable(new InternalServerException("503")),
                "关掉重试后就一次都不该重试");
        assertEquals(0, none.maxRetries());
    }

    @Test
    void backoffGrowsLinearly() {
        RetryPolicy p = new RetryPolicy(3, 500);
        assertEquals(500, p.delayBefore(1));
        assertEquals(1000, p.delayBefore(2));
        assertEquals(1500, p.delayBefore(3));
    }

    @Test
    void negativeValuesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(-1, 100));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(1, -5));
    }

    @Test
    void ioExceptionIsTransient() {
        assertTrue(policy.retryable(new IOException("Connection reset by peer")));
    }
}
