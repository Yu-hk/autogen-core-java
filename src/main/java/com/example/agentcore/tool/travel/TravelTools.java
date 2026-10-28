package com.example.agentcore.tool.travel;

/**
 * 旅游工具的聚合入口：一次性构造全部 7 个工具。
 *
 * <p>来源是 LangGraph 专题课里 Python 版「旅游规划机器人」的 {@code tools/} 目录，
 * 对应关系如下（工具名保持一致，方便两边提示词互抄）。四个地图工具走 {@link MapClient}，
 * 具体用高德还是百度由 {@code tool.map.provider} 决定，工具名和返回字段两家一致：</p>
 *
 * <table border="1">
 *   <caption>Python 工具 → Java 实现 → 依赖的外部 API</caption>
 *   <tr><th>Python</th><th>Java</th><th>外部 API / Key</th></tr>
 *   <tr><td>locations.py</td><td>{@link LocationTool}</td><td>地图地理编码（高德/百度），需 Key</td></tr>
 *   <tr><td>nearby.py</td><td>{@link NearbyTool}</td><td>地图周边检索（高德/百度），需 Key</td></tr>
 *   <tr><td>transportation.py</td><td>{@link RouteTool}</td><td>地图公交路线规划（高德/百度），需 Key</td></tr>
 *   <tr><td>static_map.py（原版未导出）</td><td>{@link StaticMapTool}</td><td>地图静态图（高德/百度），需 Key</td></tr>
 *   <tr><td>web_search.py</td><td>{@link WebSearchTool}</td><td>必应 / DuckDuckGo，无需 Key</td></tr>
 *   <tr><td>attractions.py</td><td>{@link AttractionsTool}</td><td>马蜂窝抓取，可降级到地图 POI</td></tr>
 *   <tr><td>save.py</td><td>{@link TravelNotebookTool}</td><td>本地文件，无需 Key</td></tr>
 * </table>
 */
public final class TravelTools {

    private TravelTools() {
    }

    /** 全部工具实例，直接丢给 {@code ChatAgent.Builder.tools(...)}。 */
    public static Object[] all() {
        return new Object[]{
                new WebSearchTool(),
                new LocationTool(),
                new AttractionsTool(),
                new RouteTool(),
                new NearbyTool(),
                new StaticMapTool(),
                new TravelNotebookTool()
        };
    }
}
