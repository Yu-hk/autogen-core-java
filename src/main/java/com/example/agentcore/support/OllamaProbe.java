package com.example.agentcore.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Ollama 运行状态探测：服务是否启动、目标模型是否已拉取。
 *
 * <p>本地模型的失败模式和云端不同——不是鉴权错误，而是"服务没起"和"模型没下载"。
 * 这两种情况在报错信息里都表现为连接异常或 404，先探一次能给出可执行的提示。</p>
 */
public final class OllamaProbe {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            // 探测目标是本机，必须绕开系统代理，否则会误报"连不上"
            // ProxySelector.of(null) 即"始终直连"
            .proxy(ProxySelector.of(null))
            .build();

    private OllamaProbe() {
    }

    public record Status(boolean reachable, List<String> models, String error) {

        public boolean hasModel(String model) {
            // Ollama 允许省略 latest 标签
            return models.contains(model) || models.contains(model + ":latest");
        }
    }

    public static Status probe(String rootUrl) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(rootUrl + "/api/tags"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return new Status(false, List.of(), "HTTP " + response.statusCode());
            }
            JsonNode root = MAPPER.readTree(response.body());
            List<String> models = new ArrayList<>();
            for (JsonNode item : root.path("models")) {
                String name = item.path("name").asText("");
                if (!name.isBlank()) {
                    models.add(name);
                }
            }
            return new Status(true, models, null);
        } catch (Exception e) {
            return new Status(false, List.of(), e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
