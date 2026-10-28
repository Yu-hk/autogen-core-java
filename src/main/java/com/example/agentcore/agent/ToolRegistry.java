package com.example.agentcore.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表：把带 {@code @Tool} 注解的方法变成模型可见的函数规格，并负责按名调用。
 *
 * <p>这一层直接复用 LangChain4j 的注解与 JSON Schema 生成能力——对应 AutoGen 的
 * {@code FunctionTool} + schema 推导，属于不必自研的部分。</p>
 */
public final class ToolRegistry {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final List<ToolSpecification> specifications = new ArrayList<>();
    private final List<Object> targets = new ArrayList<>();
    /** 有副作用的工具名，来自 {@link MutatingTool} 的声明。 */
    private final java.util.Set<String> mutatingTools = new java.util.LinkedHashSet<>();

    private record Entry(Object target, Method method) {
    }

    public static ToolRegistry of(Object... tools) {
        ToolRegistry registry = new ToolRegistry();
        for (Object tool : tools) {
            registry.add(tool);
        }
        return registry;
    }

    public static ToolRegistry empty() {
        return new ToolRegistry();
    }

    private void add(Object tool) {
        targets.add(tool);
        // 有副作用的工具自己声明：同名同参的调用在这些工具成功之后必须能重来
        if (tool instanceof MutatingTool mutating) {
            mutatingTools.addAll(mutating.mutatingTools());
        }
        for (Method method : tool.getClass().getMethods()) {
            if (method.getAnnotation(Tool.class) == null) {
                continue;
            }
            ToolSpecification specification = ToolSpecifications.toolSpecificationFrom(method);
            specifications.add(specification);
            // 必须用规格里的名字而不是 Java 方法名：@Tool(name=...) 可以让工具名是 snake_case
            // （与 Python 版保持一致），而模型回传的是规格名；用方法名做 key 会永远查不到。
            entries.put(specification.name(), new Entry(tool, method));
        }
    }

    public List<ToolSpecification> specifications() {
        return List.copyOf(specifications);
    }

    /**
     * 只保留列出的工具名，得到一个子集注册表。
     *
     * <p><b>为什么需要这一层</b>：同一个工具类里的方法权限不一样——{@code CodeFilesTool} 里
     * {@code list_files}/{@code read_file} 是纯读，{@code write_file}/{@code edit_file} 会改磁盘。
     * 而"看一眼这个项目"这种任务需要的是前者、不需要后者。权限只能按"这一轮挂哪些工具"来切，
     * 不能靠"挂上去再拒"——挂上去就等于告诉模型它具备写的能力，它一定会去试。
     * 注册表在这里是"能力的边界"，不是"调用的守卫"。</p>
     *
     * <p>名字用的是 {@code @Tool(name=...)} 里的规格名（模型看到的那个），不是 Java 方法名。
     * 不存在的名字直接忽略：调用方按固定清单裁剪，某个类少一个方法不该让整个场景起不来。</p>
     */
    public ToolRegistry subset(String... names) {
        ToolRegistry sub = new ToolRegistry();
        for (String name : names) {
            Entry entry = entries.get(name);
            if (entry == null) {
                continue;
            }
            sub.entries.put(name, entry);
            sub.specifications.add(ToolSpecifications.toolSpecificationFrom(entry.method()));
            if (!sub.targets.contains(entry.target())) {
                sub.targets.add(entry.target());
            }
        }
        for (String name : names) {
            if (mutatingTools.contains(name)) {
                sub.mutatingTools.add(name);
            }
        }
        return sub;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** 这个工具会不会改变外部状态。{@link ChatAgent} 用它决定"重复调用"要不要放行。 */
    public boolean isMutating(String toolName) {
        return mutatingTools.contains(toolName);
    }

    /** 按工具名执行，参数从模型给的 JSON 里按参数名映射。失败不抛异常，转成 ERROR 文本回灌给模型。 */
    public String invoke(String toolName, String argumentsJson) {
        Entry entry = entries.get(toolName);
        if (entry == null) {
            return "ERROR: 未注册的工具 " + toolName;
        }
        try {
            Map<String, Object> raw = (argumentsJson == null || argumentsJson.isBlank())
                    ? Map.of()
                    : MAPPER.readValue(argumentsJson, new TypeReference<Map<String, Object>>() {
                    });
            Parameter[] parameters = entry.method().getParameters();
            Object[] args = new Object[parameters.length];
            for (int i = 0; i < parameters.length; i++) {
                Object value = raw.get(parameters[i].getName());
                args[i] = MAPPER.convertValue(value, parameters[i].getType());
            }
            entry.method().setAccessible(true);
            Object result = entry.method().invoke(entry.target(), args);
            return result == null ? "null" : String.valueOf(result);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof com.example.agentcore.support.RetryableToolError) {
                // 参数级的失败：不当成工具坏了，否则两次锚点没对上就把工具禁掉
                return retryable(toolName, cause.getMessage());
            }
            return "ERROR: " + cause.getClass().getSimpleName() + ": " + cause.getMessage();
        } catch (Exception e) {
            if (e instanceof com.example.agentcore.support.RetryableToolError) {
                return retryable(toolName, e.getMessage());
            }
            return "ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /** 可重试失败的统一形状：ok=false + retryable=true，前面没有 ERROR 前缀。 */
    private String retryable(String toolName, String message) {
        try {
            Map<String, Object> node = new java.util.LinkedHashMap<>();
            node.put("ok", false);
            node.put("retryable", true);
            node.put("tool", toolName);
            node.put("reason", message == null ? "参数需要修正后重试" : message);
            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            return "ERROR: " + message;
        }
    }

    public List<Object> targets() {
        return List.copyOf(targets);
    }
}
