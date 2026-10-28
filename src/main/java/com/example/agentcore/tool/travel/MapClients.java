package com.example.agentcore.tool.travel;

import com.example.agentcore.support.AppConfig;

/**
 * 按 {@code agent.properties} 的 {@code tool.map.provider} 选地图服务商。
 *
 * <p>只认 {@code amap}（高德，默认）与 {@code baidu}（百度）两个值。写错时直接抛配置错误，
 * 而不是悄悄退回高德——静默降级会让人排查半天"为什么填了百度 AK 却在报高德的错"。</p>
 */
public final class MapClients {

    private MapClients() {
    }

    /** 从配置构造当前选定的 client。 */
    public static MapClient fromConfig() {
        return of(AppConfig.get().mapProvider());
    }

    /** 按名字构造，便于测试与编程式指定（不读配置）。 */
    public static MapClient of(String provider) {
        String normalized = provider == null ? "" : provider.trim().toLowerCase();
        return switch (normalized) {
            case "amap", "gaode", "" -> AmapClient.fromConfig();
            case "baidu", "bd" -> BaiduClient.fromConfig();
            default -> throw new IllegalArgumentException("tool.map.provider 只能是 amap 或 baidu，当前是「"
                    + provider + "」");
        };
    }
}
