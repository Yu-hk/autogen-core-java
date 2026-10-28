package com.example.agentcore.messaging;

/**
 * 对话内容类消息，对应 AutoGen 的 {@code BaseChatMessage}。
 *
 * <p>与 {@link AgentEvent} 的区别：这里的对象是"说了什么"，事件是"过程中发生了什么"。
 * 二者分开，流式输出与可观测性才能成立。</p>
 */
public sealed interface ChatMsg permits ChatMsg.Text, ChatMsg.Handoff, ChatMsg.Stop {

    /** 发送方名字（对应 AutoGen 的 source）。 */
    String source();

    /** 文本内容。 */
    String text();

    /** 普通文本消息，对应 {@code TextMessage}。 */
    record Text(String source, String text) implements ChatMsg {
    }

    /** 交接消息，对应 {@code HandoffMessage}：把控制权显式交给某个 agent（Swarm 模式的基础）。 */
    record Handoff(String source, String target, String text) implements ChatMsg {
    }

    /** 终止消息，对应 {@code StopMessage}。 */
    record Stop(String source, String text) implements ChatMsg {
    }
}
