package com.example.agentcore.runtime;

import com.example.agentcore.messaging.AgentEvent;
import com.example.agentcore.messaging.ChatMsg;

import java.util.List;

/**
 * 一次会话的可持久化状态，对应 AutoGen 的 {@code save_state()} 产物。
 *
 * <p>AutoGen 的洞察是"状态就是消息列表"：没有额外的状态机，把共享上下文与已发生的事件
 * 序列化下来，就等于存下了整场会话。所以这里只有四个字段，没有 agent 内部变量。</p>
 *
 * <p>恢复时要注意两条语义差（详见 {@code StopCondition}）：
 * 消息数上限按**累计**算（预算是跨进程的），超时与文本终止按**本次运行**算。</p>
 */
public record SessionState(
        List<ChatMsg> messages,
        List<AgentEvent> events,
        int round,
        String lastStopReason,
        long savedAt) {

    public SessionState {
        messages = List.copyOf(messages);
        events = List.copyOf(events);
    }

    public static SessionState empty() {
        return new SessionState(List.of(), List.of(), 0, "", 0L);
    }

    public boolean isEmpty() {
        return messages.isEmpty() && events.isEmpty();
    }

    public int messageCount() {
        return messages.size();
    }

    public int eventCount() {
        return events.size();
    }
}
