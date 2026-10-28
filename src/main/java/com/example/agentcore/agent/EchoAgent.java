package com.example.agentcore.agent;

import com.example.agentcore.messaging.ChatMsg;
import com.example.agentcore.runtime.AgentContext;

import java.util.ArrayList;
import java.util.List;

/** 只记录不调模型的 agent，用于验证发布订阅路由是否真的把消息送到了。 */
public class EchoAgent extends AbstractAgent {

    private final List<String> received = new ArrayList<>();

    public EchoAgent(String name) {
        super(name);
    }

    @Override
    public void handle(ChatMsg msg, AgentContext ctx) {
        received.add(msg.source() + ": " + msg.text());
    }

    public List<String> received() {
        return List.copyOf(received);
    }
}
