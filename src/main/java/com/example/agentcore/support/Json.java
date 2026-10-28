package com.example.agentcore.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具返回值的序列化助手。
 *
 * <p>{@code ToolRegistry} 用 {@code String.valueOf(result)} 把工具返回值回灌给模型，
 * 所以结构得工具自己序列化。代码里中文、引号、换行、制表符遍地都是，手拼字符串必然坏，
 * 统一走 Jackson。</p>
 *
 * <p>放在 {@code support} 而不是某个工具包里：编码工具和数据库工具都要用，
 * 谁都不该反过来依赖对方的包。</p>
 */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    public static String of(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{\"ok\":false,\"error\":\"序列化失败\"}";
        }
    }

    /** 保序建 Map：参数按 key/value 交替传入。 */
    public static Map<String, Object> obj(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return map;
    }

    public static List<Object> arr() {
        return new ArrayList<>();
    }
}
