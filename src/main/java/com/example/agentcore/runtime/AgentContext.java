package com.example.agentcore.runtime;

import com.example.agentcore.messaging.AgentEvent;
import com.example.agentcore.messaging.ChatMsg;
import com.example.agentcore.messaging.Topic;

import java.util.List;

/**
 * agent 处理器能拿到的运行时句柄，对应 AutoGen 中 handler 可用的 runtime API。
 *
 * <ul>
 *   <li>{@link #send} 直接消息（一对一），语义同 {@code send_message}</li>
 *   <li>{@link #publish} 广播（一对多），语义同 {@code publish_message}</li>
 *   <li>{@link #reply} 把本轮产出写回共享上下文（AgentChat 团队驱动场景下的常规做法）</li>
 *   <li>{@link #emit} 抛过程事件，供 UI / 日志消费</li>
 * </ul>
 */
public interface AgentContext {

    AgentId self();

    /** 当前 agent 的显示名（事件与日志用）。 */
    String agentName();

    /** 当前所处轮次，由团队在派发前设置。 */
    int round();

    void send(AgentId target, ChatMsg msg);

    void publish(Topic topic, ChatMsg msg);

    void reply(ChatMsg msg);

    void emit(AgentEvent event);

    /** 共享上下文的只读快照（含本条触发消息）。 */
    List<ChatMsg> transcript();
}
