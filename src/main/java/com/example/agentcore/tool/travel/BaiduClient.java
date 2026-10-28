package com.example.agentcore.tool.travel;

import com.example.agentcore.support.AppConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * 百度地图开放平台 Web 服务客户端，与 {@link AmapClient} 实现同一套 {@link MapClient} 契约。
 *
 * <p>看上去只是换个 URL，实际上有四个坑，都在本类里消化掉，工具层完全无感：</p>
 * <ol>
 *   <li><b>坐标顺序相反</b>：高德是「经度,纬度」，百度的 {@code place} 与 {@code directionlite}
 *       要「纬度,经度」（{@code lat,lng}），而 {@code staticimage} 又要「经度,纬度」（{@code center=lng,lat}）。
 *       一个平台内部两种顺序，是最容易踩的地方。</li>
 *   <li><b>坐标系不同</b>：百度默认 BD-09。这里所有请求都要求返回 GCJ-02，与高德保持同一语义，
 *       于是同一份坐标可在两家之间混用。但<b>同一个参数在不同接口上的取值还不一样</b>：
 *       {@code place} / {@code geocoding} 认 {@code ret_coordtype=gcj02ll}，
 *       {@code directionlite} 只认 {@code ret_coordtype=gcj02}（传 gcj02ll 直接报 status=2）。
 *       更阴的是 geocoding 传错值不报错，<b>静默忽略并返回 BD-09</b>——实测带 gcj02 与不带的结果完全一样。</li>
 *   <li><b>成功码是 0 不是 1</b>，而且<b>失败时 HTTP 照样是 200</b>——只看 HTTP 状态码会把
 *       "AK 无效"当成成功。本类一律以 body 里的 {@code status==0} 为准。</li>
 *   <li><b>返回结构是对象不是字符串</b>：百度给 {@code location:{lat,lng}}，
 *       高德给 {@code "112.93,28.22"}。这里统一转回「经度,纬度」字符串。</li>
 * </ol>
 *
 * <p>另一处能力差异如实保留：百度的地理编码只返回一个最匹配结果（高德返回同名候选列表），
 * 所以 {@link #geocode} 最多 1 条。</p>
 */
public final class BaiduClient implements MapClient {

    /** 百度 Web 服务 API 根地址。 */
    public static final String DEFAULT_BASE_URL = "https://api.map.baidu.com";

    /** AK 申请页。 */
    public static final String APPLY_URL = "https://lbsyun.baidu.com/apiconsole/key";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 高德 POI 类型词 → 百度 tag。
     *
     * <p>工具的 {@code types} 参数说明是照着高德写的（「中餐厅」「宾馆酒店」…），
     * 百度没有 {@code types} 参数，只有 {@code tag}，且词表不同。在这里做映射，
     * 就不用为了换 provider 去改提示词——那是要重训模型行为的事。</p>
     */
    private static final Map<String, String> TAG_MAP = Map.of(
            "中餐厅", "美食",
            "外国餐厅", "美食",
            "快餐厅", "美食",
            "宾馆酒店", "酒店",
            "旅馆招待所", "酒店");

    private final String baseUrl;
    private final String ak;
    private final Duration timeout;
    private final HttpClient http;

    private BaiduClient(String baseUrl, String ak, Duration timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.ak = ak;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** 按 {@code agent.properties} 的 {@code tool.baidu.*} 构造。 */
    public static BaiduClient fromConfig() {
        AppConfig app = AppConfig.get();
        return new BaiduClient(
                app.get("tool.baidu.base-url", DEFAULT_BASE_URL),
                app.get("tool.baidu.ak", ""),
                Duration.ofSeconds(app.getLong("tool.baidu.timeout-seconds", 20)));
    }

    /** 显式构造，供测试使用。 */
    public static BaiduClient of(String baseUrl, String ak, Duration timeout) {
        return new BaiduClient(baseUrl, ak, timeout);
    }

    @Override
    public String provider() {
        return "baidu";
    }

    @Override
    public String displayName() {
        return "百度地图开放平台";
    }

    @Override
    public String applyUrl() {
        return APPLY_URL;
    }

    @Override
    public boolean configured() {
        return !ak.isBlank();
    }

    @Override
    public String maskedKey() {
        if (ak.isBlank()) {
            return "(未配置)";
        }
        return ak.length() <= 8 ? ak.substring(0, 2) + "****"
                : ak.substring(0, 4) + "****" + ak.substring(ak.length() - 4);
    }

    // ---------- MapClient 实现 ----------

    @Override
    public List<GeoPoint> geocode(String address, String city) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("address", address);
        params.put("city", city == null ? "" : city);
        // 出参指定 gcj02，保证与高德坐标系一致
        params.put("ret_coordtype", "gcj02ll");

        JsonNode root = getJson("/geocoding/v3/", params);
        JsonNode location = root.path("result").path("location");
        if (!location.isObject()) {
            return List.of();
        }
        List<GeoPoint> points = new ArrayList<>();
        points.add(new GeoPoint(
                address + (city == null || city.isBlank() ? "" : "（" + city + "）"),
                lngLatOf(location),
                ""));
        return points;
    }

    @Override
    public PoiPage searchPoi(String lngLat, String keyword, String types, String city,
                             int radius, int pageSize, int page) {
        // 百度 page_num 从 0 开始，工具层的 page 从 1 开始
        int pageNum = Math.max(0, page - 1);
        int size = pageSize <= 0 ? 10 : Math.min(20, pageSize);

        String tag = tagOf(types);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("query", keyword == null || keyword.isBlank() ? tag : keyword);
        params.put("tag", tag);
        params.put("location", toLatLng(lngLat));
        params.put("radius", String.valueOf(radius <= 0 ? 5000 : Math.min(50000, radius)));
        params.put("scope", "2");
        params.put("page_size", String.valueOf(size));
        params.put("page_num", String.valueOf(pageNum));
        params.put("coord_type", "2");
        params.put("ret_coordtype", "gcj02ll");

        JsonNode root = getJson("/place/v2/search", params);
        return new PoiPage(parsePois(root.path("results")), root.path("total").asText("0"));
    }

    @Override
    public PoiPage searchCity(String city, String keyword, int pageSize) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("query", keyword == null || keyword.isBlank() ? "景点" : keyword);
        params.put("region", city == null ? "" : city);
        params.put("scope", "2");
        params.put("page_size", String.valueOf(pageSize <= 0 ? 10 : Math.min(20, pageSize)));
        params.put("page_num", "0");
        params.put("ret_coordtype", "gcj02ll");

        JsonNode root = getJson("/place/v2/search", params);
        return new PoiPage(parsePois(root.path("results")), root.path("total").asText("0"));
    }

    @Override
    public Transit transit(String originLngLat, String destLngLat, String originCity, String destCity) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("origin", toLatLng(originLngLat));
        params.put("destination", toLatLng(destLngLat));
        // 注意：direction 系列的 coord_type / ret_coordtype 取值与 place / geocoding 不同。
        //   place、geocoding 认的是 gcj02ll；direction 只认 gcj02（传 gcj02ll 会报
        //   「[ret_coordtype] format is invalid」status=2）。
        //   coord_type 在这里还得是字符串 gcj02，写成数字 2 会报「[coord_type] format is invalid」。
        params.put("coord_type", "gcj02");
        params.put("ret_coordtype", "gcj02");

        JsonNode root = getJson("/directionlite/v1/transit", params);
        JsonNode result = root.path("result");
        JsonNode routes = result.path("routes");

        List<TransitOption> options = new ArrayList<>();
        JsonNode first = null;
        if (routes.isArray()) {
            for (JsonNode route : routes) {
                if (first == null) {
                    first = route;
                }
                // 百度把费用放在 route 上，不是每条 step 上；duration 单位是秒
                options.add(new TransitOption(
                        route.path("price").asText(""),
                        route.path("duration").asText(""),
                        ""));
            }
        } else if (routes.isObject()) {
            // 部分响应把 routes 写成单个对象，兜一手
            first = routes;
            options.add(new TransitOption(
                    routes.path("price").asText(""),
                    routes.path("duration").asText(""),
                    ""));
        }

        if (options.isEmpty() || first == null) {
            return new Transit(originLngLat, destLngLat, "", "", List.of());
        }

        // 百度没有"步行总距离/出租车费"这两个字段；用出租车方案估算值补上，取不到就留空
        String taxiCost = result.path("taxi").path("detail").path("total_price").asText("");
        String walking = first.path("walking_distance").asText("");
        return new Transit(
                originLngLat,
                destLngLat,
                walking,
                taxiCost,
                options);
    }

    @Override
    public byte[] staticMap(String lngLat, int zoom) {
        requireAk();
        Map<String, String> params = new LinkedHashMap<>();
        // 注意：静态图的 center 是「经度,纬度」，与 place/direction 的「纬度,经度」相反
        params.put("center", normalizeLngLat(lngLat));
        params.put("width", "600");
        params.put("height", "400");
        params.put("zoom", String.valueOf(Math.max(3, Math.min(18, zoom))));
        params.put("coordtype", "gcj02ll");

        HttpRequest request = HttpRequest.newBuilder(url("/staticimage/v2", params))
                .timeout(timeout)
                .header("User-Agent", "autogen-core-java/travel")
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            byte[] body = response.body();
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            // 百度出图失败时同样返回 HTTP 200 + JSON
            if (response.statusCode() != 200 || contentType.contains("json") || contentType.contains("text")) {
                throw new IllegalStateException("百度 staticimage 未返回图片（HTTP " + response.statusCode()
                        + "，Content-Type " + contentType + "）：" + snippet(new String(body, StandardCharsets.UTF_8))
                        + explain(new String(body, StandardCharsets.UTF_8)));
            }
            return body;
        } catch (IOException e) {
            throw new IllegalStateException("请求百度 staticimage 失败：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("请求百度 staticimage 被中断", e);
        }
    }

    // ---------- 内部 ----------

    private JsonNode getJson(String path, Map<String, String> params) {
        String body = fetch(path, params);
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (IOException e) {
            throw new IllegalStateException("百度 " + path + " 返回的不是合法 JSON：" + snippet(body), e);
        }
        // 百度的坑：失败时 HTTP 仍为 200，只有 body 里的 status 能判断，成功是 0
        if (!"0".equals(root.path("status").asText())) {
            throw new IllegalStateException("百度 " + path + " 返回失败：" + root.path("message").asText("未知错误")
                    + "（status=" + root.path("status").asText("-") + "）" + hint(root.path("status").asText("")));
        }
        return root;
    }

    private String fetch(String path, Map<String, String> params) {
        requireAk();
        HttpRequest request = HttpRequest.newBuilder(url(path, params))
                .timeout(timeout)
                .header("User-Agent", "autogen-core-java/travel")
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new IllegalStateException("百度 " + path + " HTTP " + response.statusCode()
                        + "：" + snippet(response.body()));
            }
            return response.body();
        } catch (IOException e) {
            throw new IllegalStateException("连不上百度 " + path + "（" + e.getMessage()
                    + "）。检查本机网络或代理。", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("请求百度 " + path + " 被中断", e);
        }
    }

    private void requireAk() {
        if (ak.isBlank()) {
            throw new IllegalStateException("未配置百度 AK。请到 " + APPLY_URL
                    + " 申请（应用类型选「服务端」，并给 AK 配置 IP 白名单，否则调用会报 210），"
                    + "再把 AK 填进 agent.properties 的 tool.baidu.ak，"
                    + "同时把 tool.map.provider 设为 baidu。");
        }
    }

    private URI url(String path, Map<String, String> params) {
        Map<String, String> all = new LinkedHashMap<>();
        if (params != null) {
            all.putAll(params);
        }
        all.put("output", "json");
        all.put("ak", ak);
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> entry : all.entrySet()) {
            String value = entry.getValue();
            if (value == null || value.isEmpty()) {
                continue;
            }
            if (!query.isEmpty()) {
                query.append('&');
            }
            query.append(encode(entry.getKey())).append('=').append(encode(value));
        }
        return URI.create(baseUrl + path + "?" + query);
    }

    /**
     * 「经度,纬度」→「纬度,经度」。
     *
     * <p>同时把小数位截到 6 位：百度文档明确要求「小数点后不超过 6 位」，超了会按参数错误处理。</p>
     */
    static String toLatLng(String lngLat) {
        String[] parts = splitLngLat(lngLat);
        return trim6(parts[1]) + "," + trim6(parts[0]);
    }

    /** 校验并返回「经度,纬度」，小数位截到 6 位。 */
    static String normalizeLngLat(String lngLat) {
        String[] parts = splitLngLat(lngLat);
        return trim6(parts[0]) + "," + trim6(parts[1]);
    }

    private static String[] splitLngLat(String lngLat) {
        if (lngLat == null || lngLat.isBlank()) {
            throw new IllegalArgumentException("坐标不能为空，格式为「经度,纬度」");
        }
        String[] parts = lngLat.split(",");
        if (parts.length != 2) {
            throw new IllegalArgumentException("坐标格式错误：" + lngLat + "，应为「经度,纬度」");
        }
        return new String[]{parts[0].trim(), parts[1].trim()};
    }

    private static String trim6(String value) {
        try {
            return new BigDecimal(value).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("坐标不是数字：" + value);
        }
    }

    /** 百度返回 {@code location:{lat,lng}}，转成统一的「经度,纬度」字符串。 */
    private static String lngLatOf(JsonNode location) {
        return location.path("lng").asText("") + "," + location.path("lat").asText("");
    }

    /** 高德风格类型词 → 百度 tag。包私有是为了单测能直接验证映射表。 */
    static String tagOf(String types) {
        if (types == null || types.isBlank()) {
            return "";
        }
        List<String> tags = new ArrayList<>();
        for (String raw : types.split("\\|")) {
            String mapped = TAG_MAP.getOrDefault(raw.trim(), raw.trim());
            if (!tags.contains(mapped)) {
                tags.add(mapped);
            }
        }
        return String.join(",", tags);
    }

    private static List<Poi> parsePois(JsonNode array) {
        List<Poi> pois = new ArrayList<>();
        if (!array.isArray()) {
            return pois;
        }
        for (JsonNode item : array) {
            JsonNode detail = item.path("detail_info");
            pois.add(new Poi(
                    item.path("name").asText(""),
                    detail.path("tag").asText(detail.path("type").asText("")),
                    item.path("address").asText(""),
                    detail.path("distance").asText(item.path("distance").asText("")),
                    lngLatOf(item.path("location")),
                    detail.path("overall_rating").asText("not available"),
                    detail.path("price").asText("not available")));
        }
        return pois;
    }

    private static String hint(String status) {
        return switch (status) {
            case "2" -> "。请求参数有问题，重点检查坐标顺序（百度要「纬度,经度」）和 query 是否为空。";
            case "4" -> "。当日配额已用完，个人开发者「地点检索」只有 100 次/日，第二天恢复或去做开发者认证提额。";
            case "5", "101", "200", "201", "202", "203" ->
                    "。AK 无效或被禁用，确认应用类型是「服务端」，并检查控制台里 AK 的状态：" + APPLY_URL;
            case "210" -> "。IP 白名单校验失败：这个 AK 开了 IP 白名单，但当前出口 IP 不在名单里。"
                    + "先在终端查本机出口 IP（curl myip.ipip.net，注意家用宽带会变），"
                    + "再到控制台「我的应用」→ 编辑 AK → 白名单里加上它（测试期可填 0.0.0.0/0）。"
                    + "控制台：" + APPLY_URL;
            case "211" -> "。SN 校验失败：AK 开了 SN 校验就必须带 sn 参数，本项目未实现，建议改用 IP 白名单方式。";
            case "240" -> "。这个 AK 没有勾选对应服务，去控制台「我的应用」里给它勾上要用的接口。";
            case "302" -> "。天配额超限。";
            case "401" -> "。并发超限，稍后重试。";
            default -> "";
        };
    }

    private static String explain(String body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            return hint(root.path("status").asText(""));
        } catch (IOException ignored) {
            return "";
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String snippet(String body) {
        if (body == null) {
            return "";
        }
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() > 300 ? flat.substring(0, 300) + "…" : flat;
    }
}
