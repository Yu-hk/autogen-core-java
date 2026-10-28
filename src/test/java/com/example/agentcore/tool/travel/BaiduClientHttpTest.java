package com.example.agentcore.tool.travel;

import com.example.agentcore.support.ProxySupport;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用一个本地 HTTP 桩验证"真实发出去的请求"和"对真实响应结构的解析"，不碰外网。
 *
 * <p>为什么值得写：换 provider 最容易出错的地方不是业务逻辑，而是<b>参数顺序</b>——
 * 百度 place/direction 要「纬度,经度」，staticimage 要「经度,纬度」，返回的 {@code location}
 * 又是 {@code {lat,lng}} 对象。这些只有把请求拦下来看 raw query 才能证明。
 * 只 mock 掉 client 的测试证明不了这件事。</p>
 */
class BaiduClientHttpTest {

    private static HttpServer server;
    private static String baseUrl;

    private static volatile String lastPath;
    private static volatile String lastQuery;
    private static volatile String reply;

    @BeforeAll
    static void startStub() throws IOException {
        // 回环地址要绕过系统代理，否则 Windows 上可能被全局代理拦掉
        ProxySupport.bypassProxyForLocalhost();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastPath = exchange.getRequestURI().getPath();
            lastQuery = exchange.getRequestURI().getRawQuery();

            boolean image = lastPath != null && lastPath.contains("staticimage");
            byte[] body = image ? new byte[]{(byte) 0x89, 'P', 'N', 'G'} : reply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", image ? "image/png" : "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopStub() {
        if (server != null) {
            server.stop(0);
        }
    }

    @BeforeEach
    void reset() {
        lastPath = null;
        lastQuery = null;
        reply = "{\"status\":0}";
    }

    private BaiduClient client() {
        return BaiduClient.of(baseUrl, "fake-ak-for-stub", Duration.ofSeconds(5));
    }

    @Test
    void geocodeTurnsBaiduObjectIntoLngLatString() {
        reply = "{\"status\":0,\"result\":{\"location\":{\"lng\":112.938814,\"lat\":28.228209},"
                + "\"precise\":1,\"confidence\":80,\"level\":\"景点\"}}";

        List<MapClient.GeoPoint> points = client().geocode("岳麓山", "长沙");

        assertEquals(1, points.size(), "百度地理编码只返回一个最匹配结果");
        assertEquals("112.938814,28.228209", points.get(0).lngLat(),
                "百度给的是 {lat,lng} 对象，必须转成统一的「经度,纬度」");
        assertEquals("/geocoding/v3/", lastPath);
        assertTrue(lastQuery.contains("ret_coordtype=gcj02ll"), "出参要指定 gcj02，才能和高德坐标混用：" + lastQuery);
    }

    @Test
    void searchPoiSendsLatLngAndNormalizesDetailInfo() {
        reply = "{\"status\":0,\"total\":42,\"results\":[{"
                + "\"name\":\"费大厨辣椒炒肉\","
                + "\"location\":{\"lat\":28.228209,\"lng\":112.938814},"
                + "\"address\":\"长沙市岳麓区xx路1号\","
                + "\"detail_info\":{\"tag\":\"湘菜\",\"overall_rating\":\"4.5\",\"price\":\"￥88\",\"distance\":\"320\"}}]}";

        // page=2 → 百度的 page_num 必须是 1（从 0 开始）
        MapClient.PoiPage page = client().searchPoi("112.938814,28.228209", "", "中餐厅", "0731", 2000, 5, 2);

        assertEquals(1, page.pois().size());
        MapClient.Poi poi = page.pois().get(0);
        assertEquals("112.938814,28.228209", poi.lngLat(), "返回坐标要转回「经度,纬度」");
        assertEquals("4.5", poi.rating());
        assertEquals("￥88", poi.cost());
        assertEquals("42", page.total());

        assertTrue(lastQuery.contains("location=28.228209%2C112.938814"),
                "place 接口的 location 必须是「纬度,经度」，发反了会查到地球另一边：" + lastQuery);
        assertTrue(lastQuery.contains("page_num=1"), "百度页码从 0 开始：" + lastQuery);
        assertTrue(lastQuery.contains("page_size=5"), lastQuery);
        assertTrue(lastQuery.contains("scope=2"), "不传 scope=2 拿不到评分和营业时间：" + lastQuery);
        assertTrue(lastQuery.contains("coord_type=2"), "入参声明为 gcj02：" + lastQuery);
        assertTrue(lastQuery.contains("tag=%E7%BE%8E%E9%A3%9F"),
                "高德的「中餐厅」要映射成百度的「美食」：" + lastQuery);
    }

    @Test
    void transitParsesRoutesAndTaxiEstimate() {
        reply = "{\"status\":0,\"result\":{\"routes\":[{\"distance\":5400,\"duration\":1800,\"price\":4},"
                + "{\"distance\":6100,\"duration\":2100,\"price\":5}],"
                + "\"taxi\":{\"detail\":{\"total_price\":28}}}}";

        MapClient.Transit transit = client().transit("112.938814,28.228209", "112.960976,28.184095", "0731", "0731");

        assertEquals(2, transit.options().size());
        assertEquals("4", transit.options().get(0).cost());
        assertEquals("1800", transit.options().get(0).durationSeconds());
        assertEquals("28", transit.taxiCost(), "百度没有独立的 taxi_cost 字段，取出租车方案里的估算总价");
        assertTrue(lastQuery.contains("origin=28.228209%2C112.938814"), lastQuery);
        assertTrue(lastQuery.contains("coord_type=gcj02"), "入参声明为 gcj02：" + lastQuery);
        // 实测：direction 系列传 gcj02ll 会直接返回 status=2 format is invalid，
        // 只有 gcj02 有效；而 place/geocoding 反过来只认 gcj02ll。别顺手改成一样的。
        assertTrue(lastQuery.contains("ret_coordtype=gcj02"),
                "公交路线的 ret_coordtype 只能用 gcj02（不是 gcj02ll）：" + lastQuery);
        assertFalse(lastQuery.contains("ret_coordtype=gcj02ll"),
                "direction 接口不认 gcj02ll，会报 status=2：" + lastQuery);
    }

    @Test
    void staticMapUsesReversedOrderComparedToPlace() {
        byte[] image = client().staticMap("112.938814,28.228209", 12);

        assertTrue(image.length > 0);
        assertTrue(lastPath.contains("staticimage"), lastPath);
        assertTrue(lastQuery.contains("center=112.938814%2C28.228209"),
                "注意与 place 相反：静态图的 center 是「经度,纬度」：" + lastQuery);
        assertTrue(lastQuery.contains("coordtype=gcj02ll"), lastQuery);
        assertTrue(lastQuery.contains("zoom=12"), lastQuery);
    }

    /** 百度失败时 HTTP 仍是 200，只有 body 的 status 能判断——这条最容易把错误当成功。 */
    @Test
    void failureStatusIsDetectedEvenThoughHttpIs200() {
        reply = "{\"status\":200,\"message\":\"APP不存在，AK有误请检查再重试\"}";

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> client().geocode("岳麓山", "长沙"));
        assertTrue(error.getMessage().contains("status=200"), error.getMessage());
        assertTrue(error.getMessage().contains(BaiduClient.APPLY_URL), error.getMessage());
    }

    /** 天配额超限（地点检索个人只有 100 次/日）要给出可操作的提示，而不是原始错误码。 */
    @Test
    void quotaExceededGivesActionableHint() {
        reply = "{\"status\":4,\"message\":\"Quota Failure\"}";

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> client().searchCity("长沙", "风景名胜", 3));
        assertTrue(error.getMessage().contains("100 次/日"), error.getMessage());
    }
}
