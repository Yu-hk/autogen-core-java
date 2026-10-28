package com.example.agentcore.tool.travel;

import com.example.agentcore.support.AppConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 高德开放平台 Web 服务客户端：Python 版用的是 {@code requests}，这里用 JDK 自带的 {@link HttpClient}。
 *
 * <p>三个工具（地理编码 / 周边搜索 / 公交路径规划）共用一份 Key 与一套错误语义，
 * 所以把"拼 URL、解析 status、翻译错误"这几件事收在一处，避免每个工具各写一遍。</p>
 *
 * <p>Key 的来源只有一个：{@code agent.properties} 的 {@code amap.key}。
 * 没配的时候不抛在构造里，而是在真正发请求时抛出带申请地址的说明——
 * 这样自检命令还能把其余工具跑一遍。</p>
 */
public final class AmapClient implements MapClient {

    /** 高德 Web 服务 API 根地址。 */
    public static final String DEFAULT_BASE_URL = "https://restapi.amap.com";

    /** Key 申请页，出错提示里带上，省得用户再去翻文档。 */
    public static final String KEY_APPLY_URL = "https://console.amap.com/dev/key/app";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final String key;
    private final Duration timeout;
    private final HttpClient http;

    private AmapClient(String baseUrl, String key, Duration timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.key = key;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                // 高德是公网服务，系统代理要生效；不要走 bypassProxyForLocalhost 那套
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** 按 {@code agent.properties} 的 {@code tool.amap.*} 构造。 */
    public static AmapClient fromConfig() {
        AppConfig app = AppConfig.get();
        return new AmapClient(
                app.get("tool.amap.base-url", DEFAULT_BASE_URL),
                app.get("tool.amap.key", ""),
                Duration.ofSeconds(app.getLong("tool.amap.timeout-seconds", 20)));
    }

    /** 显式构造，供测试与"换 key 重试"这类场景使用。 */
    public static AmapClient of(String baseUrl, String key, Duration timeout) {
        return new AmapClient(baseUrl, key, timeout);
    }

    @Override
    public String provider() {
        return "amap";
    }

    @Override
    public String displayName() {
        return "高德开放平台";
    }

    @Override
    public String applyUrl() {
        return KEY_APPLY_URL;
    }

    @Override
    public boolean configured() {
        return !key.isBlank();
    }

    /** 当前使用的 Key，只露头尾，便于排查"到底读没读到配置"。 */
    @Override
    public String maskedKey() {
        if (key.isBlank()) {
            return "(未配置)";
        }
        return key.length() <= 8 ? key.substring(0, 2) + "****"
                : key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }

    /**
     * GET 一个 v3 接口并返回解析后的 JSON。
     *
     * @param path   形如 {@code /v3/geocode/geo}
     * @param params 业务参数，方法内部会补 {@code key}
     */
    public JsonNode get(String path, Map<String, String> params) {
        String body = fetch(path, params);
        try {
            JsonNode root = MAPPER.readTree(body);
            // 高德的约定：status=1 成功，status=0 失败，失败原因在 info / infocode 里
            if (!"1".equals(root.path("status").asText())) {
                throw new IllegalStateException("高德 " + path + " 返回失败："
                        + root.path("info").asText("未知错误")
                        + "（infocode=" + root.path("infocode").asText("-") + "）"
                        + hint(root));
            }
            return root;
        } catch (IOException e) {
            throw new IllegalStateException("高德 " + path + " 返回的不是合法 JSON：" + snippet(body), e);
        }
    }

    /** 下载二进制内容（静态地图），同样校验 Key 与状态。 */
    public byte[] getBytes(String path, Map<String, String> params) {
        requireKey();
        HttpRequest request = HttpRequest.newBuilder(url(path, params))
                .timeout(timeout)
                .header("User-Agent", "autogen-core-java/travel")
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            byte[] body = response.body();
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (response.statusCode() != 200 || contentType.contains("json") || contentType.contains("text")) {
                throw new IllegalStateException("高德 " + path + " 未返回图片（HTTP " + response.statusCode()
                        + "，Content-Type " + contentType + "）：" + snippet(new String(body, StandardCharsets.UTF_8)));
            }
            return body;
        } catch (IOException e) {
            throw new IllegalStateException("请求高德 " + path + " 失败：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("请求高德 " + path + " 被中断", e);
        }
    }

    // ---------- MapClient 实现 ----------

    @Override
    public List<GeoPoint> geocode(String address, String city) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("address", address);
        params.put("city", city == null ? "" : city);

        JsonNode root = get("/v3/geocode/geo", params);
        List<GeoPoint> points = new ArrayList<>();
        JsonNode geocodes = root.path("geocodes");
        if (geocodes.isArray()) {
            for (JsonNode item : geocodes) {
                points.add(new GeoPoint(
                        text(item, "formatted_address"),
                        text(item, "location"),
                        text(item, "citycode")));
            }
        }
        return points;
    }

    @Override
    public PoiPage searchPoi(String lngLat, String keyword, String types, String city,
                             int radius, int pageSize, int page) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("location", lngLat);
        params.put("keywords", keyword == null ? "" : keyword);
        params.put("types", types == null ? "" : types);
        params.put("city", city == null ? "" : city);
        params.put("radius", String.valueOf(radius <= 0 ? 5000 : radius));
        params.put("offset", String.valueOf(pageSize <= 0 ? 20 : pageSize));
        params.put("page", String.valueOf(page <= 0 ? 1 : page));

        JsonNode root = get("/v3/place/around", params);
        return new PoiPage(parsePois(root.path("pois")), root.path("count").asText("0"));
    }

    @Override
    public PoiPage searchCity(String city, String keyword, int pageSize) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("city", city == null ? "" : city);
        params.put("keywords", keyword == null ? "" : keyword);
        params.put("types", keyword == null ? "" : keyword);
        params.put("offset", String.valueOf(pageSize <= 0 ? 20 : pageSize));
        params.put("page", "1");

        JsonNode root = get("/v3/place/text", params);
        return new PoiPage(parsePois(root.path("pois")), root.path("count").asText("0"));
    }

    @Override
    public Transit transit(String originLngLat, String destLngLat, String originCity, String destCity) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("origin", originLngLat);
        params.put("destination", destLngLat);
        params.put("city", originCity == null ? "" : originCity);
        params.put("cityd", destCity == null ? "" : destCity);

        JsonNode root = get("/v3/direction/transit/integrated", params);
        JsonNode route = root.path("route");
        // 没有可用方案时 route 返回空数组而不是对象，直接 path 只会拿到 MissingNode
        if (!route.isObject()) {
            return new Transit(originLngLat, destLngLat, "", "", List.of());
        }

        List<TransitOption> options = new ArrayList<>();
        JsonNode transits = route.path("transits");
        if (transits.isArray()) {
            for (JsonNode item : transits) {
                options.add(new TransitOption(
                        item.path("cost").asText(""),
                        item.path("duration").asText(""),
                        item.path("walking_distance").asText("")));
            }
        }

        String taxiCost = route.path("taxi_cost").asText("");
        return new Transit(
                route.path("origin").asText(originLngLat),
                route.path("destination").asText(destLngLat),
                route.path("distance").asText(""),
                taxiCost.isBlank() || "0".equals(taxiCost) ? "" : taxiCost,
                options);
    }

    @Override
    public byte[] staticMap(String lngLat, int zoom) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("location", lngLat);
        params.put("zoom", String.valueOf(Math.max(1, Math.min(17, zoom))));
        return getBytes("/v3/staticmap", params);
    }

    // ---------- 内部 ----------

    private static List<Poi> parsePois(JsonNode array) {
        List<Poi> pois = new ArrayList<>();
        if (!array.isArray()) {
            return pois;
        }
        for (JsonNode item : array) {
            JsonNode bizExt = item.path("biz_ext");
            pois.add(new Poi(
                    item.path("name").asText(""),
                    item.path("type").asText(""),
                    item.path("address").asText(""),
                    item.path("distance").asText(""),
                    item.path("location").asText(""),
                    bizExt.isObject() && !bizExt.path("rating").asText("").isBlank()
                            ? bizExt.path("rating").asText() : "not available",
                    bizExt.isObject() && !bizExt.path("cost").asText("").isBlank()
                            ? bizExt.path("cost").asText() : "not available"));
        }
        return pois;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        // 高德偶尔用空数组 [] 表示"无此字段"，asText() 会得到空串，正合适
        return value.isMissingNode() || value.isNull() ? "" : value.asText();
    }

    private String fetch(String path, Map<String, String> params) {
        requireKey();
        HttpRequest request = HttpRequest.newBuilder(url(path, params))
                .timeout(timeout)
                .header("User-Agent", "autogen-core-java/travel")
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new IllegalStateException("高德 " + path + " HTTP " + response.statusCode()
                        + "：" + snippet(response.body()));
            }
            return response.body();
        } catch (IOException e) {
            throw new IllegalStateException("连不上高德 " + path + "（" + e.getMessage()
                    + "）。检查本机网络或代理。", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("请求高德 " + path + " 被中断", e);
        }
    }

    private void requireKey() {
        if (key.isBlank()) {
            throw new IllegalStateException("未配置高德 Key。请到 " + KEY_APPLY_URL
                    + " 申请（服务平台必须选「Web服务」），再把 Key 填进 agent.properties 的 tool.amap.key。");
        }
    }

    private URI url(String path, Map<String, String> params) {
        Map<String, String> all = new LinkedHashMap<>();
        if (params != null) {
            all.putAll(params);
        }
        all.put("key", key);
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> entry : all.entrySet()) {
            String value = entry.getValue();
            if (value == null || value.isEmpty()) {
                // 空参数高德会当成"传了这个字段"，直接不发更接近 Python 版拼串的行为
                continue;
            }
            if (!query.isEmpty()) {
                query.append('&');
            }
            query.append(encode(entry.getKey())).append('=').append(encode(value));
        }
        return URI.create(baseUrl + path + "?" + query);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String hint(JsonNode root) {
        return switch (root.path("infocode").asText("")) {
            case "10001", "10002", "10003", "10009", "10013", "10044" ->
                    "。Key 无效或类型不对，确认服务平台是「Web服务」：" + KEY_APPLY_URL;
            case "10004" -> "。当日调用量已超限，明天再试或提升配额。";
            case "10021", "10019", "10020", "10029" -> "。调用过于频繁，稍后重试。";
            default -> "";
        };
    }

    private static String snippet(String body) {
        if (body == null) {
            return "";
        }
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() > 300 ? flat.substring(0, 300) + "…" : flat;
    }
}
