package com.example.agentcore.runtime;

/** agent 身份，对应 AutoGen 的 {@code AgentID(type, key)}。 */
public record AgentId(String type, String key) {

    public AgentId {
        if (type == null || type.isBlank() || key == null || key.isBlank()) {
            throw new IllegalArgumentException("AgentId 的 type 与 key 都不能为空");
        }
    }

    public static AgentId of(String type, String key) {
        return new AgentId(type, key);
    }

    @Override
    public String toString() {
        return type + "/" + key;
    }
}
