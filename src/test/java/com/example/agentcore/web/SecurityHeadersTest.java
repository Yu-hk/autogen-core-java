package com.example.agentcore.web;

import com.example.agentcore.support.AppConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 响应头：这套东西没配好不会有任何"报错"，只会在出事那天才发现挡不住。
 *
 * <p>所以这里逐条断言具体值，而不是"有没有设置"——头名拼错、值写成 {@code DENY } 带空格、
 * CSP 少写 {@code 'self'}，浏览器都是静默忽略，服务端测不出来。</p>
 */
@Timeout(30)
class SecurityHeadersTest {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .proxy(ProxySelector.of(null))
            .build();

    @Test
    void apiResponsesCarryBaselineHeaders(@TempDir Path dir) throws Exception {
        try (AgentWebServer web = AgentWebServer.start(0, dir)) {
            HttpResponse<String> response = get(web.url() + "/api/health");

            assertEquals("nosniff", header(response, "X-Content-Type-Options"));
            assertEquals("DENY", header(response, "X-Frame-Options"));
            assertEquals("no-referrer", header(response, "Referrer-Policy"),
                    "URL 里可能带着 ?token=，跳外链时不能当 Referer 送出去");
            assertTrue(header(response, "Permissions-Policy").contains("camera=()"),
                    "这服务不需要摄像头/麦克风/定位，直接关掉");
        }
    }

    /** SSE 也是走 route 的，别因为换了 Content-Type 就漏掉安全头。 */
    @Test
    void streamResponsesCarryBaselineHeaders(@TempDir Path dir) throws Exception {
        try (AgentWebServer web = AgentWebServer.start(0, dir)) {
            // 会话不存在也会返回一条 expired 事件后关闭，响应头照样要齐
            HttpResponse<String> response = get(web.url() + "/api/stream?sessionId=deadbeef");
            assertEquals("nosniff", header(response, "X-Content-Type-Options"));
            assertEquals("DENY", header(response, "X-Frame-Options"));
        }
    }

    /**
     * CSP 两套策略的差别只有一个：允不允许内联脚本。
     *
     * <p>React 产物没有内联脚本，所以线上应当是严格的 {@code script-src 'self'}；
     * 旧单页把 JS 内联在 HTML 里，为它单独放宽。混成一套的后果是要么旧页打不开，
     * 要么新页永远带着 {@code 'unsafe-inline'}，等于 CSP 只剩一半作用。</p>
     */
    @Test
    void cspIsStrictForBuiltPageAndRelaxedOnlyForLegacyPage() {
        String strict = AgentWebServer.contentSecurityPolicy(false);
        assertTrue(strict.contains("script-src 'self';"), "严格版不应允许内联脚本：" + strict);
        assertFalse(strict.contains("script-src 'self' 'unsafe-inline'"), strict);
        assertTrue(strict.contains("frame-ancestors 'none'"), "禁止被任何页面嵌套：" + strict);
        assertTrue(strict.contains("object-src 'none'"), strict);
        assertTrue(strict.contains("connect-src 'self'"),
                "SSE 与 fetch 都是同源，放开更宽的口子没有理由：" + strict);

        String legacy = AgentWebServer.contentSecurityPolicy(true);
        assertTrue(legacy.contains("script-src 'self' 'unsafe-inline'"),
                "旧单页是内联脚本，不放宽就整页空白：" + legacy);
        assertTrue(legacy.contains("frame-ancestors 'none'"),
                "放宽脚本不等于连点击劫持也一起放开");
    }

    /** HSTS 默认不能开：内网多是 http，一旦下发浏览器会把它锁成打不开的 https。 */
    @Test
    void hstsIsOffByDefaultAndOptIn(@TempDir Path dir) throws Exception {
        try (AgentWebServer web = AgentWebServer.start(0, dir)) {
            assertNull(header(get(web.url() + "/api/health"), "Strict-Transport-Security"),
                    "默认不该下发 HSTS");
        }

        Properties props = new Properties();
        props.setProperty("server.hsts", "true");
        try (AgentWebServer web = AgentWebServer.start(0, dir, ModelSource.configured(), AppConfig.of(props))) {
            assertTrue(header(get(web.url() + "/api/health"), "Strict-Transport-Security")
                    .contains("max-age="), "显式打开后应下发");
        }
    }

    private static HttpResponse<String> get(String url) throws IOException, InterruptedException {
        return CLIENT.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String header(HttpResponse<?> response, String name) {
        return response.headers().firstValue(name).orElse(null);
    }
}
