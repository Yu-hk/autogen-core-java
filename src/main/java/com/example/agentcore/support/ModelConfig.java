package com.example.agentcore.support;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

import java.time.Duration;

/**
 * 一个模型端点的连接参数。
 *
 * <p>本地 Ollama 与云端 DeepSeek 都提供 OpenAI 兼容端点（Ollama 的是 {@code /v1}），
 * LangChain4j 的 {@link OpenAiChatModel} 支持自定义 {@code baseUrl}，
 * 因此两者共用同一套客户端，切换只改 {@code agent.properties}、不动编排代码。</p>
 *
 * <p>本类只描述"怎么连"，不决定"连哪个"——后者由 {@link AppConfig#model()} 从配置读出。</p>
 */
public record ModelConfig(String provider, String baseUrl, String model, String apiKey,
                          double temperature, Duration timeout) {

    /**
     * 离线 Mock 端点。
     *
     * <p>温度固定 0：MockChatServer 不读这个字段，但测试断言不该有任何随机性来源；
     * 超时给 30 秒，本地回环足够。</p>
     */
    public static ModelConfig forMock(String baseUrl) {
        return new ModelConfig("mock", baseUrl, "mock-model", "mock-key",
                0.0, Duration.ofSeconds(30));
    }

    public boolean isOllama() {
        return "ollama".equals(provider);
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** 去掉 {@code /v1} 后缀，得到 Ollama 原生 API 的根地址（用于探测 /api/tags）。 */
    public String rootUrl() {
        return baseUrl.replaceAll("/v1/?$", "");
    }

    public ChatModel newChatModel() {
        return newChatModel(temperature);
    }

    public ChatModel newChatModel(double temperature) {
        // JDK HttpClient 会把回环地址也发给系统代理，必须在建客户端前修掉
        ProxySupport.bypassProxyForLocalhost();
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(model)
                .temperature(temperature)
                .timeout(timeout)
                .build();
    }

    public StreamingChatModel newStreamingChatModel() {
        return newStreamingChatModel(temperature);
    }

    /**
     * 流式客户端。与 {@link #newChatModel(double)} 走同一个 OpenAI 兼容端点，
     * 只是把 {@code stream=false} 换成 {@code stream=true}（SSE 分块）。
     *
     * <p>工具调用在流式下依然可用：分片的 {@code tool_calls} 由 LangChain4j 聚合后
     * 在 {@code onCompleteResponse} 里给出完整请求，编排层不必感知分片细节。</p>
     */
    public StreamingChatModel newStreamingChatModel(double temperature) {
        ProxySupport.bypassProxyForLocalhost();
        return OpenAiStreamingChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(model)
                .temperature(temperature)
                .timeout(timeout)
                .build();
    }

    /** 打印用，不泄露 key。 */
    public String describe() {
        return provider + " · " + model + " @ " + baseUrl;
    }
}
