package com.example.agentcore.tool.travel;

import com.example.agentcore.support.AppConfig;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 景点搜索工具。
 *
 * <p>对应 Python 版 {@code tools/attractions.py}。原版靠 Selenium 开真浏览器抓马蜂窝，
 * 拿到的是"简介 + 建议游玩时长 + 开放时间"这些马蜂窝独有的结构化字段。</p>
 *
 * <p>Java 版做了两条路，按 {@code tool.attractions.provider} 选：</p>
 * <ul>
 *   <li>{@code mafengwo}：Jsoup 直连抓取，字段最全，但马蜂窝有 WAF（实测返回 202 + JS 挑战页），
 *       纯 HTTP 客户端大概率被拦；</li>
 *   <li>{@code poi}：用地图 POI 分类检索（风景名胜）。稳定、有坐标，但没有开放时间/游玩时长。
 *       具体走高德还是百度，由 {@code tool.map.provider} 决定；旧值 {@code amap} 仍兼容。</li>
 * </ul>
 *
 * <p>默认 {@code auto}：先试马蜂窝，失败自动退到地图 POI，并在返回里写明 {@code source} 和降级原因——
 * 这样模型知道哪些字段是真数据、哪些需要它自己补。</p>
 */
public class AttractionsTool {

    private static final String BASE = "https://www.mafengwo.cn";
    private static final Pattern MDDID = Pattern.compile("mddid=(\\d+)");

    private final int maxSpots;
    private final String provider;
    private final MapClient map;

    public AttractionsTool() {
        this(AppConfig.get().getLong("tool.attractions.max-spots", 5));
    }

    public AttractionsTool(long maxSpots) {
        this(maxSpots,
                AppConfig.get().get("tool.attractions.provider", "auto"),
                MapClients.fromConfig());
    }

    public AttractionsTool(long maxSpots, String provider, MapClient map) {
        this.maxSpots = (int) Math.max(1, Math.min(20, maxSpots));
        this.provider = provider == null || provider.isBlank() ? "auto" : provider.trim().toLowerCase();
        this.map = map;
    }

    @Tool(name = "get_attractions_information",
            value = "景点搜索工具。获取目的地概览和景点信息列表（含简介、开放时间、预计游玩时间，"
                    + "数据源不足时会给出经纬度和地址）。")
    public String getAttractionsInformation(
            @P(description = "目的地名称，必须是一个明确的城市或村镇名称") String destination) {

        if (destination == null || destination.isBlank()) {
            throw new IllegalArgumentException("destination 不能为空");
        }

        List<String> failures = JsonOut.list();

        // poi = 强制走地图 POI；amap 是旧配置值的兼容写法；mafengwo = 只抓马蜂窝
        boolean poiOnly = "amap".equals(provider) || "poi".equals(provider);

        if (!poiOnly) {
            try {
                return fromMafengwo(destination);
            } catch (RuntimeException e) {
                failures.add("马蜂窝抓取失败：" + e.getMessage());
            }
        }
        if (!"mafengwo".equals(provider)) {
            try {
                return fromMapPoi(destination);
            } catch (RuntimeException e) {
                failures.add(map.displayName() + " POI 检索失败：" + e.getMessage());
            }
        }

        return JsonOut.of(JsonOut.object(
                "ok", false,
                "message", "没能获取「" + destination + "」的景点信息。",
                "failures", failures,
                "suggestion", "改用 web_search 搜索该目的地的景点，或用 get_location_coordinate 直接查具体景点坐标。"));
    }

    // ---------- 数据源一：马蜂窝 ----------

    private String fromMafengwo(String destination) {
        String searchUrl = BASE + "/search/q.php?q=" + URLEncoder.encode(destination, StandardCharsets.UTF_8);
        Map<String, String> headers = WebFetcher.headers("Referer", BASE + "/");

        Document searchPage = WebFetcher.parse(WebFetcher.get(searchUrl, headers), searchUrl);
        String moreLink = findMoreLink(searchPage);
        if (moreLink == null) {
            throw new IllegalStateException("搜索页里没有「查看更多相关旅行地」入口，"
                    + "可能是名称不标准、页面结构变化，或被 WAF 挑战页拦截");
        }

        Matcher matcher = MDDID.matcher(moreLink);
        if (!matcher.find()) {
            throw new IllegalStateException("目的地链接里没有 mddid 参数，页面结构可能已变更：" + moreLink);
        }
        String mddid = matcher.group(1);

        String destUrl = BASE + "/jd/" + mddid + "/gonglve.html";
        Document page = WebFetcher.parse(WebFetcher.get(destUrl, headers), destUrl);

        String overview = textOr(page.selectFirst("span#mdd_poi_desc"), "Overview not found.");

        List<Object> spots = JsonOut.list();
        for (Element item : page.select("ul.scenic-list li")) {
            if (spots.size() >= maxSpots) {
                break;
            }
            Element h3 = item.selectFirst("h3");
            Element link = item.selectFirst("a[href]");
            if (h3 == null || link == null) {
                continue;
            }
            String href = link.attr("href");
            String detailUrl = href.startsWith("http") ? href : BASE + href;
            spots.add(fetchSpotDetail(detailUrl, h3.text().trim(), headers));
        }

        if (spots.isEmpty()) {
            throw new IllegalStateException("目的地页没有解析到景点列表（scenic-list），可能是模板变更");
        }

        return JsonOut.of(JsonOut.object(
                "ok", true,
                "source", "mafengwo",
                "destination", destination,
                "mddid", mddid,
                "overview", overview,
                "scenic_count", spots.size(),
                "scenic_list", spots,
                "note", "只抓第一页景点；duration / open_time 缺失时按经验估算，不要编造精确时间。"));
    }

    /** 详情页三个字段与原版一一对应：摘要、建议游玩时长、开放时间。 */
    private Object fetchSpotDetail(String url, String name, Map<String, String> headers) {
        try {
            Document detail = WebFetcher.parse(WebFetcher.get(url, headers), url);
            String summary = textOr(detail.selectFirst("div.summary"), "No summary available.");

            String duration = "unknown";
            Element timeItem = detail.selectFirst("li.item-time");
            if (timeItem != null) {
                Element content = timeItem.selectFirst("div.content");
                if (content != null && !content.text().isBlank()) {
                    duration = content.text().trim();
                }
            }

            String openTime = "unknown";
            for (Element dt : detail.select("dt")) {
                if (dt.text().contains("开放时间")) {
                    Element dd = dt.nextElementSibling();
                    if (dd != null) {
                        openTime = dd.text().trim();
                    }
                    break;
                }
            }

            return JsonOut.object(
                    "name", name,
                    "summary", summary,
                    "duration", duration,
                    "open_time", openTime);
        } catch (RuntimeException e) {
            // 单个景点抓失败不该让整个工具挂掉——其余景点信息对规划仍然有用
            return JsonOut.object(
                    "name", name,
                    "url", url,
                    "summary", "详情页抓取失败：" + e.getMessage(),
                    "duration", "unknown",
                    "open_time", "unknown");
        }
    }

    private static String findMoreLink(Document page) {
        for (Element anchor : page.select("a[href]")) {
            if (anchor.text().contains("查看更多相关旅行地")) {
                String href = anchor.attr("href");
                return href.startsWith("http") ? href : BASE + href;
            }
        }
        return null;
    }

    // ---------- 数据源二：地图服务商 POI（高德 / 百度同一套调用） ----------

    private String fromMapPoi(String destination) {
        // 高德的「风景名胜」是 POI 分类名；百度没有 types，内部会映射成 tag
        MapClient.PoiPage page = map.searchCity(destination, "风景名胜", maxSpots);
        List<Object> spots = JsonOut.list();
        for (MapClient.Poi poi : page.pois()) {
            if (spots.size() >= maxSpots) {
                break;
            }
            String address = poi.address();
            spots.add(JsonOut.object(
                    "name", poi.name(),
                    "summary", poi.type() + (address.isBlank() ? "" : "｜" + address),
                    "duration", "unknown",
                    "open_time", "unknown",
                    // 这条链路本身就带坐标，省掉一次 get_location_coordinate
                    "coordinate", poi.lngLat(),
                    "address", address,
                    "rating", poi.rating(),
                    "cost", poi.cost()));
        }

        if (spots.isEmpty()) {
            throw new IllegalStateException(map.displayName()
                    + " 在该城市没有检索到风景名胜类 POI（换个更常见的城市名试试）");
        }

        return JsonOut.of(JsonOut.object(
                "ok", true,
                "source", map.provider() + "-poi",
                "destination", destination,
                "overview", "（" + map.displayName() + "数据源不含目的地概览）",
                "scenic_count", spots.size(),
                "scenic_list", spots,
                "note", "该数据源没有开放时间和建议游玩时长，需要的话用 web_search 补；"
                        + "coordinate 是「经度,纬度」，可直接用于 route_planning / search_nearby_poi。"));
    }

    private static String textOr(Element element, String fallback) {
        if (element == null) {
            return fallback;
        }
        String text = element.text().trim();
        return text.isEmpty() ? fallback : text;
    }
}
