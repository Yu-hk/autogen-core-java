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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服务化的三条底线：要口令、目录有边界、并发有上限。
 *
 * <p>这三条都是"单机自用无所谓、一对外就是事故"的类型，所以断言不放在模型行为上，
 * 直接打 HTTP 看状态码——状态码骗不了人。</p>
 */
@Timeout(120)
class WebSecurityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .proxy(ProxySelector.of(null))
            .build();

    private static ModelSource source(MockChatServer mock) {
        return () -> ModelConfig.forMock(mock.baseUrl());
    }

    private static AppConfig config(String... keyValues) {
        Properties props = new Properties();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            props.setProperty(keyValues[i], keyValues[i + 1]);
        }
        return AppConfig.of(props);
    }

    private static AppConfig allowDirs(Path... dirs) {
        StringBuilder sb = new StringBuilder();
        for (Path dir : dirs) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(dir.toAbsolutePath().normalize());
        }
        return config("tool.code.allow-select", "true", "tool.code.writable-dirs", sb.toString());
    }

    private static HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String enc(String text) {
        return java.net.URLEncoder.encode(text, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 配了口令：不带口令 401，带 header 或 ?token= 放行，静态页永远能打开。 */
    @Test
    void apiRequiresTokenWhenConfigured(@TempDir Path dir) throws Exception {
        try (MockChatServer mock = MockChatServer.start();
             AgentWebServer web = AgentWebServer.start(0, dir, source(mock),
                     config("server.auth-token", "s3cret"))) {

            HttpResponse<String> anonymous = send(HttpRequest.newBuilder(
                    URI.create(web.url() + "/api/health")).GET());
            assertEquals(401, anonymous.statusCode(), "没有口令必须 401：" + anonymous.body());

            HttpResponse<String> byHeader = send(HttpRequest.newBuilder(
                            URI.create(web.url() + "/api/health")).GET()
                    .header("Authorization", "Bearer s3cret"));
            assertEquals(200, byHeader.statusCode(), byHeader.body());

            // EventSource 加不了请求头，SSE 只能走查询参数，所以这条必须支持
            HttpResponse<String> byQuery = send(HttpRequest.newBuilder(
                    URI.create(web.url() + "/api/health?token=s3cret")).GET());
            assertEquals(200, byQuery.statusCode(), byQuery.body());

            HttpResponse<String> wrong = send(HttpRequest.newBuilder(
                            URI.create(web.url() + "/api/health")).GET()
                    .header("Authorization", "Bearer nope"));
            assertEquals(401, wrong.statusCode(), "口令错了同样 401");

            // 静态页不拦：先能打开页面，才有地方输入口令
            HttpResponse<String> page = send(HttpRequest.newBuilder(
                    URI.create(web.url() + "/")).GET());
            assertEquals(200, page.statusCode(), "首页不该要口令");
        }
    }

    /** 未配口令但要对外监听：直接拒绝启动，不给"忘了配"留机会。 */
    @Test
    void refusesToExposeWithoutToken(@TempDir Path dir) {
        AppConfig exposed = config("server.host", "0.0.0.0");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AgentWebServer.start(0, dir, ModelSource.configured(), exposed));
        assertTrue(e.getMessage().contains("没有设置访问口令"), e.getMessage());

        // 显式声明「前面有反代」时放行，属于逃生口
        AppConfig anonymous = config("server.host", "0.0.0.0", "server.auth-allow-anonymous", "true");
        try (AgentWebServer ignored = AgentWebServer.start(0, dir, ModelSource.configured(), anonymous)) {
            assertTrue(ignored.port() > 0);
        } catch (IOException io) {
            throw new AssertionError("显式放行时不该失败", io);
        }
    }

    /** 工作目录必须落在白名单内；目录浏览也只能在白名单内。 */
    @Test
    void workspaceIsConfinedToWhitelist(@TempDir Path dir, @TempDir Path project, @TempDir Path outside)
            throws Exception {
        Files.createDirectories(project.resolve("src"));
        try (MockChatServer mock = MockChatServer.start();
             AgentWebServer web = AgentWebServer.start(0, dir, source(mock), allowDirs(project))) {

            String task = enc("看看这个项目");
            String allowed = project.toString().replace("\\", "/");
            String denied = outside.toString().replace("\\", "/");

            JsonNode ok = post(web.url() + "/api/run?scenario=coding&task=" + task
                    + "&workspace=" + enc(allowed));
            assertFalse(ok.path("sessionId").asText().isBlank(), ok.toString());

            JsonNode blocked = post(web.url() + "/api/run?scenario=coding&task=" + task
                    + "&workspace=" + enc(denied));
            assertTrue(blocked.path("error").asText().contains("允许范围"),
                    "白名单外的目录必须拒掉：" + blocked);

            // 起点列表里只有配置过的根，不再暴露盘符/主目录
            JsonNode roots = get(web.url() + "/api/fs/list");
            Path userHome = Path.of(System.getProperty("user.home", ".")).toAbsolutePath().normalize();
            for (JsonNode entry : roots.path("entries")) {
                Path path = Path.of(entry.path("path").asText()).toAbsolutePath().normalize();
                assertTrue(path.getParent() != null, "不该把盘符根当起点：" + entry);
                assertFalse(path.equals(userHome), "不该把用户主目录当起点：" + entry);
            }

            JsonNode outsideList = get(web.url() + "/api/fs/list?path=" + enc(denied));
            assertTrue(outsideList.path("error").asText().contains("允许范围"),
                    "目录浏览同样收口：" + outsideList);
        }
    }

    /** 并发上限：第二个请求立刻 429，并说清上限和当前占用。 */
    @Test
    void rejectsBeyondConcurrencyLimit(@TempDir Path dir) throws Exception {
        try (MockChatServer mock = MockChatServer.start();
             AgentWebServer web = AgentWebServer.start(0, dir, source(mock),
                     config("web.max-concurrent-runs", "1"))) {

            // 1500 字按 4 字/5ms 切片 ≈ 1.9 秒，足够让第二个请求撞上"名额已满"
            mock.enqueueText("长".repeat(1500));
            JsonNode first = post(web.url() + "/api/run?scenario=general");
            assertFalse(first.path("sessionId").asText().isBlank(), first.toString());

            JsonNode second = post(web.url() + "/api/run?scenario=general");
            assertTrue(second.path("error").asText().contains("上限"), "第二个请求应被拒：" + second);
            assertEquals(1, second.path("busy").asInt(), second.toString());
            assertEquals(1, second.path("limit").asInt(), second.toString());
        }
    }

    private JsonNode get(String url) throws Exception {
        return MAPPER.readTree(send(HttpRequest.newBuilder(URI.create(url)).GET()).body());
    }

    private JsonNode post(String url) throws Exception {
        return MAPPER.readTree(send(HttpRequest.newBuilder(URI.create(url))
                .POST(HttpRequest.BodyPublishers.noBody())).body());
    }
}
