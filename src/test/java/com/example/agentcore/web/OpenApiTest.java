package com.example.agentcore.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 接口文档的自检。
 *
 * <p>手写 OpenAPI 最大的风险不是写得不漂亮，而是**慢慢变假**：接口改了、文档没改，
 * 下一个人照着文档接，调不通还不知道怪谁。这里做两件事：文档里的每条路径必须真能通到
 * 服务端（不是 404），以及每个操作都要有说明和响应定义。</p>
 */
@Timeout(30)
class OpenApiTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .proxy(ProxySelector.of(null))
            .build();

    private static JsonNode spec() throws Exception {
        try (InputStream in = OpenApiTest.class.getResourceAsStream("/openapi.json")) {
            assertNotNull(in, "openapi.json 应在 classpath 里（src/main/resources）");
            return MAPPER.readTree(in.readAllBytes());
        }
    }

    @Test
    void specIsValidOpenApi31() throws Exception {
        JsonNode root = spec();
        assertTrue(root.path("openapi").asText().startsWith("3."),
                "版本号要写清楚，工具才知道按哪套解析");
        assertFalse(root.path("info").path("title").asText().isBlank());
        assertFalse(root.path("info").path("description").asText().isBlank(),
                "说明里要写清口令怎么带，否则第一次接的人会先撞 401");
        assertTrue(root.path("components").path("securitySchemes").has("bearerToken"));
        assertTrue(root.path("components").path("securitySchemes").has("queryToken"),
                "EventSource 带不了请求头，query 口令必须写在文档里");
    }

    /**
     * 文档里写的每条路径都要能在真服务上打到。
     *
     * <p>判据不能简单地用"不是 404"：{@code /api/stop} 不带 sessionId 时，业务层自己就返回 404
     * （"session 不存在"），那是路由存在的证据，不是文档写错。两者的区别在响应体——
     * 路由没匹配上时由静态资源兜底，返回的是 {@code not found: <path>}。
     * 所以这里按"404 且是兜底文案"判失败，其余状态码一律算通。</p>
     */
    @Test
    void everyDocumentedPathExistsOnServer(@TempDir Path dir) throws Exception {
        List<String> paths = new ArrayList<>();
        spec().path("paths").fieldNames().forEachRemaining(paths::add);
        assertFalse(paths.isEmpty());

        try (AgentWebServer web = AgentWebServer.start(0, dir)) {
            for (String path : paths) {
                HttpResponse<String> response = CLIENT.send(
                        HttpRequest.newBuilder(URI.create(web.url() + path)).GET().build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                boolean unrouted = response.statusCode() == 404
                        && response.body().contains("not found:");
                assertFalse(unrouted,
                        "文档里写了 " + path + "，但服务端没有这个路由（文档与代码不一致）");
            }
        }
    }

    /** 反过来也要防：新增了接口却忘了写文档，别人就永远不知道它存在。 */
    @Test
    void codeRoutesAreAllDocumented() throws Exception {
        JsonNode paths = spec().path("paths");
        for (String route : implementedRoutes()) {
            assertNotNull(paths.get(route),
                    "路由 " + route + " 已经实现但没写进 openapi.json");
        }
    }

    @Test
    void everyOperationHasSummaryAndResponses() throws Exception {
        JsonNode paths = spec().path("paths");
        paths.fieldNames().forEachRemaining(path -> {
            JsonNode item = paths.get(path);
            item.fieldNames().forEachRemaining(method -> {
                JsonNode operation = item.get(method);
                assertFalse(operation.path("summary").asText().isBlank(),
                        path + " " + method + " 缺 summary");
                assertTrue(operation.path("responses").size() > 0,
                        path + " " + method + " 没写任何响应");
            });
        });
    }

    @Test
    void specIsServedOverHttp(@TempDir Path dir) throws Exception {
        try (AgentWebServer web = AgentWebServer.start(0, dir)) {
            HttpResponse<String> response = CLIENT.send(
                    HttpRequest.newBuilder(URI.create(web.url() + "/api/openapi.json")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertEquals(200, response.statusCode());
            assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("application/json"));
            assertEquals(spec().path("info").path("version").asText(),
                    MAPPER.readTree(response.body()).path("info").path("version").asText(),
                    "服务端返回的应当就是打包进去的这份");
        }
    }

    /**
     * 代码里真实存在的接口清单。
     *
     * <p>故意写成字面量而不是扫源码：这里要的是"实现的人亲手确认过这些路径"，
     * 扫出来的列表会跟着代码一起漂，就失去了对照的意义。</p>
     */
    private static List<String> implementedRoutes() {
        return List.of(
                "/api/health",
                "/api/metrics",
                "/api/scenarios",
                "/api/fs/list",
                "/api/run",
                "/api/stream",
                "/api/snapshots",
                "/api/conversations",
                "/api/resume",
                "/api/stop",
                "/api/openapi.json");
    }
}
