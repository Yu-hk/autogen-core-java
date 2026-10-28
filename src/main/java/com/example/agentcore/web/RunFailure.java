package com.example.agentcore.web;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

/**
 * 一次运行失败的类型。
 *
 * <p>之前所有失败在页面上长得一样：一句红字，用户不知道该重发、该改输入、还是该去重启服务。
 * 但这三件事的处置成本差着数量级——重发是点一下，改输入要想清楚哪里不对，重启服务得有人去机房
 * 或者 ssh。所以失败必须分类，分类的判据是<b>用户下一步该做什么</b>，不是异常类名。</p>
 *
 * <p>只分四类，因为再多就会退化成"用户看不懂的枚举"：</p>
 * <ul>
 *   <li>{@link #MODEL_UNAVAILABLE} 模型没连上/没配好——找运维或看 Ollama，重发没用；</li>
 *   <li>{@link #TIMEOUT} 跑太久或模型卡住——通常缩小任务范围再发一次；</li>
 *   <li>{@link #BAD_REQUEST} 这次的输入或参数不成立——改输入，重发原样还是错；</li>
 *   <li>{@link #INTERNAL} 认不出来的兜底——可以重发，持续出现就查日志（带 requestId）。</li>
 * </ul>
 *
 * <p><b>为什么没有 {@code tool_failed}</b>：工具抛出的异常在 {@code ToolRegistry} 里就被接住、
 * 转成 {@code {"ok":false,"reason":...}} 交回模型自行修正了，不会中断整轮运行。
 * 也就是说"工具失败"在这个服务里本身就是可恢复的过程状态，不是一个能让一次运行死掉的结局。
 * 硬加一类只会得到永远为 0 的计数。</p>
 */
enum RunFailure {

    MODEL_UNAVAILABLE("model_unavailable"),
    TIMEOUT("timeout"),
    BAD_REQUEST("bad_request"),
    INTERNAL("internal");

    private final String code;

    RunFailure(String code) {
        this.code = code;
    }

    /** 传给前端的稳定标识：新增类型只能往后加，不能改名（前端按它选文案）。 */
    String code() {
        return code;
    }

    /**
     * 按异常判断失败类型。
     *
     * <p>必须顺着 {@code cause} 链找：{@code ChatAgent} 会把底层错误包一层
     * {@code IllegalStateException("xxx 流式调用失败（等待响应）: Connection refused")}，
     * 只看最外层类型的话，所有失败都会被归成"内部错误"，分类等于没做。</p>
     */
    static RunFailure classify(Throwable error) {
        for (Throwable t = error; t != null; t = next(t)) {
            if (t instanceof ModelUnavailable) {
                return MODEL_UNAVAILABLE;
            }
            if (t instanceof TimeoutException
                    || t instanceof SocketTimeoutException
                    || t instanceof HttpTimeoutException
                    || t instanceof InterruptedException) {
                return TIMEOUT;
            }
            // 连不上 + 域名解析不了：在这个服务里基本只有一个来源，就是模型端点
            if (t instanceof ConnectException || t instanceof UnknownHostException) {
                return MODEL_UNAVAILABLE;
            }
            // 参数类错误：快照坏了、workspace 不在白名单、轮次传了负数
            if (t instanceof IllegalArgumentException) {
                return BAD_REQUEST;
            }
        }
        return fromMessage(messages(error));
    }

    /**
     * 没有可用的异常类型时，只能看文字。
     *
     * <p>放宽到"包含"而不是"等于"，是因为内网模型（Ollama / vLLM）超时时的原文五花八门：
     * {@code request timed out}、{@code Read timed out}、{@code 请求超时} 都出现过。
     * 匹配错的代价是多一句引导语，比让用户对着"内部错误"发呆划算。</p>
     */
    static RunFailure fromMessage(String text) {
        String m = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (m.contains("timeout") || m.contains("timed out") || m.contains("超时")) {
            return TIMEOUT;
        }
        if (m.contains("connection refused")
                || m.contains("连不上") || m.contains("failed to connect")
                || m.contains("unknown host")) {
            return MODEL_UNAVAILABLE;
        }
        return INTERNAL;
    }

    /** 拆链前先判自环：有些库会把 cause 指回自己，不判就是死循环。 */
    private static Throwable next(Throwable t) {
        Throwable cause = t.getCause();
        return cause == null || cause == t ? null : cause;
    }

    /** 整条链的文字拼起来：包装层常把真正原因写在 message 里而不是 cause 里。 */
    private static String messages(Throwable error) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = error; t != null; t = next(t)) {
            if (t.getMessage() != null) {
                sb.append(t.getMessage()).append(' ');
            }
        }
        return sb.toString();
    }
}
