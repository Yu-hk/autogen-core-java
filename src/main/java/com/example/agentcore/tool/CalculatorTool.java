package com.example.agentcore.tool;

import dev.langchain4j.agent.tool.Tool;

/** 演示用计算器工具：验证"模型请求工具 → 执行 → 结果回灌"这条链路。 */
public class CalculatorTool {

    @Tool("两个整数相加，返回和")
    public int add(int a, int b) {
        return a + b;
    }

    @Tool("两个整数相乘，返回积")
    public int multiply(int a, int b) {
        return a * b;
    }

    @Tool("按需求里的关键信号估算复杂度，返回 1（简单）到 5（复杂）")
    public int estimateComplexity(String requirement) {
        if (requirement == null || requirement.isBlank()) {
            return 1;
        }
        int score = 1;
        // 按信号加分，而不是按字数：真实模型传进来的需求往往很短，
        // 用长度打分会让"百万级导出"只得 2 分，看起来像工具坏了
        score += has(requirement, "百万", "千万", "亿", "海量", "大数据") ? 1 : 0;
        score += has(requirement, "分片", "分页", "游标", "批量", "并发") ? 1 : 0;
        score += has(requirement, "异步", "队列", "任务", "重试", "幂等") ? 1 : 0;
        score += has(requirement, "权限", "鉴权", "审计", "限流", "监控", "告警") ? 1 : 0;
        return Math.min(5, score);
    }

    private static boolean has(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
