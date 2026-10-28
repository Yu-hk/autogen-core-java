package com.example.agentcore.runtime;

/**
 * 基于类型的订阅，对应 AutoGen 的 {@code TypeSubscription}。
 *
 * <p>声明"这个 topic type 归哪一类 agent"，agent key 直接从 topic source 取。
 * 这样一来发布方不需要知道接收方是谁，也不需要预建实例。</p>
 */
public record Subscription(String topicType, String agentType) {

    public Subscription {
        if (topicType == null || topicType.isBlank()) {
            throw new IllegalArgumentException("topicType 不能为空");
        }
        if (agentType == null || agentType.isBlank()) {
            throw new IllegalArgumentException("agentType 不能为空");
        }
    }
}
