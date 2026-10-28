package com.example.agentcore.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 失败要有类型，而且类型要能一路走到页面。
 *
 * <p>这是"错误只有一句话"的直接解药：以前模型没启动、任务跑超时、参数传错，用户在页面上
 * 看到的是同一条红字，只能挨个试。现在至少能告诉他是"模型没起来，重发也没用"还是
 * "跑太久了，缩小范围再发一次"。</p>
 *
 * <p>断言三件事：{@code code} 出现在 SSE 事件里、{@code requestId} 能对回发起运行的那次
 * HTTP 请求、指标里失败按 code 分开计数。第二件尤其容易做漏——分类是在后台线程里做的，
 * {@code ThreadLocal} 早就没了，不显式传递就只能拿到 {@code "-"}。</p>
 */
@Timeout(60)
class ErrorEventTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .proxy(ProxySelector.of(null))
            .build();

    /** 模型解析就失败：等价于 Ollama 没起、或 api-key 没配。 */
    private static ModelSource failing() {
        return () -> {
            throw new ModelUnavailable("连不上 http://127.0.0.1:11434，先执行 ollama serve");
        };
    }

    @Test
    void modelUnavailableCarriesCodeAndRequestId(@TempDir Path dir) throws Exception {
        try (AgentWebServer web = AgentWebServer.start(0, dir, failing())) {
            HttpResponse<String> run = post(web.url() + "/api/run?scenario=review");
            assertEquals(200, run.statusCode(), "接受任务本身是成功的，失败在事件流里报告：" + run.body());
            String requestId = run.headers().firstValue("x-request-id").orElse("");
            assertFalse(requestId.isBlank(), "发起运行必须带 X-Request-Id");
            String sessionId = MAPPER.readTree(run.body()).path("sessionId").asText();

            List<JsonNode> events = drain(web.url() + "/api/stream?sessionId=" + sessionId);
            JsonNode error = events.stream()
                    .filter(e -> "error".equals(e.path("kind").asText()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("应有 error 事件：" + events));

            assertEquals("model_unavailable", error.path("code").asText(),
                    "模型不可用要单独一类：重发原样还是失败，得让用户知道该去找人：" + error);
            assertEquals(requestId, error.path("requestId").asText(),
                    "错误事件要能对回发起它的请求，否则用户报障时无从查起：" + error);
            assertTrue(error.path("message").asText().contains("ollama serve"),
                    "原文要保留，别为了分类把可操作的信息丢掉：" + error);

            // 收尾事件之后流必须结束，页面才不会一直转圈等下一句
            assertTrue(error.path("seq").asLong() > 0, "事件要带序号（续传依赖它）");
            assertEquals("error", events.get(events.size() - 1).path("kind").asText(),
                    "error 是最后一条");

            String metrics = get(web.url() + "/api/metrics");
            assertTrue(metrics.contains("agentcore_run_failures_total{code=\"model_unavailable\"} 1.0"),
                    "失败要按类型计数，运维才知道该修哪一头：" + metrics);
        }
    }

    /** 指标口径：不同 code 各自成 series，不互相覆盖。 */
    @Test
    void failureMetricsAreSplitByCode() {
        Metrics metrics = new Metrics();
        metrics.runFailed("timeout", 1200);
        metrics.runFailed("timeout", 800);
        metrics.runFailed("bad_request", 30);

        String text = metrics.render(0, 0, 2);
        assertTrue(text.contains("agentcore_run_failures_total{code=\"timeout\"} 2.0"), text);
        assertTrue(text.contains("agentcore_run_failures_total{code=\"bad_request\"} 1.0"), text);
        // 失败同样要进耗时直方图，否则 p99 只统计成功的那部分，看起来比实际乐观
        assertTrue(text.contains("agentcore_run_duration_seconds_count 3.0"), text);
    }

    /** 老调用方式（只给一句话）不能被悄悄当成成功，兜底必须是 internal。 */
    @Test
    void plainErrorDefaultsToInternal() throws Exception {
        RunSession session = new RunSession("abc12345");
        session.error("没有可用的起始消息");

        RunSession.Entry entry = session.entriesAfter(0).get(0);
        JsonNode node = MAPPER.readTree(entry.payload());
        assertEquals("internal", node.path("code").asText(), entry.payload());
        assertFalse(node.has("requestId"), "没有 id 就不写这个字段，别塞一个空串进去");
        assertTrue(entry.terminal(), "error 必须收尾");
    }

    // ---------- 辅助 ----------

    private static HttpResponse<String> post(String url) throws IOException, InterruptedException {
        return CLIENT.send(
                HttpRequest.newBuilder(URI.create(url))
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .header("Content-Type", "application/json")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String get(String url) throws IOException, InterruptedException {
        return CLIENT.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    /** 读到流结束，收集所有 data 帧。 */
    private static List<JsonNode> drain(String url) throws IOException, InterruptedException {
        HttpResponse<InputStream> response = CLIENT.send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode(), "SSE 恒为 200，失败也是事件");

        List<JsonNode> events = new java.util.ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data: ")) {
                    events.add(MAPPER.readTree(line.substring(6)));
                }
            }
        }
        return events;
    }
}
