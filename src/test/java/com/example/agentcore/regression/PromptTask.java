package com.example.agentcore.regression;

import java.util.List;

/**
 * 一条提示词回归任务：一句真实用户会问的话 + 一组机器可判的期望。
 *
 * <p>为什么要有这个：提示词是这个服务里最像"玄学"的部分。改一句"要连续看几个文件"，
 * 模型可能从只读一个变成读十个，也可能干脆一个都不读；而这种退化<b>不会让任何现有测试变红</b>，
 * 单测测的是代码路径，不是模型行为。于是只能靠人肉试，试三个任务就以为改好了。</p>
 *
 * <p>这里把"人肉试"固化成任务清单：每条都写清楚<b>最少要调几次工具、答案里必须出现哪些
 * 来自真实文件的事实</b>。改完提示词跑一遍，数字下降就是退化，不用争论。</p>
 *
 * @param id        短标识，报告里用它对齐基线
 * @param question  原样发给模型的问题（要像真人打字，不要写成指令清单——那样测的是指令跟随，不是日常使用）
 * @param scenario  用哪套装配（{@code general} = 只读四件套，{@code coding} = 全套工具）
 * @param minToolCalls 至少调用几次工具；0 表示"这条不该调工具"
 * @param maxToolCalls 至多调用几次；-1 不限
 * @param mustMention  答案里必须出现的字符串（小写比较）；空表示只查工具调用
 * @param minAnswerChars 最终回答的最少字符数，用来抓"一句话交卷"
 * @param why       这条守的是什么——失败时人一眼知道该往哪修，而不是只看到一个数字不对
 */
record PromptTask(
        String id,
        String question,
        String scenario,
        int minToolCalls,
        int maxToolCalls,
        List<String> mustMention,
        int minAnswerChars,
        String why) {

    /** 最常用的写法：不限制上限、不查字数（用默认的 30 字防空洞回答）。 */
    static PromptTask of(String id, String question, int minToolCalls, List<String> mustMention, String why) {
        return new PromptTask(id, question, "general", minToolCalls, -1, mustMention, 30, why);
    }
}
