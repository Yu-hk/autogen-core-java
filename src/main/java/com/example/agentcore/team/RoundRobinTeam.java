package com.example.agentcore.team;

import com.example.agentcore.messaging.ChatMsg;
import com.example.agentcore.runtime.AgentId;
import com.example.agentcore.runtime.AgentRuntime;

import java.util.List;

/**
 * 轮询团队，对应 AutoGen 的 {@code RoundRobinGroupChat}。
 *
 * <p>全体成员共享同一份上下文，按固定顺序轮流发言。最常见的用法是 reflection：
 * 生成者 → 评审者 → 生成者修改。</p>
 */
public class RoundRobinTeam extends Team {

    public RoundRobinTeam(AgentRuntime runtime, List<AgentId> members) {
        super(runtime, members);
    }

    @Override
    protected AgentId selectSpeaker(int round, ChatMsg last) {
        return roundRobin(round);
    }
}
