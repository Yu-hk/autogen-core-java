package com.example.agentcore;

import com.example.agentcore.agent.ToolRegistry;
import com.example.agentcore.tool.travel.AmapClient;
import com.example.agentcore.tool.travel.LocationTool;
import com.example.agentcore.tool.travel.NearbyTool;
import com.example.agentcore.tool.travel.RouteTool;
import com.example.agentcore.tool.travel.TravelTools;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 旅游工具的接线测试：不联网、不调模型，验证"工具能被模型看到、能按名调用、缺 Key 可解释"。
 *
 * <p>真实 API 的正确性由 {@code mvn exec:java -Dexec.args="tools"} 自检覆盖——
 * 那一步需要真 Key 和网络，不适合放进单元测试。</p>
 */
class TravelToolsTest {

    private static final List<String> EXPECTED = List.of(
            "web_search",
            "get_location_coordinate",
            "get_attractions_information",
            "route_planning",
            "search_nearby_poi",
            "get_static_map_image",
            "save_info_and_clear_history");

    @Test
    void allTravelToolsAreRegisteredUnderTheirPythonNames() {
        ToolRegistry registry = ToolRegistry.of(TravelTools.all());

        List<String> names = registry.specifications().stream()
                .map(ToolSpecification::name)
                .sorted()
                .collect(Collectors.toList());

        assertEquals(EXPECTED.stream().sorted().collect(Collectors.toList()), names,
                "工具名必须与 Python 版一致，两边提示词才能互抄");
    }

    /** 回归点：{@code @Tool(name=...)} 与 Java 方法名不同时，按注解名必须能查到实现。 */
    @Test
    void registryResolvesAnnotatedNameNotMethodName() {
        ToolRegistry registry = ToolRegistry.of(TravelTools.all());

        String result = registry.invoke("save_info_and_clear_history", "{\"informationToSave\":\"测试一条\"}");
        assertTrue(result.contains("\"ok\":true"), "按注解名调用应命中实现，实际返回：" + result);

        String missing = registry.invoke("saveInfoAndClearHistory", "{}");
        assertTrue(missing.startsWith("ERROR"), "Java 方法名不该被当成工具名");
    }

    /** 没配 Key 时不能让整轮对话崩掉：错误文本要能回灌给模型，并且带申请地址。 */
    @Test
    void missingAmapKeyProducesActionableError() {
        AmapClient noKey = AmapClient.of(AmapClient.DEFAULT_BASE_URL, "", Duration.ofSeconds(1));

        RuntimeException geo = assertThrows(RuntimeException.class,
                () -> new LocationTool(noKey).getLocationCoordinate("岳麓山", "长沙"));
        assertTrue(geo.getMessage().contains(AmapClient.KEY_APPLY_URL), geo.getMessage());

        RuntimeException nearby = assertThrows(RuntimeException.class,
                () -> new NearbyTool(noKey).searchNearbyPoi("112.9,28.2", "0731", "中餐厅", "", 1000, 5, 1));
        assertTrue(nearby.getMessage().contains("amap.key"), nearby.getMessage());

        RuntimeException route = assertThrows(RuntimeException.class,
                () -> new RouteTool(noKey).routePlanning("112.9,28.2", "113.0,28.1", "0731", "0731"));
        assertTrue(route.getMessage().contains("amap.key"), route.getMessage());
    }

    /** 关键字和类型都不给属于调用方错误，应当直接说清楚，而不是发一个必然失败的请求。 */
    @Test
    void nearbyRejectsEmptyKeywordAndType() {
        AmapClient noKey = AmapClient.of(AmapClient.DEFAULT_BASE_URL, "fake", Duration.ofSeconds(1));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new NearbyTool(noKey).searchNearbyPoi("112.9,28.2", "0731", "", "", 1000, 5, 1));
        assertTrue(error.getMessage().contains("至少填写一个"));
        assertFalse(error.getMessage().contains("Key"), "参数校验应该发生在发请求之前");
    }
}
