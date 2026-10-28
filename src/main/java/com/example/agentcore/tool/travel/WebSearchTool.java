package com.example.agentcore.tool.travel;

import com.example.agentcore.support.AppConfig;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 网络搜索工具。对应 Python 版 {@code tools/web_search.py}（原版用 {@code duckduckgo_search} 库）。
 *
 * <p>原版只用 DuckDuckGo，直连在国内基本连不上（实测 connect timeout）。所以这里做成可切换：</p>
 * <ul>
 *   <li>{@code bing}（默认）：抓 {@code cn.bing.com/search}，国内直连可用、无需 Key，结果结构稳定；</li>
 *   <li>{@code ddg}：保持原版行为，抓 {@code html.duckduckgo.com}，有代理时可用。</li>
 * </ul>
 *
 * <p>两者都返回同样的 {@code title / link / snippet} 三元组，模型侧无感知。
 * 换引擎改 {@code agent.properties} 的 {@code tool.search.provider} 即可。</p>
 */
public class WebSearchTool {

    private static final String DDG_ENDPOINT = "https://html.duckduckgo.com/html/";
    private static final String BING_ENDPOINT = "https://cn.bing.com/search";

    private final String provider;

    public WebSearchTool() {
        this(AppConfig.get().get("tool.search.provider", "bing"));
    }

    public WebSearchTool(String provider) {
        this.provider = provider == null || provider.isBlank() ? "bing" : provider.trim().toLowerCase();
    }

    @Tool(name = "web_search",
            value = "网络搜索工具。在搜索引擎上搜索关键词，返回指定数目的搜索结果，"
                    + "每个结果包含网页的标题、链接和开头内容。")
    public String webSearch(
            @P(description = "要搜索的关键词，根据当前任务目标确定，尽量精确和详细") String keywords,
            @P(description = "最多返回多少条搜索结果。如果返回的结果没有太多有用信息，可以指定返回更多",
                    required = false) Integer maxResults) {

        if (keywords == null || keywords.isBlank()) {
            throw new IllegalArgumentException("keywords 不能为空");
        }
        int limit = maxResults == null || maxResults <= 0 ? 10 : Math.min(30, maxResults);

        List<Object> results = "ddg".equals(provider) ? searchDdg(keywords, limit) : searchBing(keywords, limit);

        if (results.isEmpty()) {
            // 常见原因：被限流、返回了验证码页、或者查询词确实没有结果
            return JsonOut.of(JsonOut.object(
                    "ok", false,
                    "message", "搜索引擎（" + provider + "）没有返回「" + keywords
                            + "」的结果。可能被限流或需要代理，换个关键词稍后重试。",
                    "provider", provider,
                    "returned", 0));
        }

        return JsonOut.of(JsonOut.object(
                "ok", true,
                "provider", provider,
                "query", keywords,
                "returned", results.size(),
                "results", results));
    }

    // ---------- Bing ----------

    private List<Object> searchBing(String keywords, int limit) {
        String url = BING_ENDPOINT + "?q=" + java.net.URLEncoder.encode(keywords, StandardCharsets.UTF_8)
                + "&count=" + Math.max(10, limit) + "&ensearch=0";
        String html = WebFetcher.get(url, WebFetcher.headers("Referer", "https://cn.bing.com/"));
        Document doc = WebFetcher.parse(html, BING_ENDPOINT);

        List<Object> results = JsonOut.list();
        for (Element item : doc.select("li.b_algo")) {
            if (results.size() >= limit) {
                break;
            }
            Element link = item.selectFirst("h2 a");
            if (link == null) {
                continue;
            }
            Element caption = item.selectFirst(".b_caption p");
            results.add(JsonOut.object(
                    "title", link.text(),
                    "link", bingLink(link.attr("href")),
                    "snippet", caption == null ? "" : caption.text()));
        }
        return results;
    }

    /** Bing 部分结果走 {@code /ck/a?...&u=a1<base64url>} 跳转，还原成真实地址。 */
    private static String bingLink(String href) {
        if (href == null || href.isBlank()) {
            return "";
        }
        int at = href.indexOf("&u=a1");
        if (at < 0 || !href.contains("/ck/a")) {
            return href;
        }
        String encoded = href.substring(at + 5);
        int amp = encoded.indexOf('&');
        if (amp >= 0) {
            encoded = encoded.substring(0, amp);
        }
        try {
            int padding = (4 - encoded.length() % 4) % 4;
            byte[] raw = java.util.Base64.getUrlDecoder().decode(encoded + "=".repeat(padding));
            return new String(raw, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return href;
        }
    }

    // ---------- DuckDuckGo ----------

    private List<Object> searchDdg(String keywords, int limit) {
        Map<String, String> form = WebFetcher.headers(
                "q", keywords,
                // cn-zh 等价于 Python 版 DDGS(region='cn-zh')
                "kl", "cn-zh");

        String html = WebFetcher.postForm(DDG_ENDPOINT, null, form);
        Document doc = WebFetcher.parse(html, DDG_ENDPOINT);

        List<Object> results = JsonOut.list();
        for (Element item : doc.select("div.result")) {
            if (results.size() >= limit) {
                break;
            }
            Element link = item.selectFirst("a.result__a");
            if (link == null) {
                continue;
            }
            Element snippet = item.selectFirst(".result__snippet");
            results.add(JsonOut.object(
                    "title", link.text(),
                    "link", ddgLink(link.attr("href")),
                    "snippet", snippet == null ? "" : snippet.text()));
        }
        return results;
    }

    /** DDG 的链接是 {@code //duckduckgo.com/l/?uddg=<编码后的真实地址>} 包装。 */
    private static String ddgLink(String href) {
        if (href == null || href.isBlank()) {
            return "";
        }
        int at = href.indexOf("uddg=");
        if (at >= 0) {
            String encoded = href.substring(at + "uddg=".length());
            int amp = encoded.indexOf('&');
            if (amp >= 0) {
                encoded = encoded.substring(0, amp);
            }
            try {
                return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ignored) {
                return encoded;
            }
        }
        return href.startsWith("//") ? "https:" + href : href;
    }
}
