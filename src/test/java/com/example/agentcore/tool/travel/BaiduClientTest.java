package com.example.agentcore.tool.travel;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 百度实现的"不必联网就能验证"的部分：坐标顺序、小数位、配置切换、缺 AK 的可解释性。
 *
 * <p>真实接口正确性由 {@code mvn exec:java -Dexec.args="tools"} 覆盖（需要真 AK）。
 * 这里刻意不 mock HTTP——那种测试只能证明自己写的假数据能被自己解析，价值不大。</p>
 */
class BaiduClientTest {

    @Test
    void lngLatIsReversedToBaiduLatLng() {
        // 工具层一律「经度,纬度」，百度 place / direction 要「纬度,经度」
        assertEquals("28.228209,112.938814", BaiduClient.toLatLng("112.938814,28.228209"));
    }

    @Test
    void staticMapKeepsLngLatOrder() {
        // 但 staticimage 的 center 是「经度,纬度」，两家平台内部就不统一，这里必须各走各的
        assertEquals("112.938814,28.228209", BaiduClient.normalizeLngLat("112.938814,28.228209"));
    }

    @Test
    void coordinateIsTruncatedToSixDecimals() {
        // 百度文档要求小数点后不超过 6 位，超了按参数错误处理
        assertEquals("28.228209,112.938815", BaiduClient.toLatLng("112.9388149,28.2282091"));
    }

    @Test
    void malformedCoordinateFailsBeforeAnyRequest() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> BaiduClient.toLatLng("112.938814"));
        assertTrue(error.getMessage().contains("经度,纬度"), error.getMessage());

        IllegalArgumentException notNumber = assertThrows(IllegalArgumentException.class,
                () -> BaiduClient.toLatLng("abc,28.2"));
        assertTrue(notNumber.getMessage().contains("不是数字"), notNumber.getMessage());
    }

    @Test
    void missingAkProducesActionableError() {
        BaiduClient noAk = BaiduClient.of(BaiduClient.DEFAULT_BASE_URL, "", Duration.ofSeconds(1));
        assertFalse(noAk.configured());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new LocationTool(noAk).getLocationCoordinate("岳麓山", "长沙"));
        // 报错要能直接告诉人"去哪申请、填哪个配置项"
        assertTrue(error.getMessage().contains(BaiduClient.APPLY_URL), error.getMessage());
        assertTrue(error.getMessage().contains("tool.baidu.ak"), error.getMessage());
        assertTrue(error.getMessage().contains("tool.map.provider"), error.getMessage());
    }

    /** 参数校验必须发生在网络调用之前——否则空关键字会先撞上 AK 校验，误导排查方向。 */
    @Test
    void nearbyRejectsEmptyKeywordAndTypeBeforeContactingBaidu() {
        BaiduClient noAk = BaiduClient.of(BaiduClient.DEFAULT_BASE_URL, "", Duration.ofSeconds(1));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new NearbyTool(noAk).searchNearbyPoi("112.9,28.2", "0731", "", "", 1000, 5, 1));
        assertTrue(error.getMessage().contains("至少填写一个"));
        assertFalse(error.getMessage().contains("AK"), "参数校验应该先于 AK 校验");
    }

    @Test
    void providerSwitchResolvesToTheRightImplementation() {
        assertInstanceOf(BaiduClient.class, MapClients.of("baidu"));
        assertInstanceOf(BaiduClient.class, MapClients.of(" BD "));
        assertInstanceOf(AmapClient.class, MapClients.of("amap"));
        // 写错不能静默退回高德，否则"填了百度 AK 却报高德错误"这种问题要排查很久
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> MapClients.of("tencent"));
        assertTrue(error.getMessage().contains("amap 或 baidu"), error.getMessage());
    }

    /** 高德类型词要能映射成百度 tag，否则换 provider 就得改提示词。 */
    @Test
    void amapTypeWordsMapToBaiduTags() {
        assertEquals("酒店", BaiduClient.tagOf("宾馆酒店"));
        assertEquals("美食", BaiduClient.tagOf("中餐厅|快餐厅"));
        assertEquals("风景名胜", BaiduClient.tagOf("风景名胜"));
        assertEquals("", BaiduClient.tagOf(""));
    }
}
