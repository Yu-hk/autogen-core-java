package com.example.agentcore.team;

import com.example.agentcore.messaging.AgentEvent;
import com.example.agentcore.messaging.ChatMsg;

import java.util.List;

/**
 * 一次团队运行的结果，对应 AutoGen 的 {@code TaskResult}。
 *
 * <p>{@code round} 是**累计**轮次（含续跑前已发生的轮次），快照要靠它接着数，
 * 所以放在结果里而不是让调用方自己记。</p>
 */
public record TaskResult(List<ChatMsg> messages, List<AgentEvent> events, String stopReason, int round) {

    public String lastText() {
        return messages.isEmpty() ? "" : messages.get(messages.size() - 1).text();
    }

    public boolean hitStopCondition() {
        return !stopReason.startsWith("达到最大轮次");
    }
}
