package com.example.agentcore.regression;

import com.example.agentcore.support.ModelConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把一次回归跑出来的结果渲染成人能读的报告。
 *
 * <p>为什么非要写文件而不是只让 JUnit 报失败：<b>回归的价值在于"和上次比"</b>。
 * JUnit 只会告诉你"这次红了"，看不出"工具调用从 4 次掉到 1 次"这种早期退化——
 * 而后者才是提示词改动最常造成的伤害：它还答得出来，只是不再去看了。</p>
 *
 * <p>表格列刻意只放四项：工具调用（含基线）、用时、判定、问题。答案原文放后面单独一节，
 * 因为翻报告的绝大多数时候你只想知道"哪条变了"。</p>
 */
final class RegressionReport {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 一条任务的跑分。
     *
     * @param toolsUsed 按发生顺序记的"工具名 + 参数摘要"，用来判断它到底看了哪些文件
     */
    record Outcome(PromptTask task, int toolCalls, long millis, String answer,
                   List<String> problems, List<String> toolsUsed) {

        boolean ok() {
            return problems.isEmpty();
        }
    }

    private RegressionReport() {
    }

    static String render(ModelConfig model, Path workspace, List<Outcome> outcomes,
                         long totalMillis, Map<String, Integer> baseline) {
        long passed = outcomes.stream().filter(Outcome::ok).count();
        StringBuilder sb = new StringBuilder(4096);

        sb.append("# 提示词回归报告\n\n");
        sb.append("- 模型：`").append(model.provider()).append("/").append(model.model()).append("`\n");
        sb.append("- 目标目录：`").append(workspace).append("`\n");
        sb.append("- 时间：").append(LocalDateTime.now().format(TS)).append('\n');
        sb.append("- 结果：**").append(passed).append('/').append(outcomes.size()).append(" 通过**")
                .append("，总耗时 ").append(totalMillis / 1000).append(" 秒\n");
        if (!baseline.isEmpty()) {
            sb.append("- 基线：已加载 ").append(baseline.size()).append(" 条（拷自 src/test/resources/prompt-baseline.json）\n");
        }
        sb.append('\n');

        sb.append("| 任务 | 判定 | 工具调用 | 基线 | 用时 | 说明 |\n");
        sb.append("| --- | --- | --- | --- | --- | --- |\n");
        for (Outcome o : outcomes) {
            Integer base = baseline.get(o.task().id());
            sb.append("| `").append(o.task().id()).append("` | ")
                    .append(o.ok() ? "✅" : "❌").append(" | ")
                    .append(o.toolCalls()).append("（≥").append(o.task().minToolCalls());
            if (o.task().maxToolCalls() >= 0) {
                sb.append("，≤").append(o.task().maxToolCalls());
            }
            sb.append("） | ")
                    .append(base == null ? "—" : String.valueOf(base)).append(" | ")
                    .append(o.millis() / 1000).append("s | ")
                    .append(o.ok() ? "" : escape(String.join("；", o.problems())))
                    .append(" |\n");
        }

        // 退化预警：工具调用比基线少一半以上，通常是提示词把"去看文件"这一层弄丢了
        List<String> regressions = outcomes.stream()
                .filter(o -> {
                    Integer base = baseline.get(o.task().id());
                    return base != null && base >= 2 && o.toolCalls() * 2 <= base;
                })
                .map(o -> "`" + o.task().id() + "`：" + baseline.get(o.task().id())
                        + " → " + o.toolCalls() + " 次")
                .toList();
        if (!regressions.isEmpty()) {
            sb.append("\n> ⚠️ 工具调用明显下降（不足基线一半）：").append(String.join("；", regressions))
                    .append("。先怀疑提示词被改松了，而不是模型变差。\n");
        }

        List<Outcome> slowest = outcomes.stream()
                .sorted(Comparator.comparingLong(Outcome::millis).reversed())
                .limit(3)
                .toList();
        sb.append("\n最慢三条：")
                .append(slowest.stream()
                        .map(o -> "`" + o.task().id() + "` " + o.millis() / 1000 + "s")
                        .reduce((a, b) -> a + "、" + b).orElse("—"))
                .append('\n');

        List<Outcome> failed = outcomes.stream().filter(o -> !o.ok()).toList();
        if (failed.isEmpty()) {
            sb.append("\n---\n\n全部通过。改提示词之前先跑一遍、之后跑一遍，两张表对得上才算没退化。\n");
        } else {
            sb.append("\n---\n\n## 失败详情\n");
            for (Outcome o : failed) {
                sb.append("\n### `").append(o.task().id()).append("`\n\n");
                sb.append("- 问题：").append(String.join("；", o.problems())).append('\n');
                sb.append("- 守的是：").append(o.task().why()).append('\n');
                sb.append("- 提问：").append(o.task().question()).append('\n');
                sb.append("- 工具调用（").append(o.toolCalls()).append(" 次）：")
                        .append(o.toolsUsed().isEmpty() ? "无" : escape(String.join(" → ", o.toolsUsed())))
                        .append('\n');
                sb.append("- 答案（前 400 字）：\n\n```\n")
                        .append(shorten(o.answer(), 400)).append("\n```\n");
            }
        }
        return sb.toString();
    }

    /** 基线文件：只留"哪条任务调了几次工具"，模型名和时间用于人工判断这份基线还算不算数。 */
    static String baselineJson(ModelConfig model, List<Outcome> outcomes) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", model.model());
        root.put("recordedAt", LocalDateTime.now().format(TS));
        root.put("note", "提示词回归基线：只在全部通过时记录。拷到 src/test/resources/prompt-baseline.json 生效。");
        Map<String, Integer> tools = new LinkedHashMap<>();
        for (Outcome o : outcomes) {
            tools.put(o.task().id(), o.toolCalls());
        }
        ObjectNode node = root.putObject("tools");
        tools.forEach(node::put);
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n";
    }

    /** 表格里不能有裸的竖线和换行，否则整张表错位。 */
    private static String escape(String text) {
        return text.replace("|", "\\|").replace("\n", " ");
    }

    private static String shorten(String text, int max) {
        if (text == null) {
            return "";
        }
        String stripped = text.strip();
        return stripped.length() <= max ? stripped : stripped.substring(0, max) + "…";
    }
}
