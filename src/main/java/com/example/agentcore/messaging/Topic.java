package com.example.agentcore.messaging;

/**
 * AutoGen core 的 Topic：一个二元组 {@code (Topic Type, Topic Source)}。
 *
 * <p>Type 标记消息类别（仅字母、数字、下划线，且不能数字开头）；Source 在同一 Type 内唯一，
 * 通常由业务数据构成，用来做多租户隔离。</p>
 */
public record Topic(String type, String source) {

    public Topic {
        if (type == null || !type.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("不合法 topic type（仅字母/数字/下划线，且不能以数字开头）: " + type);
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("topic source 不能为空");
        }
    }

    public static Topic of(String type, String source) {
        return new Topic(type, source);
    }

    /** 单租户单主题场景的简写：source 固定为 {@code default}。 */
    public static Topic of(String type) {
        return new Topic(type, "default");
    }

    @Override
    public String toString() {
        return type + "/" + source;
    }
}
