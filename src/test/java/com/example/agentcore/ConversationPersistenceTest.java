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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话落库的端到端验证：重启进程之后，同一段对话还能接着聊。
 *
 * <p>这里刻意用"关掉一个服务、用同一个数据目录再起一个"来模拟重启——这是唯一能证明
 * 上下文真的来自磁盘的方式。只在同一个进程里问两次，内存缓存就能让用例通过，
 * 而那条路恰好是重启后走不通的那条。</p>
 */
@Timeout(60)
class ConversationPersistenceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .proxy(ProxySelector.of(null))
            .build();

    private static ModelSource source(MockChatServer mock) {
        return () -> ModelConfig.forMock(mock.baseUrl());
    }

    private static AppConfig dataDir(Path dir) {
        Properties props = new Properties();
        props.setProperty("web.data-dir", dir.toAbsolutePath().normalize().toString());
        props.setProperty("web.conversation-store", "file");
        return AppConfig.of(props);
    }

    /**
     * 第一次运行 → 关服务 → 新进程用同一数据目录 → 带着旧 conversationId 追问。
     *
     * <p>断言三件事：仍然算追问、模型收到的是完整上文、会话 id 没有被换掉。
     * 只看"能跑完"是不够的——把上文弄丢也照样能跑完。</p>
     */
    @Test
    void followUpSurvivesRestart(@TempDir Path data, @TempDir Path snaps1, @TempDir Path snaps2)
            throws Exception {
        String conversationId;
        try (MockChatServer mock = MockChatServer.start()) {
            mock.enqueueText("橘子洲位于长沙市岳麓区，坐标 112.961998,28.186938。");
            try (AgentWebServer web = AgentWebServer.start(0, snaps1, source(mock), dataDir(data))) {
                JsonNode started = post(web.url() + "/api/run?task=" + enc("橘子洲的经纬度是多少")
                        + "&scenario=travel");
                conversationId = started.path("conversationId").asText();
                assertFalse(conversationId.isBlank(), "第一次运行就要拿到 conversationId");
                drain(web.url() + "/api/stream?sessionId=" + started.path("sessionId").asText());
            }
        }

        // ---- 相当于进程重启：新服务实例、新快照目录、同一个数据目录 ----
        try (MockChatServer mock = MockChatServer.start()) {
            mock.enqueueText("那第二个景点呢：岳麓山 112.936,28.185。");
            try (AgentWebServer web = AgentWebServer.start(0, snaps2, source(mock), dataDir(data))) {
                JsonNode started = post(web.url() + "/api/run?task=" + enc("那第二个呢")
                        + "&conversationId=" + conversationId);
                assertEquals(conversationId, started.path("conversationId").asText(),
                        "重启后同一段对话必须还是同一个 id");
                assertTrue(started.path("followUp").asBoolean(),
                        "重启后应仍被识别为追问，而不是新对话：" + started);

                List<JsonNode> events = drain(web.url() + "/api/stream?sessionId="
                        + started.path("sessionId").asText());
                assertTrue(events.stream().anyMatch(e -> "done".equals(e.path("kind").asText())),
                        "追问应正常跑完：" + events);

                String body = mock.lastRequest();
                assertTrue(body.contains("橘子洲的经纬度是多少"),
                        "重启后的请求里必须带着上一轮的问题：" + body);
                assertTrue(body.contains("112.961998"), "以及上一轮的回答：" + body);
            }
        }
    }

    /** 刷新页面后的恢复入口：列出最近对话、按 id 取回整段消息。 */
    @Test
    void conversationListAndDetailAreReadable(@TempDir Path data, @TempDir Path snaps)
            throws Exception {
        try (MockChatServer mock = MockChatServer.start();
             AgentWebServer web = AgentWebServer.start(0, snaps, source(mock), dataDir(data))) {
            mock.enqueueText("两个景点：橘子洲与岳麓山。");
            JsonNode started = post(web.url() + "/api/run?task=" + enc("长沙有哪些景点")
                    + "&scenario=travel");
            String conversationId = started.path("conversationId").asText();
            drain(web.url() + "/api/stream?sessionId=" + started.path("sessionId").asText());

            // 列表：只回元信息，不带正文
            JsonNode list = get(web.url() + "/api/conversations");
            assertEquals(1, list.size(), "跑完一段对话就该在列表里：" + list);
            assertEquals(conversationId, list.get(0).path("conversationId").asText());
            assertEquals("travel", list.get(0).path("scenario").asText());
            assertEquals(2, list.get(0).path("messages").asInt(), "user + assistant 两条");
            assertFalse(list.get(0).has("history"), "列表不该带正文，否则几十段就是几 MB");

            // 详情：整段消息，页面靠它把对话画回来
            JsonNode detail = get(web.url() + "/api/conversations?conversationId=" + conversationId);
            assertEquals(2, detail.path("history").size(), detail.toString());
            assertEquals("长沙有哪些景点", detail.path("history").get(0).path("text").asText());
            assertEquals("user", detail.path("history").get(0).path("source").asText());
            assertEquals("两个景点：橘子洲与岳麓山。", detail.path("history").get(1).path("text").asText());
        }
    }

    /** 会话 id 不存在时给 404——页面据此判断"这段没了"，而不是画一个空对话。 */
    @Test
    void unknownConversationReturns404(@TempDir Path data, @TempDir Path snaps) throws Exception {
        try (AgentWebServer web = AgentWebServer.start(0, snaps, ModelSource.configured(),
                dataDir(data))) {
            HttpResponse<String> res = CLIENT.send(
                    HttpRequest.newBuilder(URI.create(
                            web.url() + "/api/conversations?conversationId=deadbeef")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(404, res.statusCode(), res.body());
        }
    }

    /** 路径穿越必须挡住：这个参数来自 URL，任何人都能构造。 */
    @Test
    void traversalIdDoesNotLeakFiles(@TempDir Path data, @TempDir Path snaps) throws Exception {
        try (AgentWebServer web = AgentWebServer.start(0, snaps, ModelSource.configured(),
                dataDir(data))) {
            for (String bad : List.of("../../agent.properties", "..%2F..%2Fagent.properties")) {
                HttpResponse<String> res = CLIENT.send(
                        HttpRequest.newBuilder(URI.create(web.url() + "/api/conversations?conversationId="
                                + bad)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(404, res.statusCode(), "不该读出任何文件：" + bad);
                assertFalse(res.body().contains("tool.baidu"), "更不该把配置内容漏出来：" + res.body());
            }
        }
    }

    /** 关掉落库时要退回纯内存，且不影响正常跑。 */
    @Test
    void storeCanBeDisabled(@TempDir Path data, @TempDir Path snaps) throws Exception {
        Properties props = new Properties();
        props.setProperty("web.data-dir", data.toString());
        props.setProperty("web.conversation-store", "none");
        try (MockChatServer mock = MockChatServer.start();
             AgentWebServer web = AgentWebServer.start(0, snaps, source(mock), AppConfig.of(props))) {
            mock.enqueueText("好的。");
            JsonNode started = post(web.url() + "/api/run?task=" + enc("你好"));
            drain(web.url() + "/api/stream?sessionId=" + started.path("sessionId").asText());

            assertEquals(0, get(web.url() + "/api/conversations").size(), "关掉之后不该有落库");
        }
    }

    // ---------- 辅助 ----------

    private static String enc(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }

    private List<JsonNode> drain(String url) throws IOException, InterruptedException {
        HttpResponse<Stream<String>> response = CLIENT.send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofLines());
        List<JsonNode> events = new ArrayList<>();
        try (Stream<String> lines = response.body()) {
            lines.filter(line -> line.startsWith("data: "))
                    .forEach(line -> {
                        try {
                            events.add(MAPPER.readTree(line.substring(6)));
                        } catch (IOException e) {
                            throw new IllegalStateException("SSE 负载不是合法 JSON: " + line, e);
                        }
                    });
        }
        assertFalse(events.isEmpty(), "SSE 流不应为空");
        return events;
    }

    private JsonNode get(String url) throws IOException, InterruptedException {
        return MAPPER.readTree(CLIENT.send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body());
    }

    private JsonNode post(String url) throws IOException, InterruptedException {
        return MAPPER.readTree(CLIENT.send(
                HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString()).body());
    }
}
