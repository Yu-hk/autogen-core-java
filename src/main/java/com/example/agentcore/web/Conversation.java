package com.example.agentcore.web;

import com.example.agentcore.runtime.SessionState;

import java.util.List;
import java.util.UUID;

/**
 * 一次多轮对话：跨越多轮 run 的上下文。
 *
 * <p>与 {@link RunSession} 的区别是生命周期：{@code RunSession} 是"一次运行"，跑完即终态，
 * SSE 连接结束就没人用了；{@code Conversation} 是"一段对话"，用户追问时要把上一轮的消息
 * 原样接上。之前没有这一层，每次提问都是全新会话，用户问完"长沙一日游"再问"第二家呢"，
 * 系统会把第二句当成一个全新的问题，上一轮的坐标和餐馆全丢。</p>
 *
 * <p><b>只存消息，不存事件。</b>流式增量和工具事件是渲染用的，重放只会重复打印；
 * 接着聊需要的只有"谁说了什么"。这也是唯一需要控制大小的地方——一段聊上十轮，
 * 事件列表能到几 MB，消息列表只有几十 KB。</p>
 *
 * <p><b>落盘由 {@link ConversationStore} 负责，这个类只管状态。</b>落盘之后
 * "重启即丢"就不再成立了：进程重启、容器滚动发布、会话被内存 LRU 淘汰，
 * 都能从磁盘按 id 重新捞回来，追问接着上文走。写盘时机是"一轮跑完"
 * （{@link #remember} 之后），不是每条增量——一次任务几十 KB 增量写几百次盘没有意义。</p>
 */
final class Conversation {

    private final String id;
    private volatile String scenarioId;
    private volatile SessionState state;
    private volatile int turns;
    private volatile long lastActiveAt;

    private Conversation(String id, String scenarioId, SessionState state, int turns, long lastActiveAt) {
        this.id = id;
        this.scenarioId = scenarioId;
        this.state = state;
        this.turns = turns;
        this.lastActiveAt = lastActiveAt;
    }

    static Conversation start(String scenarioId) {
        return new Conversation(UUID.randomUUID().toString().substring(0, 8), scenarioId, null, 0,
                System.currentTimeMillis());
    }

    /**
     * 从磁盘恢复一段对话。
     *
     * <p>{@code lastActiveAt} 沿用落盘时的时间而不是"现在"：它同时是 LRU 的排序依据和
     * TTL 的判定依据，改成 now 会让所有旧对话看起来都是刚聊过的，淘汰与清理就都失效了。</p>
     */
    static Conversation restore(String id, String scenarioId, int turns, long lastActiveAt,
                                SessionState state) {
        return new Conversation(id, scenarioId, state, Math.max(0, turns),
                lastActiveAt > 0 ? lastActiveAt : System.currentTimeMillis());
    }

    String id() {
        return id;
    }

    String scenarioId() {
        return scenarioId;
    }

    void useScenario(String value) {
        this.scenarioId = value;
    }

    int turns() {
        return turns;
    }

    long lastActiveAt() {
        return lastActiveAt;
    }

    boolean hasHistory() {
        SessionState current = state;
        return current != null && !current.messages().isEmpty();
    }

    int messageCount() {
        SessionState current = state;
        return current == null ? 0 : current.messages().size();
    }

    /**
     * 存下这一轮跑完的上下文，供下一轮接着用。
     *
     * <p>轮次和事件都归零：轮次归零是必要的（`maxRounds` 是累计预算，不归零第二轮就
     * 直接"已达最大轮次"结束）；事件归零是为了体积，重放用不上。</p>
     */
    synchronized void remember(SessionState snapshot) {
        this.state = new SessionState(snapshot.messages(), List.of(), 0,
                snapshot.lastStopReason(), System.currentTimeMillis());
        this.turns++;
        this.lastActiveAt = System.currentTimeMillis();
    }

    /**
     * 交给 {@code Team.resume(state, task)} 的上下文。
     *
     * <p>复制成"轮次 0"的一份，原因同上：轮次不归零，续接的第二轮会一上来就被
     * {@code maxRounds} 判定为超限。</p>
     */
    synchronized SessionState carryOver() {
        SessionState current = state;
        if (current == null) {
            return null;
        }
        return new SessionState(current.messages(), List.of(), 0,
                current.lastStopReason(), current.savedAt());
    }

    synchronized void touch() {
        this.lastActiveAt = System.currentTimeMillis();
    }

    /** 供 {@link ConversationStore} 落盘：拿到当前上下文的一份只读视图。 */
    synchronized SessionState state() {
        return state;
    }

    /**
     * 页面右栏展示用：这段对话从哪一轮开始、聊了几句。
     */
    String describe() {
        return "第 " + (turns + 1) + " 轮 · 已有 " + messageCount() + " 条消息";
    }
}
