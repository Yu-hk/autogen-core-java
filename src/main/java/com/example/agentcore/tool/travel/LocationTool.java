package com.example.agentcore.tool.travel;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.util.List;

/**
 * 位置获取工具（地理编码）。
 *
 * <p>对应 Python 版 {@code tools/locations.py}。语义完全一致：按地点名 + 城市名查经纬度，
 * 因为存在同名地点，所以永远返回候选列表，让模型自己根据详细地址挑。</p>
 *
 * <p>坐标一律是「经度,纬度」+ GCJ-02，换到百度后端时由 {@link BaiduClient} 内部转换，
 * 这个工具的提示词和输出格式不需要跟着变。</p>
 */
public class LocationTool {

    private final MapClient map;

    public LocationTool() {
        this(MapClients.fromConfig());
    }

    public LocationTool(MapClient map) {
        this.map = map;
    }

    @Tool(name = "get_location_coordinate",
            value = "位置获取工具。根据地点名称和城市名称获取该地点的经纬度。"
                    + "由于可能存在同名地点，返回的是一个包含所有同名地点详细地址和经纬度的列表。")
    public String getLocationCoordinate(
            @P(description = "要获取经纬度的地点名称") String location,
            @P(description = "要获取经纬度的地点所在的城市名称，必须是地级市，不能是县级市或村镇。"
                    + "如果能确定的话填写，不能确定可以不填", required = false) String city) {

        if (location == null || location.isBlank()) {
            throw new IllegalArgumentException("location 不能为空");
        }

        List<MapClient.GeoPoint> points = map.geocode(location, city);

        if (points.isEmpty()) {
            // 原版在这里也是回一句自然语言错误，而不是抛异常——模型能读到原因并改参数重试
            return JsonOut.of(JsonOut.object(
                    "ok", false,
                    "message", "没有查到「" + location + "」的坐标。请确认名称是否准确，"
                            + "或者换个更正式的名称再试（俗称、简称往往查不到）。",
                    "query", location,
                    "city", city == null ? "" : city));
        }

        List<Object> candidates = JsonOut.list();
        for (MapClient.GeoPoint point : points) {
            candidates.add(JsonOut.object(
                    "address", point.address(),
                    "coordinate", point.lngLat(),
                    "citycode", point.cityCode()));
        }

        return JsonOut.of(JsonOut.object(
                "ok", true,
                "provider", map.provider(),
                "query", location,
                "city", city == null ? "" : city,
                "count", candidates.size(),
                "candidates", candidates,
                "note", "coordinate 的顺序是「经度,纬度」。同名地点需结合 address 判断，选错会让后续路线规划出错。"
                        + ("baidu".equals(map.provider())
                        ? "百度地理编码只返回一个最匹配结果，若明显不准，请补上城市名或换个更正式的名称。"
                        : "")));
    }
}
