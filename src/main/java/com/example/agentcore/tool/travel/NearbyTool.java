package com.example.agentcore.tool.travel;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.util.List;

/**
 * 周边搜索工具（POI 圆形区域检索）。
 *
 * <p>对应 Python 版 {@code tools/nearby.py}。用来找景点附近的餐馆和酒店。
 * 评分与人均消费不是每个 POI 都有，取不到时统一给 {@code not available}。</p>
 *
 * <p>入参仍按高德的类型词描述（「中餐厅」「宾馆酒店」），换百度后端时由
 * {@link BaiduClient#searchPoi} 映射成百度的 {@code tag}，提示词不用改。</p>
 */
public class NearbyTool {

    /** 高德 POI 类型里可用于住宿/餐饮的白名单，写进参数说明里能显著降低模型乱填的概率。 */
    private static final String TYPE_HINT =
            "如果查询住宿，只能填写以下类型：「宾馆酒店」「旅馆招待所」。"
                    + "如果查询餐饮，只能填写以下类型：「中餐厅」「外国餐厅」「快餐厅」。多个类型用「|」分割。";

    private final MapClient map;

    public NearbyTool() {
        this(MapClients.fromConfig());
    }

    public NearbyTool(MapClient map) {
        this.map = map;
    }

    @Tool(name = "search_nearby_poi",
            value = "周边搜索工具。根据中心点坐标和关键字或POI类型搜索周边POI。"
                    + "返回结果中，距离的单位都是米，费用的单位都是元。")
    public String searchNearbyPoi(
            @P(description = "中心点坐标，以「,」分割，经度在前、纬度在后，如 117.500244,40.417801") String location,
            @P(description = "查询中心点所在的城市编码", required = false) String city,
            @P(description = TYPE_HINT + "关键字和POI类型二者至少填写一个。", required = false) String types,
            @P(description = "要搜索的关键字。除非用户特别指定了具体地点名称（如「星巴克」「希尔顿」），否则不要填写！"
                    + "一次只能搜索一个关键字。", required = false) String keyword,
            @P(description = "查询半径，单位米", required = false) Integer radius,
            @P(description = "每页搜索结果数目", required = false) Integer offset,
            @P(description = "当前页数", required = false) Integer page) {

        boolean noKeyword = keyword == null || keyword.isBlank();
        boolean noTypes = types == null || types.isBlank();
        if (noKeyword && noTypes) {
            // 原版抛 ValueError，这里给同样的可读错误；ToolRegistry 会包成 ERROR 文本回灌给模型
            throw new IllegalArgumentException("关键字（keyword）和 POI 类型（types）至少填写一个。");
        }

        MapClient.PoiPage result = map.searchPoi(
                location,
                noKeyword ? "" : keyword,
                noTypes ? "" : types,
                city,
                radius == null || radius <= 0 ? 5000 : radius,
                offset == null || offset <= 0 ? 20 : offset,
                page == null || page <= 0 ? 1 : page);

        String what = noKeyword ? types : keyword;
        if (result.pois().isEmpty()) {
            return JsonOut.of(JsonOut.object(
                    "ok", false,
                    "message", "在 " + location + " 周边没有搜索到「" + what
                            + "」相关结果。可以尝试扩大搜索半径，或者更换关键字/POI类型。",
                    "center_point", location));
        }

        List<Object> pois = JsonOut.list();
        for (MapClient.Poi poi : result.pois()) {
            pois.add(JsonOut.object(
                    "name", poi.name(),
                    "type", poi.type(),
                    "address", poi.address(),
                    "distance", poi.distanceMeters(),
                    "location", poi.lngLat(),
                    "rating", poi.rating(),
                    "cost", poi.cost()));
        }

        return JsonOut.of(JsonOut.object(
                "ok", true,
                "provider", map.provider(),
                "center_point", location,
                "search_result_count", result.total(),
                "returned", pois.size(),
                "pois", pois));
    }
}
