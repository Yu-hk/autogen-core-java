package com.example.agentcore.web;

import com.example.agentcore.support.JsonlLog;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;

import java.util.UUID;

/**
 * 给每个 HTTP 请求打一个 traceId，并把它写进一条结构化访问日志。
 *
 * <p>没有 traceId 的时候，"用户说刚才那条回答断了"这种反馈基本没法查：日志里只有一堆
 * 时间相近的请求，分不清谁是谁。有了它，浏览器 devtools 里能看到 {@code X-Request-Id}，
 * 拿这个值 grep 日志就是这次请求的全部记录（含它启动的那次 run）。</p>
 *
 * <p>用 {@link ThreadLocal} 而不是把上下文一路透传：写响应的地方是 {@code json()} 这个底层工具，
 * 它被二十多个处理器调用，为了传一个 id 改遍所有签名不划算。{@code HttpServer} 会复用线程，
 * 所以每次在 {@code finally} 里清掉——不清的话，下一个请求会继承上一个的计时和 id。</p>
 */
final class RequestLog {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

    private RequestLog() {
    }

    private static final class Context {
        String id;
        String method;
        String path;
        long startedNanos;
        boolean finished;
    }

    /** 开始一个请求：生成 id、回写响应头。必须在任何 sendResponseHeaders 之前调用。 */
    static String begin(HttpExchange exchange) {
        Context ctx = new Context();
        ctx.id = UUID.randomUUID().toString().substring(0, 8);
        ctx.method = exchange.getRequestMethod();
        ctx.path = exchange.getRequestURI().getPath();
        ctx.startedNanos = System.nanoTime();
        CURRENT.set(ctx);
        exchange.getResponseHeaders().set("X-Request-Id", ctx.id);
        return ctx.id;
    }

    /** 当前请求的 traceId；不在请求线程里（如后台任务启动时）返回 "-"。 */
    static String currentId() {
        Context ctx = CURRENT.get();
        return ctx == null ? "-" : ctx.id;
    }

    /** 记录响应结果并落日志。重复调用只生效一次（处理器可能既写响应又走异常分支）。 */
    static void finish(HttpExchange exchange, int status, JsonlLog log) {
        Context ctx = CURRENT.get();
        if (ctx == null || ctx.finished) {
            return;
        }
        ctx.finished = true;
        long millis = (System.nanoTime() - ctx.startedNanos) / 1_000_000;
        if (log != null) {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("requestId", ctx.id);
            node.put("method", ctx.method);
            node.put("path", ctx.path);
            node.put("status", status);
            node.put("millis", millis);
            String remote = exchange.getRemoteAddress() == null
                    ? "" : exchange.getRemoteAddress().getAddress().getHostAddress();
            node.put("remote", remote);
            log.write(node);
        }
        System.out.println("[req " + ctx.id + "] " + ctx.method + " " + ctx.path
                + " → " + status + " · " + millis + "ms");
    }

    /**
     * 收尾兜底：静态资源、SSE 这类不走 {@code json()} 的响应，在 {@code route} 的 finally 里调用。
     *
     * <p>{@code status <= 0} 表示"响应已经流式写完了"（SSE），此时没有单一状态码可记，
     * 记 0 并在日志里能看出是长连接即可。</p>
     */
    static void finishIfPending(HttpExchange exchange, JsonlLog log) {
        finish(exchange, 0, log);
        CURRENT.remove();
    }

    /** 清掉线程上下文（finally 里最后一步，防止线程复用串味）。 */
    static void clear() {
        CURRENT.remove();
    }
}
