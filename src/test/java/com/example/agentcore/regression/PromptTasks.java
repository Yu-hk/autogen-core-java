package com.example.agentcore.regression;

import java.util.List;

/**
 * 回归任务清单。
 *
 * <p>选任务的三个原则：</p>
 * <ol>
 *   <li><b>像真人打字</b>。"这个项目用哪个 Java 版本"是好问题；"请调用 read_file 读取 pom.xml
 *       并提取 maven.compiler.release"测的是指令跟随，不是日常使用。</li>
 *   <li><b>答案必须是文件里的字面值</b>。这样"模型是不是真的看了文件"和"它是不是在编"可以分开——
 *       编出来的版本号不会刚好等于 17、1.22.0、6000。</li>
 *   <li><b>覆盖三种失败模式</b>：一次都不调工具（凭记忆答）、只看一个文件就收尾（回答半截）、
 *       该直接答却去翻文件（过度工具化）。</li>
 * </ol>
 *
 * <p>所有事实值都取自本仓库当前代码。改了这些常量就该同步改这里——这是好事，
 * 说明回归集真的盯着代码而不是拍脑袋。</p>
 */
final class PromptTasks {

    private PromptTasks() {
    }

    static List<PromptTask> all() {
        return List.of(
                // ---- 明确指向一个文件：不该连这个都找不到 ----
                PromptTask.of("java-version",
                        "这个项目要求用哪个 Java 版本？看一下 pom.xml 告诉我版本号。",
                        1, List.of("17"),
                        "最基础的一条：连指定文件都读不到的模型，后面所有分析都不可信"),

                PromptTask.of("dependency-version",
                        "pom.xml 里 langchain4j 依赖用的是哪个版本？",
                        1, List.of("1.22"),
                        "验证它读到的是 properties 里的变量值，而不是把 ${langchain4j.version} 原样抄回来"),

                // ---- 需要看目录结构 ----
                // 下限是 1 不是 2：list_files 一次就能列出全部子项，调两次纯属浪费。
                // 这里守的是"有没有看目录"，不是"调得够不够多"——判据写偏了会把答对的人判成错的。
                PromptTask.of("packages",
                        "src/main/java/com/example/agentcore 下面有哪些子包？列出来。",
                        1, List.of("web", "tool", "agent"),
                        "要看得见目录才答得出；一次工具都不调就编出包名是典型退化"),

                // ---- 单点细节：得先定位文件再读内容 ----
                PromptTask.of("stream-timeout",
                        "ChatAgent 里流式调用最多等多久？我要分钟数。",
                        1, List.of("10"),
                        "从「哪个类」到「哪个常量」要跨一步；顺序反了就会答成别的超时"),

                PromptTask.of("sse-resume-class",
                        "哪段代码负责 SSE 断点续传？说下是哪个类。",
                        2, List.of("runsession"),
                        "概念到代码的映射：光搜关键词不够，得看实现才能确认是环形日志那套"),

                PromptTask.of("max-retained",
                        "RunSession 保留窗口的最大条数是多少？",
                        1, List.of("6000"),
                        "数字类事实最能验证「真读了」——3B 编数字很顺，编对这个数很难"),

                // ---- 需要综合多处：最容易被回答成半截的一类 ----
                PromptTask.of("error-codes",
                        "失败类型 ErrorCode 一共有哪几种？把 code 字符串列出来。",
                        1, List.of("model_unavailable", "timeout", "bad_request", "internal"),
                        "四个值必须齐；少一个通常意味着它只读了枚举的一半就下结论"),

                PromptTask.of("project-overview",
                        "这个项目整体是做什么的？用几句话概括，说明你看了哪些文件。",
                        2, List.of("agent"),
                        "最贴近真实用法的一问（「分析一下这个项目」）；也是历史 bug 的原发地——"
                                + "曾经答到「接下来我将分析…」就交卷"),

                // ---- 计数类：工具返回的就是真值 ----
                // 数的是单个目录，不是整个 src/main/java：后者要一路 list 到底，只读工具集里
                // 又没有"统计"能力，模型面对 69 个文件只能自称数不清——那测的是工具缺口，不是模型。
                PromptTask.of("web-file-count",
                        "src/main/java/com/example/agentcore/web 这个目录下有几个 .java 文件？",
                        1, List.of("13"),
                        "数数必须靠工具；凭感觉给的数字通常整十整百，编不出 13 这么零碎的数"),

                // ---- 反向用例：这条不该翻文件 ----
                new PromptTask("smalltalk",
                        "简单说说 SSE 是什么，一句话就行，不用查项目里的代码。",
                        "general",
                        0, 2,
                        List.of("服务器"),
                        10,
                        "过度工具化的反向用例：概念题也去翻文件，说明提示词把「多用工具」推过头了")
        );
    }
}
