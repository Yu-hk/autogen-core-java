package com.example.agentcore.tool.travel;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.util.List;

/**
 * 路线规划工具（公共交通）。
 *
 * <p>对应 Python 版 {@code tools/transportation.py}。规划的是"公交+地铁+步行"的综合方案，
 * 不是驾车路线。返回值单位保持各家原样（米/秒/元），由模型换算成人话。</p>
 *
 * <p>两家字段对不齐的地方在 client 里补齐：高德直接给 {@code taxi_cost} 与总步行距离，
 * 百度只有出租车方案里的估算总价，取不到时字段留空而不是编一个数。</p>
 */
public class RouteTool {

    private final MapClient map;

    public RouteTool() {
        this(MapClients.fromConfig());
    }

    public RouteTool(MapClient map) {
        this.map = map;
    }

    @Tool(name = "route_planning",
            value = "路线规划工具。规划综合各类公共交通方式（火车、公交、地铁）的交通方案，"
                    + "返回从出发点到目的地的步行距离、出租车费用以及公共交通方案列表。"
                    + "返回结果中，距离单位都是米，时间单位都是秒，费用单位都是元。"
                    + "如果公共交通方案列表为空，说明两个地点之间没有可用的公共交通方式。")
    public String routePlanning(
            @P(description = "出发点的经纬度，以「,」分割，经度在前、纬度在后，如 117.500244,40.417801") String origin,
            @P(description = "目的地的经纬度，以「,」分割，经度在前、纬度在后，如 117.500244,40.417801") String destination,
            @P(description = "出发点所在的城市编码") String originCityCode,
            @P(description = "目的地所在的城市编码") String destCityCode) {

        MapClient.Transit transit = map.transit(origin, destination, originCityCode, destCityCode);

        if (transit.options().isEmpty()) {
            return JsonOut.of(JsonOut.object(
                    "ok", false,
                    "message", "从 " + origin + " 到 " + destination + " 没有查到公共交通方案。"
                            + "请确认经纬度与城市编码是否正确，或考虑两地距离过近/过远。",
                    "origin", origin,
                    "destination", destination));
        }

        List<Object> options = JsonOut.list();
        for (MapClient.TransitOption item : transit.options()) {
            options.add(JsonOut.object(
                    "cost", item.cost(),
                    "duration", item.durationSeconds(),
                    "walking_distance", item.walkingDistanceMeters()));
        }

        return JsonOut.of(JsonOut.object(
                "ok", true,
                "provider", map.provider(),
                "origin", transit.origin().isBlank() ? origin : transit.origin(),
                "destination", transit.destination().isBlank() ? destination : transit.destination(),
                "walking_distance", transit.walkingDistanceMeters(),
                "taxi_cost", transit.taxiCost().isBlank() ? "not available" : transit.taxiCost(),
                "public_transport_options_list", options,
                "units", "距离=米，时长=秒，费用=元"));
    }
}
