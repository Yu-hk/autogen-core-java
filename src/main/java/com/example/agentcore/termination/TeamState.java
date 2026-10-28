package com.example.agentcore.termination;

import com.example.agentcore.messaging.AgentEvent;
import com.example.agentcore.messaging.ChatMsg;

import java.util.List;
import java.util.Objects;

/**
 * 终止条件求值所需的快照。
 *
 * <p>两个"看全量还是看增量"的访问器是续跑能成立的关键：
 * 从快照恢复后，历史里往往已经存在 {@code APPROVE} 或某次工具调用，
 * 若条件仍按全量判定，续跑会**立刻停止**、等于没续。所以：</p>
 *
 * <ul>
 *   <li>{@link #freshMessages()} / {@link #freshEvents()}：只含本次运行新增的，供文本/发言人/工具类条件使用；</li>
 *   <li>{@link #messageCount()}：累计总数，供 {@code maxMessages} 使用——预算是跨进程的。</li>
 * </ul>
 */
public record TeamState(
        List<ChatMsg> messages,
        List<AgentEvent> events,
        long elapsedMillis,
        int round,
        int restoredMessageCount,
        int restoredEventCount) {

    /** 首次运行时的便捷构造：没有任何历史。 */
    public TeamState(List<ChatMsg> messages, List<AgentEvent> events, long elapsedMillis, int round) {
        this(messages, events, elapsedMillis, round, 0, 0);
    }

    public int messageCount() {
        return messages.size();
    }

    /** 本次运行新增的消息（续跑前的历史不算）。 */
    public List<ChatMsg> freshMessages() {
        int from = Math.min(restoredMessageCount, messages.size());
        return messages.subList(from, messages.size());
    }

    /** 本次运行新增的事件。 */
    public List<AgentEvent> freshEvents() {
        int from = Math.min(restoredEventCount, events.size());
        return events.subList(from, events.size());
    }

    public ChatMsg last() {
        return messages.isEmpty() ? null : messages.get(messages.size() - 1);
    }

    public ChatMsg lastFresh() {
        List<ChatMsg> fresh = freshMessages();
        return fresh.isEmpty() ? null : fresh.get(fresh.size() - 1);
    }

    public String lastText() {
        ChatMsg last = last();
        return last == null ? "" : Objects.toString(last.text(), "");
    }

    public String lastSource() {
        ChatMsg last = last();
        return last == null ? "" : Objects.toString(last.source(), "");
    }

    /** 本次运行是否调用过某个工具（历史调用不算，否则续跑必然立即停止）。 */
    public boolean anyToolCalled(String tool) {
        return freshEvents().stream()
                .filter(e -> e instanceof AgentEvent.ToolExecuted)
                .map(e -> ((AgentEvent.ToolExecuted) e).tool())
                .anyMatch(tool::equals);
    }
}
