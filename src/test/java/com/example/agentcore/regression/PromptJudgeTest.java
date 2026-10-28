package com.example.agentcore.regression;

import com.example.agentcore.support.ModelConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归框架自身的单测。
 *
 * <p>这一层必须进默认 {@code mvn test}：跑真模型的 {@link PromptRegressionTest} 平时是关着的，
 * 如果判定逻辑写错了，那条路径上没有任何东西会告诉你——直到某天你看着全绿的回归报告
 * 以为提示词改好了，其实判据早就失效了。</p>
 */
class PromptJudgeTest {

    private static final PromptTask TASK = PromptTask.of(
            "demo", "演示用", 2, List.of("6000"), "单测占位");

    @Test
    void tooFewToolCallsIsAFailure() {
        // 答案本身要够长（过 minAnswerChars），这样失败的只有"工具调用太少"这一项
        List<String> problems = PromptRegressionTest.judge(TASK, 1,
                "根据实现，RunSession 的保留窗口是 6000 条，超过之后最老的会被挤出去。");
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("少于期望的 2"), problems.get(0));
    }

    @Test
    void missingFactIsAFailure() {
        List<String> problems = PromptRegressionTest.judge(TASK, 3, "这个值大概是几千条吧。");
        assertTrue(problems.stream().anyMatch(p -> p.contains("缺") && p.contains("6000")),
                problems.toString());
    }

    @Test
    void goodAnswerPasses() {
        assertEquals(List.of(), PromptRegressionTest.judge(TASK, 3,
                "RunSession 里 MAX_RETAINED 定义为 6000，也就是最多保留 6000 条事件供断点补发。"));
    }

    /** 概念题不该去翻文件：上限也要判，否则"过度工具化"没人管。 */
    @Test
    void tooManyToolCallsIsAlsoAFailure() {
        PromptTask chat = new PromptTask("chat", "说说 SSE", "general",
                0, 2, List.of("服务器"), 10, "反向用例");
        List<String> problems = PromptRegressionTest.judge(chat, 5,
                "SSE 是服务器向浏览器单向推送的技术。");
        assertTrue(problems.stream().anyMatch(p -> p.contains("多于期望的 2")), problems.toString());
    }

    /** 空回答（模型没说话）不该被当成"通过"，否则一次静默失败会变成一条绿记录。 */
    @Test
    void emptyAnswerFails() {
        List<String> problems = PromptRegressionTest.judge(TASK, 3, "");
        assertTrue(problems.stream().anyMatch(p -> p.contains("回答只有 0 字")), problems.toString());
    }

    /**
     * 计划句判定。
     *
     * <p>正例来自线上真实出现过的三种说法；负例来自两次误伤的教训——
     * 「接下来我将分析 pom.xml。分析结果：……」是<b>完成了</b>的回答，
     * 只看关键词的实现会把它判成失败。</p>
     */
    @Test
    void planEndingIsDetected() {
        assertTrue(PromptRegressionTest.endsWithPlan(
                "根据 pom.xml 看出这是单模块工程。接下来，我将分析 frequencyAnaly 目录下的内容。"));
        assertTrue(PromptRegressionTest.endsWithPlan("我需要更多信息。下一步我会检查依赖版本"));
        assertTrue(PromptRegressionTest.endsWithPlan("我会使用 search_code 来查找入口类和配置文件。"));
    }

    @Test
    void completeAnswerIsNotMistakenForAPlan() {
        assertFalse(PromptRegressionTest.endsWithPlan(
                "接下来我将分析 pom.xml。分析结果：这是一个单模块 Spring 工程，Java 17。"));
        assertFalse(PromptRegressionTest.endsWithPlan("结论：Java 17，不需要改。"));
        // 提到"我"但是过去时/结论句，不该误判
        assertFalse(PromptRegressionTest.endsWithPlan("我查看了 pom.xml 和主类，结论是单模块工程。"));
    }

    @Test
    void reportContainsOneRowPerTaskAndFailureDetails() {
        PromptTask ok = PromptTask.of("ok-task", "问题一", 1, List.of("17"), "占位");
        PromptTask bad = PromptTask.of("bad-task", "问题二", 3, List.of("6000"), "占位");
        List<RegressionReport.Outcome> outcomes = List.of(
                new RegressionReport.Outcome(ok, 2, 42_000, "Java 17。", List.of(), List.of("read_file pom.xml")),
                new RegressionReport.Outcome(bad, 1, 31_000, "大概几千条。",
                        List.of("工具调用 1 次，少于期望的 3"), List.of("read_file a.txt")));

        String report = RegressionReport.render(
                new ModelConfig("ollama", "http://x/v1", "qwen2.5:3b", "k", 0.0, Duration.ofMinutes(1)),
                Path.of("/tmp/proj"), outcomes, 73_000, Map.of("ok-task", 2, "bad-task", 5));

        assertTrue(report.contains("**1/2 通过**"), report);
        assertTrue(report.contains("| `ok-task` | ✅ | 2（≥1） | 2 |"), report);
        assertTrue(report.contains("| `bad-task` | ❌ | 1（≥3） | 5 |"), report);
        assertTrue(report.contains("## 失败详情"), "有失败就要有详情页：" + report);
        assertTrue(report.contains("- 守的是：占位"), report);
        // 3 → 1 掉了一半以上，要出退化预警
        assertTrue(report.contains("工具调用明显下降"), "应该提示退化：" + report);
    }

    @Test
    void allGreenReportSaysSo() {
        PromptTask ok = PromptTask.of("ok-task", "问题一", 1, List.of("17"), "占位");
        String report = RegressionReport.render(
                new ModelConfig("ollama", "http://x/v1", "m", "k", 0.0, Duration.ofMinutes(1)),
                Path.of("/tmp/proj"),
                List.of(new RegressionReport.Outcome(ok, 2, 1000, "17", List.of(), List.of())),
                1000, Map.of());
        assertTrue(report.contains("**1/1 通过**"), report);
        assertTrue(report.contains("全部通过"), report);
        assertFalse(report.contains("## 失败详情"), report);
        assertFalse(report.contains("基线：已加载"), "没有基线时不该假装有：" + report);
    }

    @Test
    void baselineJsonIsRoundTrippable() throws Exception {
        PromptTask ok = PromptTask.of("ok-task", "问题一", 1, List.of("17"), "占位");
        String json = RegressionReport.baselineJson(
                new ModelConfig("ollama", "http://x/v1", "qwen2.5:3b", "k", 0.0, Duration.ofMinutes(1)),
                List.of(new RegressionReport.Outcome(ok, 3, 1000, "", List.of(), List.of())));

        assertTrue(json.contains("\"model\" : \"qwen2.5:3b\""), json);
        assertTrue(json.contains("\"ok-task\" : 3"), json);
    }

    /** 任务清单本身也要体检：id 唯一、问题非空、事实值不能是空串。 */
    @Test
    void taskListIsSane() {
        List<PromptTask> tasks = PromptTasks.all();
        assertEquals(10, tasks.size(), "固定 10 条，加减要同步改文档");
        assertEquals(tasks.size(), tasks.stream().map(PromptTask::id).distinct().count(), "id 不能重复");
        for (PromptTask task : tasks) {
            assertFalse(task.question().isBlank(), task.id());
            assertTrue(task.minToolCalls() >= 0, task.id());
            assertTrue(task.mustMention().stream().noneMatch(String::isBlank),
                    task.id() + " 的 mustMention 里有空串，会永远通过");
            assertFalse(task.why().isBlank(), task.id() + " 要写清守的是什么");
        }
    }
}
