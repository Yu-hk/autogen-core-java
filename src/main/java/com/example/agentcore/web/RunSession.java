package com.example.agentcore.web;

import com.example.agentcore.messaging.AgentEvent;
import com.example.agentcore.messaging.ChatMsg;
import com.example.agentcore.team.TaskResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 一次运行的会话：模型事件在这里被翻译成浏览器能直接消费的 JSON，按序号排进事件日志。
 *
 * <p>这里用的不是"队列"，而是**带序号的保留窗口**。差别只在断线重连这一件事上，
 * 但正是这件事决定了它能不能算一个可用的服务：</p>
 *
 * <ul>
 *   <li>队列是"取走即消失"，连接一断，断开期间产生的事件就永远看不到了；</li>
 *   <li>带序号的日志是"只读游标"，浏览器重连带 {@code Last-Event-ID}，
 *       服务端从那条之后接着发，断几秒只是晚几秒，内容一条不少。</li>
 * </ul>
 *
 * <p>保留窗口有上限（{@link #MAX_RETAINED}）。断太久、落后超过窗口时不再假装能补——
 * 由 {@link #hasGap} 如实上报，让页面重新拉一次全量，而不是悄悄少一段。</p>
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
 *   <li>{@code done} / {@code error} 收尾；{@code error} 带 {@code code}（见 {@link RunFailure}）
 *       与 {@code requestId}，页面据此给出不同的下一步建议</li>
 * </ul>
 */
public final class RunSession {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 保留最近多少条事件供断点补发。
     *
     * <p>一次长回答的流式增量在几千条量级，取 6000 能覆盖「整轮没连上、后补」这个最坏情况；
     * 单条 JSON 几百字节，一个会话约 1–2 MB。会话本身会被 {@code pruneSessions} 清掉，
     * 不会无限累积。</p>
     */
    static final int MAX_RETAINED = 6000;

    /** 一条已入日志的事件：序号、JSON 原文、是否收尾事件。 */
    public record Entry(long seq, String payload, boolean terminal) {
    }

    private final String id;

    /**
     * 环形缓冲，下标就是 {@code seq % MAX_RETAINED}。
     *
     * <p>选数组而不是 {@code ArrayDeque}，是为了让"从游标取下一条"变成一次下标计算：
     * 用双端队列的话，游标贴着队尾时每次都要从头遍历，流式增量一多就是平方级。
     * 覆盖写最老的一条即完成淘汰，不需要额外的清理动作。</p>
     */
    private final Entry[] ring = new Entry[MAX_RETAINED];
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition available = lock.newCondition();
    /** 下一条事件的序号（从 1 开始）。读写都在 lock 内。 */
    private long nextSeq = 1;

    private final AtomicBoolean stopRequested = new AtomicBoolean();
    private final AtomicLong deltas = new AtomicLong();
    private final AtomicLong toolCalls = new AtomicLong();
    private final AtomicLong retries = new AtomicLong();

    private volatile String state = "running";
    private volatile String snapshotFile;
    /** 已推入终止事件（done / error）。 */
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

    /** 给 {@code StopCondition.external(...)} 用：页面点停止就翻这个标志。 */
    public AtomicBoolean stopRequested() {
        return stopRequested;
    }

    public void requestStop() {
        stopRequested.set(true);
    }

    // ---------- 事件读取（SSE 用） ----------

    /** 目前最新一条事件的序号；还没有事件时为 0。 */
    public long latestSeq() {
        lock.lock();
        try {
            return nextSeq - 1;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 游标之后的第一条事件，没有就等到超时。
     *
     * @param cursor       客户端已有的事件序号（{@code Last-Event-ID}，首次连接传 0）
     * @param timeoutMillis 等多久；返回 {@code null} 表示这段时间没有新事件，调用方发心跳
     */
    public Entry pollAfter(long cursor, long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        lock.lock();
        try {
            while (true) {
                Entry next = afterLocked(clamp(cursor));
                if (next != null) {
                    return next;
                }
                long remain = deadline - System.nanoTime();
                if (remain <= 0) {
                    return null;
                }
                available.awaitNanos(remain);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 游标之后的事件是否已经被窗口挤掉了。
     *
     * <p>调用方据此决定是"接着补发"还是"认输、让前端重拉全量"。默默少发一段
     * 比报错更糟：页面会停在半截对话上，用户以为模型答到一半就没了。</p>
     */
    public boolean hasGap(long cursor) {
        lock.lock();
        try {
            return clamp(cursor) + 1 < oldestSeqLocked();
        } finally {
            lock.unlock();
        }
    }

    /** 游标之后的最近一条事件快照，测试与排障用。 */
    public List<Entry> entriesAfter(long cursor) {
        lock.lock();
        try {
            long start = Math.max(clamp(cursor) + 1, oldestSeqLocked());
            List<Entry> list = new java.util.ArrayList<>();
            for (long seq = start; seq < nextSeq; seq++) {
                Entry entry = ring[(int) (seq % MAX_RETAINED)];
                if (entry != null) {
                    list.add(entry);
                }
            }
            return list;
        } finally {
            lock.unlock();
        }
    }

    /** 游标只可能落在 [0, 已发出的最后一条] 之间；超出说明客户端记了个未来值（服务端换过会话）。 */
    private long clamp(long cursor) {
        if (cursor < 0) {
            return 0;
        }
        return Math.min(cursor, nextSeq - 1);
    }

    private long oldestSeqLocked() {
        return Math.max(1, nextSeq - MAX_RETAINED);
    }

    private Entry afterLocked(long cursor) {
        long start = Math.max(cursor + 1, oldestSeqLocked());
        if (start >= nextSeq) {
            return null;
        }
        return ring[(int) (start % MAX_RETAINED)];
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
        push(node, true);
        terminal = true;
    }

    public void error(String code, String message, String requestId) {
        this.state = "error";
        ObjectNode node = base("error");
        // code 是页面的决策依据（重发 / 改输入 / 找人），放在 message 前面便于阅读抓包
        node.put("code", code);
        node.put("message", message);
        if (requestId != null && !requestId.isBlank()) {
            node.put("requestId", requestId);
        }
        push(node, true);
        terminal = true;
    }

    /** 不带类型的失败：一律算 {@code internal}，保留给演示路径与测试。 */
    public void error(String message) {
        error(RunFailure.INTERNAL.code(), message, null);
    }

    private ObjectNode base(String kind) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("kind", kind);
        return node;
    }

    private void push(ObjectNode node) {
        push(node, false);
    }

    /**
     * 入日志并唤醒等待中的 SSE 线程。
     *
     * <p>序号在 lock 内分配，保证"日志里的顺序"和"发出去的顺序"完全一致——
     * 断点续传靠的就是这个顺序：客户端说"我收到 42 了"，服务端必须能确定 43 是哪条。</p>
     */
    private void push(ObjectNode node, boolean isTerminal) {
        lastActivityAt.set(System.currentTimeMillis());
        lock.lock();
        try {
            long seq = nextSeq++;
            node.put("seq", seq);
            ring[(int) (seq % MAX_RETAINED)] = new Entry(seq, node.toString(), isTerminal);
            available.signalAll();
        } finally {
            lock.unlock();
        }
    }
}
