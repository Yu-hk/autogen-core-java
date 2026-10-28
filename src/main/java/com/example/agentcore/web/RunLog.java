package com.example.agentcore.web;

import com.example.agentcore.messaging.AgentEvent;
import com.example.agentcore.support.JsonlLog;
import com.example.agentcore.team.TaskResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * 一次运行的服务端日志。
 *
 * <p>浏览器看到的是 SSE 推过去的事件，服务端此前一行日志都没有——出问题时只能开 devtools，
 * 这在"模型回答跑偏"这类故障上尤其难受：页面只给结果，不给过程。</p>
 *
 * <p>这里把框架事件按人读的顺序落一行一条，格式 {@code [时间] [run 会话号] 内容}，
 * 与页面上的事件时间线一一对应，便于事后对账。</p>
 */
final class RunLog {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String sessionId;
    private final long startedAt = System.currentTimeMillis();
    /** 结构化日志出口；{@code null} 表示只打控制台。 */
    private final JsonlLog file;
    /**
     * 触发这次运行的 HTTP 请求 id。
     *
     * <p>它把"浏览器里那次点击"和"服务端这一串模型/工具调用"连起来：
     * 页面上的 X-Request-Id 与这里的 requestId 相同，出问题按一个字符串就能捞全。</p>
     */
    private final String requestId;
    /** 工具事件不带轮次，沿用最近一次发言事件的轮次，日志才对得上页面时间线。 */
    private int round;

    RunLog(String sessionId) {
        this(sessionId, null, "-");
    }

    RunLog(String sessionId, JsonlLog file, String requestId) {
        this.sessionId = sessionId;
        this.file = file;
        this.requestId = requestId == null ? "-" : requestId;
    }

    /** 开跑：把"这次到底用什么在回答"写清楚，mock 与真实模型的区别一眼可见。 */
    void start(String scenario, String provider, String model, int maxRounds, String task) {
        print("start 场景=" + scenario + " 来源=" + provider + (model == null ? "" : "/" + model)
                + " 最大轮次=" + maxRounds + " 任务=" + shorten(task, 40));
        ObjectNode node = base("start");
        node.put("scenario", scenario);
        node.put("provider", provider);
        node.put("model", model == null ? "" : model);
        node.put("maxRounds", maxRounds);
        node.put("task", shorten(task, 200));
        emit(node);
    }

    /** 补充说明：用来解释本次运行的非常规之处，例如"离线剧本不看任务内容"。 */
    void note(String message) {
        print("note " + message);
        ObjectNode node = base("note");
        node.put("message", message);
        emit(node);
    }

    /** 逐条翻译过程事件；增量太多，不打印（只在结束时给总数）。 */
    void event(AgentEvent event) {
        if (event instanceof AgentEvent.SpeakingStarted started) {
            round = started.round();
            print("r" + round + " " + started.agent() + " 开始发言");
        } else if (event instanceof AgentEvent.SpeakingFinished finished) {
            round = finished.round();
            print("r" + round + " " + finished.agent() + " 发言结束 · "
                    + (finished.text() == null ? 0 : finished.text().length()) + " 字");
        } else if (event instanceof AgentEvent.ToolCallRequested requested) {
            print("r" + round + " " + requested.agent() + " → 调用 "
                    + requested.tool() + "(" + shorten(requested.arguments(), 60) + ")");
        } else if (event instanceof AgentEvent.ToolExecuted executed) {
            print("r" + round + " " + executed.agent() + " ← 返回 "
                    + shorten(executed.result(), 60) + " · " + executed.millis() + "ms");
        }
    }

    void done(TaskResult result, long deltas, long toolCalls) {
        print("done 轮次=" + result.round()
                + " 消息=" + result.messages().size()
                + " 事件=" + result.events().size()
                + " 增量=" + deltas
                + " 工具=" + toolCalls
                + " 停止=" + result.stopReason()
                + " 耗时=" + elapsed() + "ms");
        ObjectNode node = base("done");
        node.put("round", result.round());
        node.put("messages", result.messages().size());
        node.put("events", result.events().size());
        node.put("deltas", deltas);
        node.put("toolCalls", toolCalls);
        node.put("stopReason", result.stopReason());
        node.put("millis", elapsed());
        emit(node);
    }

    /**
     * 记录一次"已知会失败"的收尾。
     *
     * <p>和 {@link #error(String, Throwable)} 的区别是这里没有异常栈可打——模型没配好、
     * 总结模型缺失，这些都是服务启动时就该被发现的状态，不是代码崩了。分成两个方法是为了
     * 让日志里"真的出 bug"和"环境没配好"一眼能分开。</p>
     */
    void failed(String code, String message) {
        print("error[" + code + "] " + message);
        ObjectNode node = base("error");
        node.put("code", code);
        node.put("type", "RunFailure");
        node.put("message", message);
        node.put("millis", elapsed());
        emit(node);
    }

    void error(String code, Throwable error) {
        print("error[" + code + "] " + error.getClass().getSimpleName() + ": " + error.getMessage());
        ObjectNode node = base("error");
        node.put("code", code);
        node.put("type", error.getClass().getSimpleName());
        node.put("message", String.valueOf(error.getMessage()));
        node.put("millis", elapsed());
        emit(node);
        // 演示项目里把栈打到 stderr，省得为一条异常再挂日志框架
        error.printStackTrace();
    }

    private long elapsed() {
        return System.currentTimeMillis() - startedAt;
    }

    /** 结构化日志的公共字段：同一次运行的所有记录都能靠 run / requestId 串起来。 */
    private ObjectNode base(String event) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("event", event);
        node.put("run", sessionId);
        node.put("requestId", requestId);
        return node;
    }

    private void emit(ObjectNode node) {
        if (file != null) {
            file.write(node);
        }
    }

    private void print(String message) {
        System.out.println("[" + LocalTime.now().format(TS) + "] [run " + sessionId + "] " + message);
    }

    private static String shorten(String text, int max) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }
}
