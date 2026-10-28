package com.example.agentcore.agent;

import com.example.agentcore.messaging.ChatMsg;
import com.example.agentcore.runtime.AgentContext;

/**
 * agent 基类，对应 AutoGen 的 {@code BaseAgent}。
 *
 * <p>实现方只需要关心"收到一条消息后做什么"；身份、路由、事件都由运行时负责。</p>
 */
public abstract class AbstractAgent {

    private final String name;

    protected AbstractAgent(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("agent name 不能为空");
        }
        this.name = name;
    }

    public String name() {
        return name;
    }

    /** 处理一条消息。产物通过 {@link AgentContext#reply} 或 {@link AgentContext#publish} 写出。 */
    public abstract void handle(ChatMsg msg, AgentContext ctx);
}
