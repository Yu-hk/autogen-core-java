package com.example.agentcore.tool.travel;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具返回值序列化。
 *
 * <p>Python 版工具直接返回 {@code dict} / {@code list}，模型侧会自动拿到 JSON 文本；
 * Java 这边 {@code ToolRegistry.invoke} 用 {@code String.valueOf(result)} 回灌，
 * 所以工具自己就得把结构化成 JSON。用 Jackson 而不是手拼字符串，
 * 免得中文、引号、换行把 JSON 弄坏。</p>
 */
final class JsonOut {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonOut() {
    }

    static String of(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // 兜底：宁可返回不完美但可读的内容，也不要因为序列化失败把工具结果吞掉
            return "{\"error\":\"序列化失败\",\"raw\":\"" + String.valueOf(value).replace("\"", "'") + "\"}";
        }
    }

    /** 保序建一个 Map，参数按 key/value 交替传入。 */
    static Map<String, Object> object(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return map;
    }

    static <T> List<T> list() {
        return new java.util.ArrayList<>();
    }
}
