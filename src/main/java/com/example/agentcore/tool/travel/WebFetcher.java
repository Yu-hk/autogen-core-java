package com.example.agentcore.tool.travel;

import com.example.agentcore.support.AppConfig;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 抓网页用的最小 HTTP 客户端（网络搜索、景点抓取共用）。
 *
 * <p>Python 版用 {@code requests} + {@code bs4}，Java 这边是 JDK {@link HttpClient} + Jsoup：
 * 前者负责请求，后者只负责解析 HTML，分工与原来一致。</p>
 *
 * <p>用浏览器 UA 是因为马蜂窝、DuckDuckGo 都会拦默认的 Java UA；这不是在绕反爬，
 * 只是别让请求在第一层就 403。真要登录/验证码的页面照样过不去。</p>
 */
final class WebFetcher {

    static final String BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    private static final Duration TIMEOUT =
            Duration.ofSeconds(AppConfig.get().getLong("tool.http-timeout-seconds", 20));

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private WebFetcher() {
    }

    static String get(String url, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("User-Agent", BROWSER_UA)
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .GET();
        if (headers != null) {
            headers.forEach(builder::header);
        }
        return send(builder.build(), "GET " + url);
    }

    static String postForm(String url, Map<String, String> headers, Map<String, String> form) {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> entry : form.entrySet()) {
            if (!body.isEmpty()) {
                body.append('&');
            }
            body.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("User-Agent", BROWSER_UA)
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        if (headers != null) {
            headers.forEach(builder::header);
        }
        return send(builder.build(), "POST " + url);
    }

    static Document parse(String html, String baseUrl) {
        return Jsoup.parse(html, baseUrl);
    }

    static Map<String, String> headers(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static String send(HttpRequest request, String what) {
        try {
            HttpResponse<String> response = HTTP.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new IllegalStateException(what + " 返回 HTTP " + response.statusCode()
                        + "，目标站点可能拒绝了本机请求（反爬/风控），换网络环境或改用其他工具。");
            }
            return response.body();
        } catch (IOException e) {
            throw new IllegalStateException(what + " 失败：" + e.getMessage()
                    + "。检查本机网络与代理设置。", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(what + " 被中断", e);
        }
    }
}
