package com.example.agentcore.web;

import com.example.agentcore.messaging.AgentEvent;
import com.example.agentcore.messaging.ChatMsg;
import com.example.agentcore.team.TaskResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 一次运行的会话：模型事件在这里被翻译成浏览器能直接消费的 JSON，投进队列等 SSE 取走。
 *
 * <p>队列天然承担了"事件先到、连接后到"的缓冲：{@code POST /api/run} 立刻返回 sessionId，
 * 浏览器再去建 EventSource，中间这段时间产生的事件不会丢。</p>
 *
 * <p>事件类型刻意压到 9 种，够页面用又不至于让前端写一堆分支：</p>
 * <ul>
 *   <li>{@code status} 生命周期（running / ended）</li>
 *   <li>{@code route} 自动路由的结论（用了哪套协作方式、为什么）</li>
 *   <li>{@code history} 整段历史（首次运行一条任务、续跑则回放已有消息）</li>
 *   <li>{@code message} 一条完整发言</li>
 *   <li>{@code delta} 流式增量</li>
 *   <li>{@code event} 过程事件（发言开始/结束）</li>
 *   <li>{@code tool} 工具请求与结果</li>
 *   <li>{@code retry} 模型调用失败、即将重试</li>
 *   <li>{@code done} / {@code error} 收尾</li>
 * </ul>
 */
public final class RunSession {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 未推送事件的积压上限。
     *
     * <p>一次长回答的增量在几千条量级，两万条足够正常消费；真撞到这个数只可能是
     * 「页面早就关了，没人再 poll」。无界队列在这种场景下会一路涨到 OOM，
     * 而丢几条过期增量对用户没有任何影响（收尾的 done 最后才入队，不会被丢）。</p>
     */
    private static final int MAX_PENDING_EVENTS = 20_000;

    private final String id;
    private final BlockingQueue<String> queue = new LinkedBlockingQueue<>(MAX_PENDING_EVENTS);
    private final AtomicBoolean stopRequested = new AtomicBoolean();
    private final AtomicLong deltas = new AtomicLong();
    private final AtomicLong toolCalls = new AtomicLong();
    private final AtomicLong retries = new AtomicLong();

    private volatile String state = "running";
    private volatile String snapshotFile;
    /** 已推入终止事件（done / error），SSE 循环据此收尾。 */
    private volatile boolean terminal;
    /** 最后一次产生事件的时刻，服务端用它淘汰早就没人看的会话。 */
    private final AtomicLong lastActivityAt = new AtomicLong(System.currentTimeMillis());

    public RunSession(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public String state() {
        return state;
    }

    public String snapshotFile() {
        return snapshotFile;
    }

    public boolean isTerminal() {
        return terminal;
    }

    public long lastActivityAt() {
        return lastActivityAt.get();
    }

    /** 已推送的流式增量片数，收尾统计用。 */
    public long deltaCount() {
        return deltas.get();
    }

    public long toolCallCount() {
        return toolCalls.get();
    }

    public long retryCount() {
        return retries.get();
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }

    /** 给 {@code StopCondition.external(...)} 用：页面点停止就翻这个标志。 */
    public AtomicBoolean stopRequested() {
        return stopRequested;
    }

    public void requestStop() {
        stopRequested.set(true);
    }

    /** 取一条待推送的 JSON，超时返回 null（调用方据此发心跳）。 */
    public String poll(long timeoutMillis) throws InterruptedException {
        return queue.poll(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    // ---------- 事件构造 ----------

    public void status(String phase, String detail) {
        ObjectNode node = base("status");
        node.put("phase", phase);
        node.put("detail", detail);
        push(node);
    }

    /** 回放已有上下文，前端收到后清空并重建对话区。 */
    public void history(List<ChatMsg> messages, int round, String note) {
        history(messages, round, note, false);
    }

    /**
     * 回放上下文。
     *
     * @param append {@code true} 表示"接着这段对话"——页面本来就有这些消息，不再重绘；
     *               只带条数，省掉一次几 KB 的重复传输。{@code false} 用于快照续跑：
     *               页面是空的，必须把整段历史发过去才能重建。
     */
    public void history(List<ChatMsg> messages, int round, String note, boolean append) {
        ObjectNode node = base("history");
        node.put("round", round);
        node.put("note", note);
        node.put("append", append);
        if (append) {
            node.put("count", messages == null ? 0 : messages.size());
            push(node);
            return;
        }
        ArrayNode array = node.putArray("messages");
        for (ChatMsg msg : messages) {
            ObjectNode item = array.addObject();
            item.put("source", msg.source());
            item.put("text", msg.text());
        }
        push(node);
    }

    public void message(String source, String text) {
        ObjectNode node = base("message");
        node.put("source", source);
        node.put("text", text);
        push(node);
    }

    /**
     * 自动路由的结论：这次用的是哪套协作方式、凭什么。
     *
     * <p>单独一种事件而不是塞进 {@code status} 里，是因为它要同时驱动两件事：
     * 页面把成员列表和其它场景相关的东西换掉，以及时间线上留一行"为什么这样派活"。
     * 用户看不到这个判断过程的话，"自动"就成了黑箱。</p>
     */
    public void route(String scenarioId, String title, String reason) {
        ObjectNode node = base("route");
        node.put("scenario", scenarioId);
        node.put("title", title);
        node.put("reason", reason);
        push(node);
    }

    /** 把一次过程事件翻译成页面能画的东西。 */
    public void event(AgentEvent event) {
        if (event instanceof AgentEvent.TextDelta delta) {
            deltas.incrementAndGet();
            ObjectNode node = base("delta");
            node.put("agent", delta.agent());
            node.put("round", delta.round());
            node.put("text", delta.delta());
            push(node);
            return;
        }
        if (event instanceof AgentEvent.SpeakingStarted started) {
            ObjectNode node = base("event");
            node.put("name", "SpeakingStarted");
            node.put("agent", started.agent());
            node.put("round", started.round());
            push(node);
            return;
        }
        if (event instanceof AgentEvent.SpeakingFinished finished) {
            ObjectNode node = base("event");
            node.put("name", "SpeakingFinished");
            node.put("agent", finished.agent());
            node.put("round", finished.round());
            node.put("length", finished.text() == null ? 0 : finished.text().length());
            push(node);
            return;
        }
        if (event instanceof AgentEvent.ToolCallRequested requested) {
            toolCalls.incrementAndGet();
            ObjectNode node = base("tool");
            node.put("phase", "request");
            node.put("agent", requested.agent());
            node.put("tool", requested.tool());
            node.put("arguments", requested.arguments());
            push(node);
            return;
        }
        if (event instanceof AgentEvent.ToolExecuted executed) {
            ObjectNode node = base("tool");
            node.put("phase", "result");
            node.put("agent", executed.agent());
            node.put("tool", executed.tool());
            node.put("result", executed.result());
            node.put("millis", executed.millis());
            push(node);
            return;
        }
        if (event instanceof AgentEvent.Retrying retrying) {
            retries.incrementAndGet();
            ObjectNode node = base("retry");
            node.put("agent", retrying.agent());
            node.put("attempt", retrying.attempt());
            node.put("maxRetries", retrying.maxRetries());
            node.put("delayMillis", retrying.delayMillis());
            node.put("reason", retrying.reason());
            push(node);
        }
    }

    /** 正常结束：带上统计与快照路径，页面据此解锁按钮。 */
    public void done(TaskResult result, String snapshot) {
        this.snapshotFile = snapshot;
        this.state = "done";
        ObjectNode node = base("done");
        node.put("stopReason", result.stopReason());
        node.put("round", result.round());
        node.put("messages", result.messages().size());
        node.put("events", result.events().size());
        node.put("deltas", deltas.get());
        node.put("toolCalls", toolCalls.get());
        node.put("retries", retries.get());
        if (snapshot != null) {
            node.put("snapshot", snapshot);
        }
        push(node);
        terminal = true;
    }

    public void error(String message) {
        this.state = "error";
        ObjectNode node = base("error");
        node.put("message", message);
        push(node);
        terminal = true;
    }

    private ObjectNode base(String kind) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("kind", kind);
        return node;
    }

    private void push(ObjectNode node) {
        lastActivityAt.set(System.currentTimeMillis());
        String payload = node.toString();
        if (!queue.offer(payload)) {
            // 队列满：丢最老的一条再放新的，保证最新状态与终止事件一定能进队
            queue.poll();
            queue.offer(payload);
        }
    }
}
