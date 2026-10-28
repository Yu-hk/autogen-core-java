package com.example.agentcore;

import com.example.agentcore.support.AppConfig;
import com.example.agentcore.support.ModelConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置层验证：内置配置能被正确解析，覆盖链生效，写错的值要么回退要么明确报错。
 *
 * <p>这里只测"解析"，不测网络——模型是否连得上由 {@code OllamaProbe} 与页面 health 负责。</p>
 */
class AppConfigTest {

    @AfterEach
    void clearOverrides() {
        System.clearProperty("model.name");
        System.clearProperty("model.provider");
        System.clearProperty("server.port");
        System.clearProperty("agent.config");
        System.clearProperty("TEST_MAP_TOKEN");
        System.clearProperty("TEST_ABSENT_NAME");
        // 用例里改过单例，收尾必须重载回 classpath 配置，否则后面的用例看到的是临时配置
        AppConfig.reload();
    }

    @Test
    void bundledConfigIsLoaded() {
        AppConfig app = AppConfig.get();
        ModelConfig model = app.model();

        assertEquals(8097, app.port(), "端口应来自 agent.properties");
        assertEquals("target/snapshots", app.snapshotDir().toString().replace('\\', '/'));

        assertEquals("ollama", model.provider());
        assertEquals("qwen2.5:7b", model.model());
        assertEquals("http://127.0.0.1:11434/v1", model.baseUrl());
        assertTrue(model.hasApiKey(), "Ollama 不校验 Key，但客户端要求非空占位值");
        assertEquals(0.3, model.temperature(), 1e-9, "本地小模型温度默认压低");
        assertEquals(300, model.timeout().toSeconds(), "CPU 上单轮几十秒，超时给足");

        // 内置配置里第二套模型是本机 20B：分组流水线的总结侧
        assertEquals("gpt-oss:20b", app.summarizerModel().model());
        assertEquals("ollama", app.summarizerModel().provider());
        assertEquals(600, app.summarizerModel().timeout().toSeconds(), "20B 写长回答要给足时间");
        assertTrue(app.hasSeparateSummarizer());
    }

    @Test
    void explicitValuesOverrideDefaults() {
        Properties props = new Properties();
        props.setProperty("model.provider", "ollama");
        props.setProperty("model.base-url", "http://192.168.1.9:11434/v1");
        props.setProperty("model.name", "qwen2.5:14b");
        props.setProperty("model.temperature", "0.15");
        props.setProperty("model.timeout-seconds", "42");
        props.setProperty("server.port", "9100");

        AppConfig app = AppConfig.of(props);
        ModelConfig model = app.model();

        assertEquals("qwen2.5:14b", model.model());
        assertEquals("http://192.168.1.9:11434/v1", model.baseUrl());
        assertEquals(0.15, model.temperature(), 1e-9);
        assertEquals(42, model.timeout().toSeconds());
        assertEquals(9100, app.port());
        // /v1 后缀是给 OpenAI 兼容客户端用的，探测原生 API 时要剥掉
        assertEquals("http://192.168.1.9:11434", model.rootUrl());
    }

    @Test
    void deepseekFallsBackToItsOwnDefaults() {
        Properties props = new Properties();
        props.setProperty("model.provider", "deepseek");

        ModelConfig model = AppConfig.of(props).model();

        assertEquals("deepseek", model.provider());
        assertEquals("https://api.deepseek.com/v1", model.baseUrl());
        assertEquals("deepseek-chat", model.model());
        assertEquals(0.7, model.temperature(), 1e-9, "云端大模型用常规温度");
        assertEquals(90, model.timeout().toSeconds());
        assertFalse(model.hasApiKey(), "云端必须显式给 Key，不能有默认占位");
    }

    @Test
    void systemPropertyBeatsConfig() {
        System.setProperty("model.name", "override-model");
        assertEquals("override-model", AppConfig.get().model().model());
    }

    @Test
    void unknownProviderIsRejectedLoudly() {
        Properties props = new Properties();
        props.setProperty("model.provider", "openai");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AppConfig.of(props).model());
        assertTrue(error.getMessage().contains("openai"), "报错要指出写错的值");
    }

    @Test
    void malformedNumbersFallBackInsteadOfCrashing() {
        Properties props = new Properties();
        props.setProperty("server.port", "not-a-port");
        props.setProperty("model.temperature", "");

        AppConfig app = AppConfig.of(props);
        assertEquals(8099, app.port());
        assertEquals(0.3, app.model().temperature(), 1e-9, "空值走 provider 默认");
    }

    /**
     * 密钥占位符：配置文件里只写 {@code ${NAME:}}，真实值从环境（或系统属性）注入。
     *
     * <p>这条用例守护的是"仓库里不出现真实 Key"这件事。写坏的方式很隐蔽：
     * 占位符拼错一个字符，解析结果变成空串，功能表现是"地图 Key 没配"，
     * 而看配置的人会坚称自己配过了。</p>
     */
    @Test
    void placeholderTakesValueFromSystemProperty(@TempDir Path dir) throws IOException {
        System.setProperty("TEST_MAP_TOKEN", "token-from-env");
        Path file = dir.resolve("agent.properties");
        Files.writeString(file, String.join("\n",
                "tool.custom.endpoint=https://example.test/${TEST_MAP_TOKEN}/v1",
                "tool.custom.optional=${TEST_ABSENT_NAME:}",
                "tool.custom.defaulted=${TEST_ABSENT_NAME:fallback-value}"));
        System.setProperty("agent.config", file.toString());

        AppConfig app = AppConfig.reload();

        assertEquals("https://example.test/token-from-env/v1", app.get("tool.custom.endpoint", ""));
        assertEquals("", app.get("tool.custom.optional", ""), "没有默认值就是空，不该留占位符原文");
        assertEquals("fallback-value", app.get("tool.custom.defaulted", ""));
    }

    /** 内置配置里不应再出现真实 Key：它们必须是占位符，否则打包就把密钥带出去了。 */
    @Test
    void bundledConfigKeepsSecretsAsPlaceholders() throws IOException {
        try (java.io.InputStream in = AppConfig.class.getResourceAsStream("/agent.properties")) {
            assertTrue(in != null, "classpath 应带 agent.properties");
            Properties raw = new Properties();
            raw.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            for (String key : new String[]{"tool.baidu.ak", "tool.amap.key", "model.api-key"}) {
                String value = raw.getProperty(key, "");
                assertFalse(value.matches("[A-Za-z0-9]{20,}"),
                        key + " 不该出现真实 Key（当前值形如密钥）：" + value);
            }
        }
    }

    /** 多模型是可选增强：没配就等回主模型，单模型场景的行为一个字都不能变。 */
    @Test
    void summarizerFallsBackToMainModelWhenUnset() {
        Properties props = new Properties();
        props.setProperty("model.provider", "deepseek");

        AppConfig app = AppConfig.of(props);

        assertEquals(app.model().model(), app.summarizerModel().model(),
                "没配 model.summarizer.name 时总结侧就该用主模型");
        assertFalse(app.hasSeparateSummarizer());
    }

    /** 第二套模型自己选 provider / 地址 / 超时，不继承主模型的，也不吃主模型的老式环境变量。 */
    @Test
    void summarizerIsParsedIndependently() {
        Properties props = new Properties();
        props.setProperty("model.provider", "deepseek");
        props.setProperty("model.name", "deepseek-chat");
        props.setProperty("model.summarizer.name", "gpt-oss:20b");
        props.setProperty("model.summarizer.timeout-seconds", "600");
        props.setProperty("model.summarizer.temperature", "0.4");

        AppConfig app = AppConfig.of(props);
        ModelConfig summarizer = app.summarizerModel();

        assertEquals("ollama", summarizer.provider(), "第二套模型可以自己选 provider");
        assertEquals("gpt-oss:20b", summarizer.model());
        assertEquals("http://127.0.0.1:11434/v1", summarizer.baseUrl(),
                "地址按 provider 取默认，不该被主模型的 deepseek 地址带偏");
        assertEquals(600, summarizer.timeout().toSeconds(), "20B 纯 CPU 要给更长超时");
        assertEquals(0.4, summarizer.temperature(), 1e-9);
        assertTrue(app.hasSeparateSummarizer());
    }
}
