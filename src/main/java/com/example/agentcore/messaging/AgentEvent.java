package com.example.agentcore.messaging;

/**
 * 过程事件，对应 AutoGen 的 {@code BaseAgentEvent}。
 *
 * <p>UI 能显示"正在调用工具"、能展示 token 流，靠的是这一类事件，而不是解析最终文本。</p>
 *
 * <p>注意 {@link TextDelta} 与 {@link SpeakingFinished} 的关系：前者是流式过程中的增量碎片，
 * 后者是这一轮最终写入共享上下文的权威文本。二者不可互换——工具调用场景下，
 * 模型可能先吐出半句话再去调工具，那段 delta 不会进入最终回复。</p>
 */
public sealed interface AgentEvent permits AgentEvent.SpeakingStarted, AgentEvent.SpeakingFinished,
        AgentEvent.TextDelta, AgentEvent.ToolCallRequested, AgentEvent.ToolExecuted,
        AgentEvent.Retrying {

    String agent();

    /** 某位成员开始发言。 */
    record SpeakingStarted(String agent, int round) implements AgentEvent {
    }

    /** 某位成员发言结束。 */
    record SpeakingFinished(String agent, int round, String text) implements AgentEvent {
    }

    /**
     * 流式增量文本片段（仅在流式模型下产生）。
     *
     * <p>不写入快照：它是渲染用的瞬时流，恢复会话时重放它只会重复打印。
     * 恢复所需的权威文本在 {@link SpeakingFinished} 与 {@link ChatMsg} 里。</p>
     */
    record TextDelta(String agent, int round, String delta) implements AgentEvent {
    }

    /** 模型请求调用工具，对应 {@code ToolCallRequestEvent}。 */
    record ToolCallRequested(String agent, String tool, String arguments) implements AgentEvent {
    }

    /** 工具执行完成，对应 {@code ToolCallExecutionEvent}。 */
    record ToolExecuted(String agent, String tool, String result, long millis) implements AgentEvent {
    }

    /**
     * 模型调用失败，即将重试。
     *
     * <p>必须作为事件外抛而不是只写日志：用户看到的等待时间会因此变长（多等一个退避 + 一整次调用），
     * 没有这条提示的话，页面在重试期间和卡死没有区别。</p>
     *
     * @param attempt     第几次重试（从 1 开始）
     * @param maxRetries  本次运行允许的最大重试次数
     * @param delayMillis 重试前等待的毫秒数
     * @param reason      失败的简要原因，给人看
     */
    record Retrying(String agent, int attempt, int maxRetries, long delayMillis, String reason)
            implements AgentEvent {
    }
}
