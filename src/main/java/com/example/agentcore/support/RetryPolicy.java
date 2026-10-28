package com.example.agentcore.support;

import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.exception.RetriableException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;

/**
 * 模型调用的重试策略。
 *
 * <p>起因很实际：一轮任务在模型上要停十几秒到几分钟，前面可能已经跑完十几次工具调用。
 * 这时候如果模型端偶发一次 503 或者连接被重置，整轮结果直接作废，用户只能从头再跑一遍。
 * 之前的代码没有任何重试（{@code grep retry} 是全空的）。</p>
 *
 * <p><b>什么该重试：</b>LangChain4j 已经把错误分好类了——{@link RetriableException} 的
 * 子类（429 限流、5xx、超时）说明"再试一次可能就行"；{@link NonRetriableException}
 * 的子类（401 鉴权、400 参数错、模型不存在）说明"再试一百次也一样"。这个划分直接拿来用，
 * 不自己根据消息文本猜状态码。</p>
 *
 * <p><b>什么不该重试：</b>本地的流式输出已经开始往后推字了，这时候重来会让页面出现
 * 重复的半句话。是否允许重试由调用方判断（见 {@code ChatAgent#callModel}），
 * 本类只回答"这个异常值不值得再试"。</p>
 */
public record RetryPolicy(int maxRetries, long backoffMillis) {

    /** 一次都不重试。测试里用这个，避免把 mock 的失败也重试掉。 */
    public static final RetryPolicy NONE = new RetryPolicy(0, 0L);

    /**
     * 默认值：重试 1 次、退避 800ms。
     *
     * <p>次数给 1 而不是 3，是因为这里的调用很贵：一次失败已经等了超时时间（云端 90 秒），
     * 再叠三次重试会让用户等上五六分钟还以为卡死了。真正的网络抖动 1 次足够覆盖。</p>
     */
    public static RetryPolicy defaults() {
        return new RetryPolicy(1, 800L);
    }

    public RetryPolicy {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries 不能为负: " + maxRetries);
        }
        if (backoffMillis < 0) {
            throw new IllegalArgumentException("backoffMillis 不能为负: " + backoffMillis);
        }
    }

    /** 第 {@code attempt} 次重试前该等多久：线性退避，第 n 次等 n 倍基础间隔。 */
    public long delayBefore(int attempt) {
        return backoffMillis * Math.max(1, attempt);
    }

    /**
     * 这个异常值不值得再试一次。
     *
     * <p>判定顺序有讲究：先看 LangChain4j 的显式分类，再看 JDK 的网络异常，
     * 最后才退回消息关键词。反过来做的话，一个 {@code InvalidRequestException}
     * 的消息里恰好带着 "timeout" 字样就会被误判成可重试。</p>
     */
    public boolean retryable(Throwable error) {
        if (maxRetries <= 0 || error == null) {
            return false;
        }
        Throwable current = error;
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (current instanceof NonRetriableException) {
                return false;
            }
            if (current instanceof RetriableException) {
                return true;
            }
            current = current.getCause();
        }

        current = error;
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (current instanceof HttpTimeoutException
                    || current instanceof SocketTimeoutException
                    || current instanceof ConnectException
                    || current instanceof SocketException
                    || current instanceof IOException) {
                return true;
            }
            current = current.getCause();
        }

        return looksTransient(error.getMessage());
    }

    /** 兜底：没有异常类型可用时（例如被包装成裸 RuntimeException），只认明确的瞬时故障词。 */
    private static boolean looksTransient(String message) {
        if (message == null) {
            return false;
        }
        String text = message.toLowerCase();
        return text.contains("429") || text.contains("rate limit")
                || text.contains("500") || text.contains("502")
                || text.contains("503") || text.contains("504")
                || text.contains("timeout") || text.contains("timed out")
                || text.contains("connection reset") || text.contains("connection refused")
                || text.contains("temporarily unavailable");
    }

    public String describe() {
        return maxRetries <= 0
                ? "不重试"
                : "最多重试 " + maxRetries + " 次（退避 " + backoffMillis + "ms 起）";
    }
}
