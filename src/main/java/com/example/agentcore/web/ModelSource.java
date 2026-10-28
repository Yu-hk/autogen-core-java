package com.example.agentcore.web;

import com.example.agentcore.support.AppConfig;
import com.example.agentcore.support.ModelConfig;
import com.example.agentcore.support.OllamaProbe;

/**
 * 一次运行要用哪个模型。
 *
 * <p>Web 端只认"能回答问题的真实模型"，不再有离线剧本分支。但接口测试又必须可断言、
 * 不能真的去调一个 7B 模型，所以把"模型从哪来"抽成一个注入点：</p>
 * <ul>
 *   <li>生产：{@link #configured()}，读 {@code agent.properties} 并做可用性探测；</li>
 *   <li>测试：直接给一个指向 {@code MockChatServer} 的配置，剧本由测试自己排。</li>
 * </ul>
 *
 * <p>这样"mock"退化成测试替身，不再是可以从页面点出来的产品功能。</p>
 */
@FunctionalInterface
public interface ModelSource {

    /** 解析出本次运行可用的模型；不可用时抛出带人话原因的异常。 */
    ModelConfig resolve() throws ModelUnavailable;

    /** 生产实现：配置里的模型 + 就绪探测（Ollama 探活、云端查 Key）。 */
    static ModelSource configured() {
        return () -> {
            ModelConfig config = AppConfig.get().model();
            String detail = Readiness.check(config);
            if (detail != null) {
                throw new ModelUnavailable(detail);
            }
            return config;
        };
    }

    /** 模型当前是否可用；不可用时 message 是给用户看的一句话。 */
    record Readiness(boolean ready, String detail) {

        static Readiness of(ModelConfig config) {
            String failure = check(config);
            return failure == null ? new Readiness(true, "就绪") : new Readiness(false, failure);
        }

        /** @return null 表示可用，否则返回不可用的原因 */
        private static String check(ModelConfig config) {
            if (!config.isOllama()) {
                return config.hasApiKey() ? null : "缺少 API Key（agent.properties 的 model.api-key）";
            }
            OllamaProbe.Status status = OllamaProbe.probe(config.rootUrl());
            if (!status.reachable()) {
                return "连不上 " + config.rootUrl() + "，先执行 ollama serve";
            }
            if (!status.hasModel(config.model())) {
                return "本地缺少模型 " + config.model() + "，执行 ollama pull " + config.model()
                        + "，或改 model.name";
            }
            return null;
        }
    }
}
