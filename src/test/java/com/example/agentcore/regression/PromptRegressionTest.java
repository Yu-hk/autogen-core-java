package com.example.agentcore.regression;

import com.example.agentcore.agent.ChatAgent;
import com.example.agentcore.messaging.AgentEvent;
import com.example.agentcore.runtime.AgentId;
import com.example.agentcore.runtime.AgentRuntime;
import com.example.agentcore.support.AppConfig;
import com.example.agentcore.support.ModelConfig;
import com.example.agentcore.team.RoundRobinTeam;
import com.example.agentcore.team.TaskResult;
import com.example.agentcore.team.Team;
import com.example.agentcore.termination.StopCondition;
import com.example.agentcore.tool.code.CodePrompt;
import com.example.agentcore.tool.code.CodeTools;
import com.example.agentcore.tool.code.CodeWorkspace;
import com.example.agentcore.tool.code.Platform;
import com.example.agentcore.web.Scenario;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 提示词回归集：改完提示词，用固定 10 条任务量一次"退化没退化"。
 *
 * <p><b>为什么不是普通的单元测试</b>：它跑真模型，一次 20–30 分钟（CPU 上 3B 的速度）。
 * 放进默认 {@code mvn test} 会把每次构建拖成半小时，所以默认<b>关着</b>，
 * 只在你动了提示词、路由或工具描述时手动开：</p>
 *
 * <pre>
 *   mvn -o test -Dtest=PromptRegressionTest -Dprompt.regression=true
 *   # 换模型做对比
 *   mvn -o test -Dtest=PromptRegressionTest -Dprompt.regression=true -Dregression.model=qwen2.5:7b
 * </pre>
 *
 * <p>结果写到 {@code target/prompt-regression/report.md}（同时打到控制台）：每条任务调了几次工具、
 * 用了哪些工具、缺了哪个必须出现的事实、答案前几百字。失败时先看那张表，再看"失败详情"。</p>
 *
 * <p><b>装配和线上必须一致</b>，否则测的是另一套东西。这里刻意复刻
 * {@code AgentWebServer.buildTeam} 里 general 场景那条路径：同一个 system 提示词 +
 * {@code CodePrompt.readOnlyHint} + 只读工具集 + 20 次工具迭代上限。改那边记得改这边——
 * 不一致的回归集比没有更糟，因为它会给你假的安心。</p>
 */
@EnabledIfSystemProperty(named = "prompt.regression", matches = "true")
@Timeout(value = 120, unit = TimeUnit.MINUTES)
class PromptRegressionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 报告目录：{@code target/} 已被 gitignore，跑出来的东西不会混进提交。 */
    private static final Path REPORT_DIR = Path.of("target", "prompt-regression");

    /** 默认拿最弱的那个模型量——它跑得通，7B/20B 才有意义。 */
    private static final String DEFAULT_MODEL = "qwen2.5:3b";
    private static final String DEFAULT_BASE_URL = "http://127.0.0.1:11434/v1";

    /**
     * 单条任务的执行器。
     *
     * <p>单独开线程只为一件事：能设超时。模型调用是阻塞的 HTTP，主线程上没有任何办法
     * 中断它，而一条卡住的任务足以让整批跑不成。</p>
     */
    private static final java.util.concurrent.ExecutorService WORKER =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "prompt-regression");
                t.setDaemon(true);
                return t;
            });

    /** 单条上限，秒。3B 上正常一条 40–120 秒；超过 4 分钟基本是上下文被撑爆了。 */
    private static int taskTimeoutSeconds() {
        return Integer.getInteger("regression.task-timeout-seconds", 240);
    }

    @Test
    void everyTaskStillBehaves() throws Exception {
        Path workspace = workspace();
        ModelConfig model = model();
        AppConfig config = AppConfig.get();
        List<PromptTask> tasks = selected();

        System.out.println("提示词回归：模型=" + model.model() + " 目录=" + workspace
                + " 任务数=" + tasks.size());
        assertTrue(Files.isDirectory(workspace.resolve("src/main/java")),
                "目标目录下没有 src/main/java，检查 -Dregression.workspace：" + workspace);

        List<RegressionReport.Outcome> outcomes = new ArrayList<>();
        long startedAt = System.currentTimeMillis();
        for (int i = 0; i < tasks.size(); i++) {
            PromptTask task = tasks.get(i);
            System.out.print("[" + (i + 1) + "/" + tasks.size() + "] " + task.id() + " … ");
            System.out.flush();
            RegressionReport.Outcome outcome = runTask(task, config, model, workspace);
            outcomes.add(outcome);
            System.out.println((outcome.ok() ? "PASS" : "FAIL")
                    + " 工具 " + outcome.toolCalls() + " 次 · "
                    + (outcome.millis() / 1000) + "s"
                    + (outcome.ok() ? "" : " · " + String.join("；", outcome.problems())));
            if (!outcome.ok()) {
                // 失败时把答案原文打出来。"只调 1 次工具"和"根本没答"在报告里长得一样，
                // 而这两种情况该改的东西完全不同，当场看见能省掉一轮重跑
                System.out.println("      答：" + shorten(outcome.answer(), 160));
            }
        }
        long totalMillis = System.currentTimeMillis() - startedAt;

        String report = RegressionReport.render(model, workspace, outcomes, totalMillis, baseline());
        Files.createDirectories(REPORT_DIR);
        Path file = REPORT_DIR.resolve("report.md");
        Files.writeString(file, report, StandardCharsets.UTF_8);
        System.out.println();
        System.out.println(report);
        System.out.println("报告已写入 " + file.toAbsolutePath());

        if (Boolean.getBoolean("regression.writeBaseline")) {
            Path baselineFile = REPORT_DIR.resolve("baseline.json");
            Files.writeString(baselineFile, RegressionReport.baselineJson(model, outcomes),
                    StandardCharsets.UTF_8);
            System.out.println("基线已写入 " + baselineFile.toAbsolutePath()
                    + "；确认这份结果没问题后，把它拷到 src/test/resources/prompt-baseline.json");
        }

        List<String> failures = outcomes.stream().filter(o -> !o.ok())
                .map(o -> o.task().id() + "：" + String.join("；", o.problems()))
                .toList();
        assertFalse(!failures.isEmpty(),
                "提示词回归未通过（详情见 " + file.toAbsolutePath() + "）：\n  "
                        + String.join("\n  ", failures));
    }

    // ---------- 跑一条 ----------

    private RegressionReport.Outcome runTask(PromptTask task, AppConfig config,
                                             ModelConfig model, Path workspace) {
        Scenario scenario = Scenario.byId(task.scenario());
        assertTrue(scenario != null, "未知场景：" + task.scenario());
        Scenario.Member member = scenario.members().get(0);

        CodeWorkspace ws = CodeWorkspace.forUserSelection(workspace);
        ChatAgent.Builder builder = ChatAgent
                .streamingBuilder(member.key(), model.newStreamingChatModel())
                // 与 AgentWebServer.buildTeam 的 general 分支逐字对齐
                .systemMessage(member.system() + "\n\n"
                        + CodePrompt.readOnlyHint(ws, Platform.current()))
                .retryPolicy(config.retryPolicy())
                .toolRegistry(CodeTools.readOnly(ws))
                .maxToolIterations(20);

        AgentRuntime runtime = new AgentRuntime();
        AgentId id = runtime.register(member.role(), member.key(), builder.build());
        Team team = new RoundRobinTeam(runtime, List.of(id))
                .onStop(StopCondition.textMention(scenario.stopWord()))
                .maxRounds(1);

        long startedAt = System.currentTimeMillis();
        TaskResult result;
        // 单条限时：一条任务把整批拖死是真实发生过的（模型读了 6 万字符的目录之后，
        // 每轮生成从 20 秒变 5 分钟，20 次迭代就是两小时）。没有这条，回归集会变成一个
        // 谁都不敢在提交前跑的东西——而不敢跑的工具等于没有。
        var future = WORKER.submit(() -> team.run(task.question()));
        try {
            result = future.get(taskTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            return new RegressionReport.Outcome(task, 0, System.currentTimeMillis() - startedAt, "",
                    List.of("超时：单条超过 " + taskTimeoutSeconds() + " 秒没跑完。两种常见原因——"
                            + "模型本身太慢（本机 CPU 跑 7B 时，单次生成就能超过这个数），"
                            + "或者上下文被一次读整目录撑爆（那之后每轮生成都慢十倍）"),
                    List.of());
        } catch (java.util.concurrent.ExecutionException e) {
            // 模型层炸了也要留一条记录：报告里"这条没跑起来"比整批中断有用
            Throwable cause = e.getCause() == null ? e : e.getCause();
            String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
            return new RegressionReport.Outcome(task, 0, System.currentTimeMillis() - startedAt,
                    "", List.of("运行失败：" + message), List.of());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new RegressionReport.Outcome(task, 0, System.currentTimeMillis() - startedAt,
                    "", List.of("被中断"), List.of());
        }
        long millis = System.currentTimeMillis() - startedAt;

        List<String> toolsUsed = new ArrayList<>();
        int toolCalls = 0;
        for (AgentEvent event : result.events()) {
            if (event instanceof AgentEvent.ToolCallRequested requested) {
                toolCalls++;
                toolsUsed.add(requested.tool() + " " + shorten(requested.arguments(), 60));
            }
        }
        String answer = result.lastText() == null ? "" : result.lastText();
        return new RegressionReport.Outcome(task, toolCalls, millis, answer,
                judge(task, toolCalls, answer), toolsUsed);
    }

    /**
     * 判定。
     *
     * <p>五条，按"最能说明退化"的顺序排：工具调用次数、必须出现的事实、字数、计划句收尾。
     * 都为的是同一个问题——**它到底看没看文件**。</p>
     */
    static List<String> judge(PromptTask task, int toolCalls, String answer) {
        List<String> problems = new ArrayList<>();
        if (toolCalls < task.minToolCalls()) {
            problems.add("工具调用 " + toolCalls + " 次，少于期望的 " + task.minToolCalls()
                    + "（大概率在凭记忆答）");
        }
        if (task.maxToolCalls() >= 0 && toolCalls > task.maxToolCalls()) {
            problems.add("工具调用 " + toolCalls + " 次，多于期望的 " + task.maxToolCalls()
                    + "（这条本该直接回答）");
        }
        String lower = answer.toLowerCase();
        List<String> missing = task.mustMention().stream()
                .filter(need -> !lower.contains(need.toLowerCase()))
                .toList();
        if (!missing.isEmpty()) {
            problems.add("答案里缺 " + missing + "（要么没读到，要么在编）");
        }
        if (answer.length() < task.minAnswerChars()) {
            problems.add("回答只有 " + answer.length() + " 字，少于 " + task.minAnswerChars());
        }
        if (endsWithPlan(answer)) {
            problems.add("以计划句收尾（说要去做，但没做）");
        }
        return problems;
    }

    /**
     * 是否以"接下来我将…"这类计划句收尾。
     *
     * <p>刻意不复用 {@code ChatAgent.endsWithPromise}：那边是**被测代码**的兜底逻辑，
     * 用它做判据等于自己证明自己对。这里独立实现一份，宁可口径略有出入，
     * 也要保证"检查和被检查"是两段代码。</p>
     *
     * <p>只看最后一句，且要求三个条件同时成立（将来时成分 + 第一人称 + 动作动词）——
     * 只看关键词会误伤「接下来要改的是配置。」这种结论句。</p>
     */
    static boolean endsWithPlan(String answer) {
        if (answer == null || answer.isBlank()) {
            return false;
        }
        // 先剥掉末尾的标点，再找"最后一个句号"——否则"…pom.xml。分析结果：……。"这种
        // 会把两段拼成一句，前半句的"接下来我将"就把一条完整的回答判成了计划句
        String text = answer.strip();
        while (!text.isEmpty() && "。！？!?.".indexOf(text.charAt(text.length() - 1)) >= 0) {
            text = text.substring(0, text.length() - 1);
        }
        int cut = Math.max(
                Math.max(text.lastIndexOf('。'), text.lastIndexOf('！')),
                Math.max(text.lastIndexOf('?'), text.lastIndexOf('？')));
        String last = cut >= 0 ? text.substring(cut + 1) : text;
        if (last.isBlank()) {
            return false;
        }
        boolean future = last.contains("接下来") || last.contains("下一步")
                || last.contains("下面我") || last.contains("稍后")
                // 模型很爱换口成"我会使用 xxx 来…"，漏了这两种就等于没判
                || last.contains("我将") || last.contains("我会");
        boolean firstPerson = last.contains("我");
        boolean action = last.contains("分析") || last.contains("查看") || last.contains("读取")
                || last.contains("检查") || last.contains("搜索") || last.contains("确认")
                || last.contains("整理") || last.contains("汇总") || last.contains("展开")
                || last.contains("查找");
        return future && firstPerson && action;
    }

    // ---------- 环境 ----------

    /**
     * 要跑哪些任务。
     *
     * <p>{@code -Dregression.only=java-version,packages} 只跑指定的几条。调试提示词时用得上：
     * 改了一句话就重跑十分钟全量，没人会真的这么做，最后就变成"改完跑一次看看"，
     * 而"看一次"看不出退化。</p>
     */
    private static List<PromptTask> selected() {
        String only = System.getProperty("regression.only", "").strip();
        if (only.isEmpty()) {
            return PromptTasks.all();
        }
        java.util.Set<String> ids = java.util.Set.of(only.split("\\s*,\\s*"));
        List<PromptTask> picked = PromptTasks.all().stream()
                .filter(t -> ids.contains(t.id()))
                .toList();
        assertFalse(picked.isEmpty(), "regression.only 没匹配到任何任务：" + only);
        return picked;
    }

    private static Path workspace() {
        String configured = System.getProperty("regression.workspace",
                System.getenv("REGRESSION_WORKSPACE"));
        if (configured != null && !configured.isBlank()) {
            // 显式指定目录时不动它：那多半是别人的工程，只读工具也改不了什么
            return Path.of(configured).toAbsolutePath().normalize();
        }
        return sanitizedCopy();
    }

    /**
     * 把当前仓库复制一份"干净"的给模型看，去掉写着答案的那些目录。
     *
     * <p>不复制的话这套回归集可以被搜出答案来——实测就撞上了：问「哪段代码负责 SSE 断点续传」，
     * 它回了<em>「在 prompt-regression.md 第 150 行，有提到类 sse-resume-class」</em>。
     * 任务清单、判定常量（`6000`、`1.22`、四个 code）全都写在 {@code src/test} 和
     * {@code docs/prompt-regression.md} 里，而它们和被测代码在同一个工作区、同一个
     * {@code search_code} 能搜到的范围内。<b>答案键和考卷放在同一个房间，这场考试就没意义。</b></p>
     *
     * <p>复制到 {@code target/} 下还有个附带好处：模型再怎么乱读也碰不到源码，
     * 不会因为一次误操作把工作区搞脏。</p>
     */
    private static Path sanitizedCopy() {
        Path src = Path.of("").toAbsolutePath().normalize();
        Path dst = REPORT_DIR.resolve("workspace");
        try {
            deleteTree(dst);
            Files.createDirectories(dst);
            java.util.Set<String> skipDirs = java.util.Set.of(
                    ".git", "target", "node_modules", ".idea", ".workbuddy", "logs", "dist",
                    // 答案键：测试代码里有 mustMention 的字面值，docs 里有任务表和判定依据
                    "docs");
            Files.walkFileTree(src, new java.nio.file.SimpleFileVisitor<>() {
                @Override
                public java.nio.file.FileVisitResult preVisitDirectory(Path dir,
                        java.nio.file.attribute.BasicFileAttributes attrs) throws java.io.IOException {
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    // 只跳 src/test，别把名字里带 test 的正常目录也跳掉
                    boolean isTestDir = name.equals("test") && dir.getParent() != null
                            && dir.getParent().getFileName() != null
                            && dir.getParent().getFileName().toString().equals("src");
                    if (skipDirs.contains(name) || isTestDir) {
                        return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                    }
                    Files.createDirectories(dst.resolve(src.relativize(dir).toString()));
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFile(Path file,
                        java.nio.file.attribute.BasicFileAttributes attrs) throws java.io.IOException {
                    Files.copy(file, dst.resolve(src.relativize(file).toString()));
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
            return dst;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("准备干净工作区失败：" + e.getMessage(), e);
        }
    }

    private static void deleteTree(Path dir) throws java.io.IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static ModelConfig model() {
        String name = System.getProperty("regression.model", DEFAULT_MODEL);
        String baseUrl = System.getProperty("regression.base-url", DEFAULT_BASE_URL);
        // 温度 0：回归要可复现，同一句问题不该因为采样随机而一次过一个不过
        // 超时同时设到模型客户端上：只靠外层 future.cancel 是**假超时**——线程被放弃了，
        // 但那条 HTTP 请求还在生成，继续占着模型，后面每一条都排在它后面（实测第 9 条超时后，
        // 本该 1 秒的第 10 条也卡住了）。让请求自己断掉才是真超时
        return new ModelConfig("ollama", baseUrl, name, "ollama",
                0.0, java.time.Duration.ofSeconds(taskTimeoutSeconds()));
    }

    /** 上一次全绿的基线；没有就返回空表（报告里那一列留空，不判失败）。 */
    private static Map<String, Integer> baseline() {
        try (var in = PromptRegressionTest.class.getResourceAsStream("/prompt-baseline.json")) {
            if (in == null) {
                return Map.of();
            }
            var root = MAPPER.readTree(in.readAllBytes());
            Map<String, Integer> tools = new LinkedHashMap<>();
            root.path("tools").fields().forEachRemaining(e -> tools.put(e.getKey(), e.getValue().asInt()));
            return tools;
        } catch (Exception e) {
            System.out.println("读基线失败（忽略）：" + e.getMessage());
            return Map.of();
        }
    }

    private static String shorten(String text, int max) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ").strip();
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "…";
    }
}
