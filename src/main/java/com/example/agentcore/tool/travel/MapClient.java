package com.example.agentcore.tool.travel;

import java.util.List;

/**
 * 地图服务商抽象。高德、百度各一套实现，工具层只跟这个接口打交道。
 *
 * <p><b>坐标契约（本接口最重要的约定）</b>：所有 {@code lngLat} 参数与返回值一律是
 * 「经度,纬度」，且语义为 GCJ-02（国测局坐标）。这样做有两个理由：</p>
 * <ul>
 *   <li>高德原生就是 GCJ-02 + 「经度,纬度」，保持原样，不影响已有行为和提示词；</li>
 *   <li>百度原生是 BD-09 + 「纬度,经度」，实现内部用 {@code coord_type=gcj02} 做进出转换。
 *       于是同一份坐标可以在两个 provider 之间直接混用，模型看到的参数说明也不用改。</li>
 * </ul>
 *
 * <p>各家错误码差异很大（高德 {@code status=1} 是成功、百度 {@code status=0} 才是成功，
 * 且百度失败时 HTTP 依然是 200），所以实现方必须把错误翻译成带「下一步怎么办」的
 * {@link IllegalStateException}，工具层不做任何重试判断。</p>
 *
 * <p>能力差异如实体现在返回值上，不强行对齐：例如百度的地理编码只返回一个最匹配结果，
 * {@link #geocode} 就只会有 1 条；高德会返回同名候选列表。</p>
 */
public interface MapClient {

    /** 配置里的标识：{@code amap} / {@code baidu}。 */
    String provider();

    /** 展示用名称。 */
    String displayName();

    /** Key/AK 申请页，出错提示里会带上。 */
    String applyUrl();

    boolean configured();

    /** 只露头尾的 Key，用于自检打印，便于确认"到底读没读到配置"。 */
    String maskedKey();

    /**
     * 地址 → 坐标。
     *
     * @param address 地点名称或结构化地址
     * @param city    城市名，可空
     * @return 候选列表，按可能性排序；查不到时返回空列表而不是抛异常
     */
    List<GeoPoint> geocode(String address, String city);

    /**
     * 圆形区域检索（周边）。
     *
     * @param lngLat   中心点「经度,纬度」
     * @param keyword  关键字，可空
     * @param types    高德风格的 POI 类型词（如「中餐厅」「宾馆酒店」），百度实现内部会映射成 tag
     * @param city     城市编码/城市名，可空
     * @param radius   半径（米）
     * @param pageSize 每页条数
     * @param page     页码，从 1 开始
     */
    PoiPage searchPoi(String lngLat, String keyword, String types, String city,
                      int radius, int pageSize, int page);

    /**
     * 城市内文本检索。用于景点降级数据源（不需要中心点坐标）。
     *
     * @param city     城市名或城市编码
     * @param keyword  关键字
     * @param pageSize 条数
     */
    PoiPage searchCity(String city, String keyword, int pageSize);

    /**
     * 公共交通路线规划。
     *
     * @param originLngLat 起点「经度,纬度」
     * @param destLngLat   终点「经度,纬度」
     * @param originCity   起点城市编码，可空（百度不需要）
     * @param destCity     终点城市编码，可空（百度不需要）
     */
    Transit transit(String originLngLat, String destLngLat, String originCity, String destCity);

    /**
     * 下载静态地图图片。
     *
     * @param lngLat 中心点「经度,纬度」
     * @param zoom   缩放级别，实现方负责夹到各自支持的范围
     */
    byte[] staticMap(String lngLat, int zoom);

    // ---------- 归一化结构 ----------

    /** 一个坐标候选点。{@code lngLat} 统一「经度,纬度」。 */
    record GeoPoint(String address, String lngLat, String cityCode) {
    }

    /** 一条 POI。字段缺失时用 {@code not available} 占位，与改造前的输出保持一致。 */
    record Poi(String name, String type, String address, String distanceMeters,
               String lngLat, String rating, String cost) {
    }

    /** POI 分页结果。{@code total} 是服务端给的总数，取不到时为 {@code 0}。 */
    record PoiPage(List<Poi> pois, String total) {
        public PoiPage {
            pois = List.copyOf(pois);
        }
    }

    /** 一条公共交通方案。 */
    record TransitOption(String cost, String durationSeconds, String walkingDistanceMeters) {
    }

    /** 路线规划结果，字段名沿用改造前工具的输出，避免提示词和前端受影响。 */
    record Transit(String origin, String destination, String walkingDistanceMeters,
                   String taxiCost, List<TransitOption> options) {
        public Transit {
            options = List.copyOf(options);
        }
    }
}
