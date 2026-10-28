package com.example.agentcore.web;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

/**
 * 进程内指标，按 Prometheus 文本格式输出。
 *
 * <p>为什么自己写而不是引 Micrometer：这个服务只暴露一个 {@code /api/metrics}，
 * 需要的是"现在几个任务在跑、跑了多久、失败了几次"，十几个计数器就够了；
 * 引一套指标框架再配 registry，收益不如把这几行数清楚。</p>
 *
 * <p><b>刻意控制标签基数。</b>停止原因原本是自由文本（"达到最大轮次 4"、"命中终止词 APPROVE"），
 * 直接当 label 会让时序数量随每次运行增长，Prometheus 那边很快就会爆。所以这里先把
 * 停止原因归成 5 类再打标签；原始文本仍在运行日志里，排查时去看日志。</p>
 *
 * <p>线程安全：全部用 {@link LongAdder}/{@link DoubleAdder}，SSE 与后台任务会并发写。</p>
 */
final class Metrics {

    /** 任务耗时直方图的分桶（秒）。纯 CPU 上一次任务 3–9 分钟，档位往上给足。 */
    private static final double[] BUCKETS = {5, 15, 30, 60, 120, 300, 600, 1800};

    private final Map<String, LongAdder> httpByStatus = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> runsByReason = new ConcurrentHashMap<>();
    private final LongAdder runsStarted = new LongAdder();
    private final LongAdder runFailures = new LongAdder();
    private final LongAdder toolCalls = new LongAdder();
    private final LongAdder modelRetries = new LongAdder();
    private final LongAdder sseStreams = new LongAdder();
    private final LongAdder sseResumes = new LongAdder();
    private final LongAdder durationCount = new LongAdder();
    private final DoubleAdder durationSum = new DoubleAdder();
    private final LongAdder[] durationBuckets = new LongAdder[BUCKETS.length];
    private final long startedAt = System.currentTimeMillis();

    Metrics() {
        for (int i = 0; i < durationBuckets.length; i++) {
            durationBuckets[i] = new LongAdder();
        }
    }

    void http(int status) {
        httpByStatus.computeIfAbsent(String.valueOf(status), k -> new LongAdder()).increment();
    }

    void runStarted() {
        runsStarted.increment();
    }

    /** @param stopReason 原始停止原因，内部归一化后再当 label */
    void runFinished(String stopReason, long millis) {
        runsByReason.computeIfAbsent(classify(stopReason), k -> new LongAdder()).increment();
        double seconds = millis / 1000.0;
        durationCount.increment();
        durationSum.add(seconds);
        for (int i = 0; i < BUCKETS.length; i++) {
            if (seconds <= BUCKETS[i]) {
                durationBuckets[i].increment();
            }
        }
    }

    void runFailed(long millis) {
        runFailures.increment();
        // 失败的耗时也要进直方图，否则 p99 只统计了成功的那些，看起来比实际乐观
        runFinished("error", millis);
    }

    void toolCall() {
        toolCalls.increment();
    }

    void modelRetry() {
        modelRetries.increment();
    }

    /** 带游标重连的次数。它和 {@link #sseStream()} 的差值就是「断过几次」，内网链路质量看这个。 */
    void sseResume() {
        sseResumes.increment();
    }

    void sseStream() {
        sseStreams.increment();
    }

    /**
     * 把"达到最大轮次 4"这类自由文本归成有限几类。
     *
     * <p>归不进去的一律 {@code other}——宁可少一个维度，也不让 label 的取值集合无限增长。</p>
     */
    static String classify(String stopReason) {
        String text = stopReason == null ? "" : stopReason.trim();
        if (text.isEmpty()) {
            return "unknown";
        }
        if (text.startsWith("error")) {
            return "error";
        }
        if (text.contains("最大轮次") || text.startsWith("max")) {
            return "max_rounds";
        }
        if (text.contains("终止词") || text.contains("命中")) {
            return "stop_word";
        }
        if (text.contains("超时") || text.contains("timeout")) {
            return "timeout";
        }
        if (text.contains("停止") || text.contains("external")) {
            return "external";
        }
        return "other";
    }

    /** 渲染成 Prometheus 文本格式（{@code text/plain; version=0.0.4}）。 */
    String render(int conversations, int activeRuns, int maxConcurrentRuns) {
        StringBuilder sb = new StringBuilder(2048);
        gauge(sb, "agentcore_uptime_seconds", "进程已运行秒数",
                (System.currentTimeMillis() - startedAt) / 1000.0);
        gauge(sb, "agentcore_active_runs", "正在运行的任务数", activeRuns);
        gauge(sb, "agentcore_max_concurrent_runs", "允许同时运行的任务上限", maxConcurrentRuns);
        gauge(sb, "agentcore_conversations", "内存中缓存的会话数", conversations);

        counter(sb, "agentcore_runs_started_total", "累计启动的运行数", runsStarted.sum());
        counter(sb, "agentcore_run_failures_total", "累计异常结束的运行数", runFailures.sum());
        counter(sb, "agentcore_tool_calls_total", "累计工具调用数", toolCalls.sum());
        counter(sb, "agentcore_model_retries_total", "累计模型重试次数", modelRetries.sum());
        counter(sb, "agentcore_sse_streams_total", "累计 SSE 连接数", sseStreams.sum());
        counter(sb, "agentcore_sse_resumes_total", "带游标重连（断点续传）次数", sseResumes.sum());

        sb.append("# HELP agentcore_runs_finished_total 按停止原因归类的完成运行数\n")
                .append("# TYPE agentcore_runs_finished_total counter\n");
        for (Map.Entry<String, LongAdder> entry : new TreeMap<>(runsByReason).entrySet()) {
            sb.append("agentcore_runs_finished_total{reason=\"").append(entry.getKey()).append("\"} ")
                    .append(fmt(entry.getValue().sum())).append('\n');
        }

        sb.append("# HELP agentcore_http_requests_total 按状态码统计的接口请求数\n")
                .append("# TYPE agentcore_http_requests_total counter\n");
        for (Map.Entry<String, LongAdder> entry : new TreeMap<>(httpByStatus).entrySet()) {
            sb.append("agentcore_http_requests_total{status=\"").append(entry.getKey()).append("\"} ")
                    .append(fmt(entry.getValue().sum())).append('\n');
        }

        sb.append("# HELP agentcore_run_duration_seconds 运行耗时分布\n")
                .append("# TYPE agentcore_run_duration_seconds histogram\n");
        long cumulative = 0;
        for (int i = 0; i < BUCKETS.length; i++) {
            cumulative = durationBuckets[i].sum();
            sb.append("agentcore_run_duration_seconds_bucket{le=\"").append(fmt(BUCKETS[i]))
                    .append("\"} ").append(fmt(cumulative)).append('\n');
        }
        sb.append("agentcore_run_duration_seconds_bucket{le=\"+Inf\"} ")
                .append(fmt(durationCount.sum())).append('\n')
                .append("agentcore_run_duration_seconds_sum ").append(fmt(durationSum.sum())).append('\n')
                .append("agentcore_run_duration_seconds_count ").append(fmt(durationCount.sum()))
                .append('\n');

        Runtime runtime = Runtime.getRuntime();
        long heapUsed = runtime.totalMemory() - runtime.freeMemory();
        gauge(sb, "jvm_memory_used_bytes", "JVM 已用堆内存", heapUsed);
        gauge(sb, "jvm_memory_committed_bytes", "JVM 已申请堆内存", runtime.totalMemory());
        gauge(sb, "jvm_memory_max_bytes", "JVM 最大可用堆内存", runtime.maxMemory());
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        gauge(sb, "jvm_nonheap_used_bytes", "JVM 非堆内存（元空间/代码缓存）",
                memory.getNonHeapMemoryUsage().getUsed());
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        gauge(sb, "jvm_threads_live", "存活线程数", threads.getThreadCount());
        return sb.toString();
    }

    private static void counter(StringBuilder sb, String name, String help, long value) {
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n')
                .append("# TYPE ").append(name).append(" counter\n")
                .append(name).append(' ').append(fmt(value)).append('\n');
    }

    private static void gauge(StringBuilder sb, String name, String help, double value) {
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n')
                .append("# TYPE ").append(name).append(" gauge\n")
                .append(name).append(' ').append(fmt(value)).append('\n');
    }

    /** Prometheus 要求浮点；整数值也写成 {@code 12.0} 更省事（避免 "12" 被当成整数解析问题）。 */
    private static String fmt(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return String.valueOf((long) value) + ".0";
        }
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }
}
