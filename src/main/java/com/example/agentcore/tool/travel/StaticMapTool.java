package com.example.agentcore.tool.travel;

import com.example.agentcore.support.AppConfig;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 静态地图工具。
 *
 * <p>对应 Python 版 {@code tools/static_map.py}，但修正了两处问题：</p>
 * <ul>
 *   <li>原版把图片写在进程当前目录，跑几次就散一地；这里统一落到 {@code tool.notes-dir/maps}。</li>
 *   <li>原版函数名与 locations.py 的 {@code get_location_coordinate} 重名，
 *       在 LangGraph 里挂两个同名工具会互相覆盖；这里改名 {@code get_static_map_image}。</li>
 * </ul>
 *
 * <p>另外原版没在 {@code tools/__init__.py} 里导出这个工具，等于死代码；
 * 这里补进工具清单，需要时模型可以调。</p>
 */
public class StaticMapTool {

    private final MapClient map;
    private final Path dir;

    public StaticMapTool() {
        this(MapClients.fromConfig(), AppConfig.get().notesDir().resolve("maps"));
    }

    public StaticMapTool(MapClient map, Path dir) {
        this.map = map;
        this.dir = dir;
    }

    @Tool(name = "get_static_map_image",
            value = "地图获取工具。根据地点经纬度下载一张静态地图图片，返回本地文件路径。")
    public String getStaticMapImage(
            @P(description = "地点的经纬度，以「,」分割，经度在前、纬度在后，如 117.500244,40.417801") String location,
            @P(description = "地图缩放级别 1-17，默认 10", required = false) Integer zoom) {

        byte[] image = map.staticMap(location, zoom == null || zoom <= 0 ? 10 : zoom);

        String fileName = location.replace(".", "_").replace(",", "_") + ".png";
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(fileName);
            Files.write(file, image);
            return JsonOut.of(JsonOut.object(
                    "ok", true,
                    "provider", map.provider(),
                    "file", file.toAbsolutePath().toString(),
                    "bytes", image.length,
                    "note", "这是本地文件路径，用户需要自己在磁盘上打开看图。"));
        } catch (IOException e) {
            throw new IllegalStateException("地图图片已下载（" + image.length + " 字节）但写入失败："
                    + e.getMessage(), e);
        }
    }
}
