package com.example.agentcore;

import com.example.agentcore.support.AppConfig;
import com.example.agentcore.support.MockChatServer;
import com.example.agentcore.support.ModelConfig;
import com.example.agentcore.web.AgentWebServer;
import com.example.agentcore.web.ModelSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可观测性三件事的验收：指标口径、traceId 贯通、结构化日志落地。
 *
 * <p>这些能力最容易"看起来做了"：接口有响应、日志文件存在，但数字是错的、
 * traceId 每行都不一样。所以这里断言的是具体数值与具体字段，而不是"接口返回 200"。</p>
 */
@Timeout(60)
class ObservabilityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .proxy(ProxySelector.of(null))
            .build();

    private static AppConfig config(Path logs) {
        Properties props = new Properties();
        props.setProperty("web.log-dir", logs.toAbsolutePath().normalize().toString());
        props.setProperty("web.data-dir", logs.resolve("data").toString());
        props.setProperty("web.structured-log", "true");
        return AppConfig.of(props);
    }

    private static ModelSource source(MockChatServer mock) {
        return () -> ModelConfig.forMock(mock.baseUrl());
    }

    /**
     * 跑一次之后，指标要能反映"跑了一次、耗时可查、没有失败"。
     *
     * <p>停止原因必须被归一化成 {@code max_rounds} 而不是原文案——标签基数这件事
     * 一旦放松，Prometheus 侧的时序数量就会随每次运行增长。</p>
     */
    @Test
    void metricsReflectRunsAndStayBounded(@TempDir Path logs, @TempDir Path snaps) throws Exception {
        try (MockChatServer mock = MockChatServer.start();
             AgentWebServer web = AgentWebServer.start(0, snaps, source(mock), config(logs))) {
            // 跑之前：计数为 0，但指标本身可读（探活脚本会先抓一次）
            String before = plain(web.url() + "/api/metrics");
            assertTrue(before.contains("agentcore_runs_started_total 0.0"), before);

            mock.enqueueText("好的。");
            String sessionId = post(web.url() + "/api/run?scenario=review&maxRounds=1&task="
                    + enc("随便说一句")).path("sessionId").asText();
            drain(web.url() + "/api/stream?sessionId=" + sessionId);

            String text = plain(web.url() + "/api/metrics");
            assertTrue(text.contains("agentcore_runs_started_total 1.0"),
                    "应记录一次启动：" + text);
            assertTrue(text.contains("agentcore_runs_finished_total{reason=\"max_rounds\"} 1.0"),
                    "停止原因应归一化成 max_rounds：" + text);
            assertTrue(text.contains("agentcore_run_failures_total 0.0"), text);
            assertTrue(text.contains("agentcore_run_duration_seconds_count 1.0"),
                    "耗时应进直方图：" + text);
            assertTrue(text.contains("agentcore_run_duration_seconds_bucket{le=\"+Inf\"} 1.0"), text);
            assertTrue(text.contains("agentcore_http_requests_total{status=\"200\"}"), text);
            assertTrue(text.contains("agentcore_conversations 1.0"), "跑完应有一段会话在内存里：" + text);
            assertTrue(text.contains("agentcore_active_runs 0.0"), "跑完名额必须还回去：" + text);
            // JVM 基础指标在，运维接入面板时不用再改代码
            assertTrue(text.contains("jvm_memory_used_bytes"), text);
            assertTrue(text.contains("jvm_threads_live"), text);
        }
    }

    /** 请求 id 必须是"一个请求一个"：既要有，也要每次不同。 */
    @Test
    void everyResponseCarriesUniqueRequestId(@TempDir Path logs, @TempDir Path snaps)
            throws Exception {
        try (AgentWebServer web = AgentWebServer.start(0, snaps, ModelSource.configured(),
                config(logs))) {
            String first = headerOf(web.url() + "/api/health", "x-request-id");
            String second = headerOf(web.url() + "/api/health", "x-request-id");
            assertFalse(first.isBlank(), "响应必须带 X-Request-Id");
            assertEquals(8, first.length(), "id 长度固定 8 位：" + first);
            assertNotEquals(first, second, "两次请求不能共用一个 id");

            // 会话详情、指标这些接口同样要带，否则断链的那一段恰好没有 id
            assertEquals(8, headerOf(web.url() + "/api/metrics", "x-request-id").length());
            assertEquals(8, headerOf(web.url() + "/api/conversations", "x-request-id").length());
        }
    }

    /** 访问日志按请求一行，且能按 requestId 与运行日志对上。 */
    @Test
    void accessLogAndRunLogShareTraceId(@TempDir Path logs, @TempDir Path snaps) throws Exception {
        String requestId;
        try (MockChatServer mock = MockChatServer.start();
             AgentWebServer web = AgentWebServer.start(0, snaps, source(mock), config(logs))) {
            mock.enqueueText("好的。");
            HttpResponse<String> runResponse = CLIENT.send(
                    HttpRequest.newBuilder(URI.create(web.url() + "/api/run?scenario=review&maxRounds=1"))
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            requestId = runResponse.headers().firstValue("x-request-id").orElse("");
            JsonNode started = MAPPER.readTree(runResponse.body());
            drain(web.url() + "/api/stream?sessionId=" + started.path("sessionId").asText());
        }

        List<String> access = readJsonl(logs, "access-");
        assertFalse(access.isEmpty(), "访问日志应有内容");
        boolean tracedRun = access.stream()
                .map(ObservabilityTest::parse)
                .anyMatch(node -> requestId.equals(node.path("requestId").asText())
                        && "/api/run".equals(node.path("path").asText())
                        && node.path("status").asInt() == 200);
        assertTrue(tracedRun, "访问日志里应有这次 /api/run 且带同一个 requestId：" + access);

        // SSE 是长连接，响应结束时记 0 表示"流式写完"，不该被误记成一次失败请求
        assertTrue(access.stream().map(ObservabilityTest::parse)
                        .anyMatch(node -> node.path("path").asText().equals("/api/stream")),
                "SSE 也要留痕：" + access);

        List<String> runs = readJsonl(logs, "run-");
        List<JsonNode> nodes = runs.stream().map(ObservabilityTest::parse).toList();
        assertTrue(nodes.stream().anyMatch(n -> "start".equals(n.path("event").asText())
                        && requestId.equals(n.path("requestId").asText())),
                "运行日志的 start 必须与请求同一个 traceId：" + runs);
        assertTrue(nodes.stream().anyMatch(n -> "done".equals(n.path("event").asText())
                        && n.path("messages").asInt() >= 2),
                "运行日志要记录结果与消息数：" + runs);
    }

    /** 关掉结构化日志时不该再建目录：演示机不想在磁盘上留东西。 */
    @Test
    void structuredLogCanBeDisabled(@TempDir Path logs, @TempDir Path snaps) throws Exception {
        Properties props = new Properties();
        props.setProperty("web.log-dir", logs.resolve("off").toString());
        props.setProperty("web.data-dir", logs.resolve("data-off").toString());
        props.setProperty("web.structured-log", "false");
        try (MockChatServer mock = MockChatServer.start();
             AgentWebServer web = AgentWebServer.start(0, snaps, source(mock), AppConfig.of(props))) {
            mock.enqueueText("好的。");
            String sessionId = post(web.url() + "/api/run?scenario=review&maxRounds=1")
                    .path("sessionId").asText();
            drain(web.url() + "/api/stream?sessionId=" + sessionId);
        }
        assertFalse(Files.exists(logs.resolve("off")), "关掉之后不该建日志目录");
    }

    // ---------- 辅助 ----------

    private static String enc(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }

    /** 把某个前缀当天写出的所有 jsonl 读成一个行列表（跨天时会拼上多份）。 */
    private static List<String> readJsonl(Path dir, String prefix) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> matched = files
                    .filter(p -> p.getFileName().toString().startsWith(prefix))
                    .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .collect(Collectors.toList());
            assertFalse(matched.isEmpty(), "没找到 " + prefix + "*.jsonl（目录 " + dir + "）");
            List<String> lines = new java.util.ArrayList<>();
            for (Path file : matched) {
                lines.addAll(Files.readAllLines(file, StandardCharsets.UTF_8));
            }
            return lines;
        }
    }

    private static JsonNode parse(String line) {
        try {
            return MAPPER.readTree(line);
        } catch (IOException e) {
            throw new IllegalStateException("日志行不是合法 JSON: " + line, e);
        }
    }

    private String plain(String url) throws IOException, InterruptedException {
        return CLIENT.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    private String headerOf(String url, String name) throws IOException, InterruptedException {
        return CLIENT.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                        HttpResponse.BodyHandlers.ofString())
                .headers().firstValue(name).orElse("");
    }

    private JsonNode post(String url) throws IOException, InterruptedException {
        return MAPPER.readTree(CLIENT.send(
                HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString()).body());
    }

    private List<JsonNode> drain(String url) throws IOException, InterruptedException {
        HttpResponse<Stream<String>> response = CLIENT.send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofLines());
        List<JsonNode> events = new java.util.ArrayList<>();
        try (Stream<String> lines = response.body()) {
            lines.filter(line -> line.startsWith("data: ")).forEach(line -> {
                try {
                    events.add(MAPPER.readTree(line.substring(6)));
                } catch (IOException e) {
                    throw new IllegalStateException("SSE 负载不是合法 JSON: " + line, e);
                }
            });
        }
        return events;
    }
}
