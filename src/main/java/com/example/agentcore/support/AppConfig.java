package com.example.agentcore.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * 运行配置：模型地址、模型名、端口、快照目录全部来自 {@code agent.properties}。
 *
 * <p>为什么不再从环境变量读：模型配置是"部署形态"的一部分，散在多个环境变量里
 * 既不可版本化，也无法一眼看全。配置文件是唯一真相，环境变量退化成覆盖手段。</p>
 *
 * <p>覆盖优先级（从高到低）：</p>
 * <ol>
 *   <li>系统属性，如 {@code -Dmodel.name=qwen2.5:14b}</li>
 *   <li>环境变量，如 {@code MODEL_NAME} / {@code OLLAMA_MODEL} / {@code DEEPSEEK_API_KEY}</li>
 *   <li>外部配置文件：{@code -Dagent.config=<path>}，或启动目录下的 {@code agent.properties}</li>
 *   <li>classpath 内置的 {@code agent.properties}</li>
 * </ol>
 *
 * <p>环境变量只是兼容手段（老脚本、CI 密钥注入），不是必需项。</p>
 */
public final class AppConfig {

    /** 外部配置文件默认名：放在启动目录即可生效。 */
    public static final String FILE_NAME = "agent.properties";

    /** 显式指定配置文件的系统属性名。 */
    public static final String FILE_PROPERTY = "agent.config";

    private static final String CLASSPATH_FILE = "/agent.properties";

    /**
     * 本机私有覆盖层：与 {@code agent.properties} 同格式，但**不进版本库**。
     *
     * <p>它解决的是"密钥该放哪儿"这个具体问题：真实 AK、数据库口令写在
     * {@code agent.properties} 里，只要一提交就泄露了；全靠环境变量又让本机开发多一步。
     * 折中是——版本库里只出现 {@code ${TOOL_BAIDU_AK:}} 这样的占位符，
     * 真实值放这个文件（已 gitignore）或 CI 的密钥里。</p>
     */
    public static final String LOCAL_FILE_NAME = "agent.local.properties";

    /** 占位符：{@code ${NAME}} 或 {@code ${NAME:默认值}}。 */
    private static final java.util.regex.Pattern PLACEHOLDER =
            java.util.regex.Pattern.compile("\\$\\{([A-Za-z0-9_.\\-]+)(?::([^}]*))?}");

    private static final String DEFAULT_PROVIDER = "ollama";
    private static final String DEFAULT_OLLAMA_BASE_URL = "http://127.0.0.1:11434/v1";
    private static final String DEFAULT_OLLAMA_MODEL = "qwen2.5:7b";
    private static final String DEFAULT_DEEPSEEK_BASE_URL = "https://api.deepseek.com/v1";
    private static final String DEFAULT_DEEPSEEK_MODEL = "deepseek-chat";
    /** 总结阶段的默认模型：本机 20B，纯 CPU 跑一次汇总约一分钟，够用且不花钱。 */
    private static final String DEFAULT_SUMMARIZER_MODEL = "gpt-oss:20b";

    private static volatile AppConfig instance;

    private final Properties values;
    private final String source;

    private AppConfig(Properties values, String source) {
        this.values = values;
        this.source = source;
    }

    /** 进程内单例，首次访问时加载。 */
    public static AppConfig get() {
        AppConfig local = instance;
        if (local == null) {
            synchronized (AppConfig.class) {
                local = instance;
                if (local == null) {
                    local = load();
                    instance = local;
                }
            }
        }
        return local;
    }

    /** 强制重新读盘并替换单例；改完配置不想重启进程时用。 */
    public static synchronized AppConfig reload() {
        instance = load();
        return instance;
    }

    /** 由给定属性构造，不参与单例；便于测试与编程式覆盖。 */
    public static AppConfig of(Properties values) {
        Properties copy = new Properties();
        copy.putAll(values);
        return new AppConfig(copy, "内存构造");
    }

    /** 配置来源，仅用于启动日志与排查。 */
    public String source() {
        return source;
    }

    // ---------- 服务 ----------

    public int port() {
        return intValue(pick(
                System.getProperty("server.port"),
                System.getenv("SERVER_PORT"),
                values.getProperty("server.port")), 8099);
    }

    public Path snapshotDir() {
        return Path.of(pick(
                System.getProperty("server.snapshot-dir"),
                System.getenv("SERVER_SNAPSHOT_DIR"),
                values.getProperty("server.snapshot-dir"),
                "target/snapshots"));
    }

    /**
     * 绑定地址。默认只绑回环（本机演示工具，不该暴露到局域网）；
     * 用 Docker 部署想从宿主机浏览器访问时，设 {@code -Dserver.host=0.0.0.0} 或
     * 环境变量 {@code SERVER_HOST=0.0.0.0} 即可。
     */
    public String host() {
        return pick(
                System.getProperty("server.host"),
                System.getenv("SERVER_HOST"),
                values.getProperty("server.host"),
                "127.0.0.1");
    }

    /**
     * 访问口令（{@code server.auth-token}，环境变量 {@code SERVER_AUTH_TOKEN}）。
     *
     * <p>非空时所有 {@code /api/*} 都要带它；空表示不鉴权，此时只允许绑定回环地址，
     * 否则服务直接拒绝启动——「零鉴权 + 绑 0.0.0.0」等于把宿主机文件读取和命令执行
     * 开放给整个网段，不能靠部署时的自觉来保证。</p>
     */
    public String authToken() {
        return get("server.auth-token", "");
    }

    /**
     * 显式放行「不鉴权也能对外」的逃生开关（{@code server.auth-allow-anonymous}）。
     *
     * <p>只给「前面已经有反代做 SSO」这类场景用；开了会在启动日志里持续告警。</p>
     */
    public boolean authAllowAnonymous() {
        return getBoolean("server.auth-allow-anonymous", false);
    }

    /** 绑定地址是不是本机回环：只有这种情况才允许不带口令对外提供接口。 */
    /**
     * 是否下发 HSTS。
     *
     * <p>默认关闭，而且不该在内网随手打开：HSTS 的语义是"这个域名以后只能用 https"，
     * 一旦浏览器记住，再用 http 访问会被直接拦成错误页，而内网多数没配证书。
     * 真正放在 HTTPS 反代后面时再打开。</p>
     */
    public boolean hsts() {
        return getBoolean("server.hsts", false);
    }

    public boolean bindsLoopback() {
        String host = host().trim().toLowerCase(Locale.ROOT);
        return "127.0.0.1".equals(host) || "localhost".equals(host) || "::1".equals(host);
    }

    // ---------- 模型 ----------

    /** 主模型：收集数据、驱动工具，也是所有场景的默认发言人。 */
    public ModelConfig model() {
        return readModel("model", DEFAULT_PROVIDER, DEFAULT_OLLAMA_MODEL);
    }

    /**
     * 总结阶段用的第二个模型，供「分工流水线」场景使用。
     *
     * <p>没配 {@code model.summarizer.name} 时直接返回主模型——单模型场景的行为完全不变，
     * 页面也不会凭空多出一个模型名。这条回退是刻意的：多模型是可选增强，不是新的必需项。</p>
     */
    public ModelConfig summarizerModel() {
        String explicit = pick(
                System.getProperty("model.summarizer.name"),
                System.getenv("MODEL_SUMMARIZER_NAME"),
                values.getProperty("model.summarizer.name"));
        if (explicit.isBlank()) {
            return model();
        }
        return readModel("model.summarizer", DEFAULT_PROVIDER, DEFAULT_SUMMARIZER_MODEL);
    }

    /** 是否真的配了两个不同的模型（页面据此决定要不要显示第二段）。 */
    public boolean hasSeparateSummarizer() {
        ModelConfig main = model();
        ModelConfig summarizer = summarizerModel();
        return !summarizer.model().equals(main.model())
                || !summarizer.baseUrl().equals(main.baseUrl());
    }

    /**
     * 读一套模型配置。{@code ns} 是命名空间（{@code model} 或 {@code model.summarizer}），
     * 环境变量名由它机械推导（如 {@code MODEL_SUMMARIZER_NAME}）。
     *
     * <p>老式变量名 {@code OLLAMA_*} / {@code DEEPSEEK_*} 只给主模型享用：第二套模型若也接受，
     * 就会被主模型的地址和模型名污染，等于没配。</p>
     */
    private ModelConfig readModel(String ns, String defaultProvider, String defaultOllamaModel) {
        String env = ns.toUpperCase().replace('.', '_').replace('-', '_');
        boolean main = "model".equals(ns);

        String provider = pick(
                System.getProperty(ns + ".provider"),
                System.getenv(env + "_PROVIDER"),
                values.getProperty(ns + ".provider"),
                defaultProvider).toLowerCase();

        if (!provider.equals("ollama") && !provider.equals("deepseek")) {
            throw new IllegalArgumentException(
                    ns + ".provider 只能是 ollama 或 deepseek，当前是 \"" + provider + "\"");
        }

        boolean deepseek = provider.equals("deepseek");
        String legacy = deepseek ? "DEEPSEEK" : "OLLAMA";

        String baseUrl = pick(
                System.getProperty(ns + ".base-url"),
                System.getenv(env + "_BASE_URL"),
                main ? System.getenv(legacy + "_BASE_URL") : null,
                values.getProperty(ns + ".base-url"),
                deepseek ? DEFAULT_DEEPSEEK_BASE_URL : DEFAULT_OLLAMA_BASE_URL);

        String model = pick(
                System.getProperty(ns + ".name"),
                System.getenv(env + "_NAME"),
                main ? System.getenv(legacy + "_MODEL") : null,
                values.getProperty(ns + ".name"),
                deepseek ? DEFAULT_DEEPSEEK_MODEL : defaultOllamaModel);

        String apiKey = pick(
                System.getProperty(ns + ".api-key"),
                System.getenv(env + "_API_KEY"),
                main ? System.getenv(legacy + "_API_KEY") : null,
                values.getProperty(ns + ".api-key"),
                // Ollama 不校验 Key，但 LangChain4j 的 builder 要求非空
                deepseek ? "" : "ollama");

        // 温度与超时留空表示"按 provider 默认"，本地小模型与云端大模型的合理值不同
        double temperature = doubleValue(pick(
                System.getProperty(ns + ".temperature"),
                System.getenv(env + "_TEMPERATURE"),
                values.getProperty(ns + ".temperature")), deepseek ? 0.7 : 0.3);

        long timeoutSeconds = longValue(pick(
                System.getProperty(ns + ".timeout-seconds"),
                System.getenv(env + "_TIMEOUT_SECONDS"),
                values.getProperty(ns + ".timeout-seconds")), deepseek ? 90 : 300);

        return new ModelConfig(provider, baseUrl, model, apiKey,
                temperature, Duration.ofSeconds(timeoutSeconds));
    }

    // ---------- 通用读取 ----------

    /**
     * 读任意配置项，优先级与 {@link #model()} 一致：系统属性 &gt; 环境变量 &gt; 配置文件。
     *
     * <p>环境变量名由 key 推导：{@code tool.amap.key} → {@code TOOL_AMAP_KEY}，
     * 方便把 Key 放进 CI 密钥而不是写进文件。</p>
     */
    public String get(String key, String fallback) {
        String value = pick(
                System.getProperty(key),
                System.getenv(key.toUpperCase().replace('.', '_')),
                values.getProperty(key));
        return value.isBlank() ? fallback : value;
    }

    public long getLong(String key, long fallback) {
        return longValue(get(key, ""), fallback);
    }

    public boolean getBoolean(String key, boolean fallback) {
        String value = get(key, "");
        return value.isBlank() ? fallback : Boolean.parseBoolean(value);
    }

    /** 工具产生的本地文件目录（地图图片、行程笔记）。 */
    public Path notesDir() {
        return Path.of(get("tool.notes-dir", "target/notes"));
    }

    /**
     * 编码工具的工作目录：模型能读写的全部范围。
     *
     * <p>默认放在 {@code target/code-workspace}——本来就在构建产物目录里，
     * {@code mvn clean} 就能整个清掉，不会在源码树里留下一堆模型写出来的临时文件。</p>
     */
    public Path codeWorkspace() {
        return Path.of(get("tool.code.workspace", "target/code-workspace"));
    }

    /**
     * 额外允许**只读**的目录：{@code tool.code.read-only-dirs}，逗号或分号分隔。
     *
     * <p>用途是让它读已有工程（真实项目、别人的代码库），而不是只能在空沙箱里从零写。
     * 只影响读：{@code read_file}/{@code read_dir}/{@code list_files}/{@code search_code} 认这些目录，
     * {@code write_file}/{@code edit_file} 永远不认——读得到不等于改得动。</p>
     *
     * <p>默认空：不给配置就是"什么外部目录都读不到"，这是安全默认。</p>
     */
    public List<Path> codeReadOnlyDirs() {
        return pathList("tool.code.read-only-dirs", "");
    }

    /** 逗号或分号分隔的路径列表；空项丢掉。 */
    private List<Path> pathList(String key, String fallback) {
        String raw = get(key, fallback);
        List<Path> dirs = new ArrayList<>();
        for (String part : raw.split("[,;]")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                dirs.add(Path.of(trimmed));
            }
        }
        return dirs;
    }

    /**
     * 额外允许**写入**的目录：{@code tool.code.writable-dirs}，逗号或分号分隔。
     *
     * <p>正常用法是"在页面上选工作目录"，选中的目录本身就是可写的，不需要配这个。
     * 它解决的是另一种场景：一次要改两个仓库（比如后端 + 前端），选一个当工作目录，
     * 另一个用这里挂进来。默认空。</p>
     */
    public List<Path> codeWritableDirs() {
        return pathList("tool.code.writable-dirs", "");
    }

    /**
     * 允不允许在页面上选本地目录（{@code tool.code.allow-select}，默认开）。
     *
     * <p>关掉之后 {@code /api/run} 的 workspace 参数会被忽略，只能用配置里的工作目录——
     * 部署成多人共用时用得上。</p>
     */
    public boolean codeAllowSelect() {
        return !"false".equalsIgnoreCase(get("tool.code.allow-select", "true"));
    }

    /**
     * 页面上「可选的工作目录」白名单：只有落在这些根之下的路径才允许当工作区。
     *
     * <p>由三处配置合并而成，顺序即优先级（都只是「允许」，不代表可写）：
     * {@code tool.code.workspace}（默认工作区）、{@code tool.code.writable-dirs}（额外可写）、
     * {@code tool.code.read-only-dirs}（只读）。</p>
     *
     * <p>为什么要有白名单：{@code /api/run} 的 {@code workspace} 来自页面，
     * 没有白名单时它等于「宿主机任意目录」——配上 {@code read_file} 就是任意文件读取。
     * 本机自用无所谓，一旦绑到 0.0.0.0 就是数据泄露。</p>
     */
    public List<Path> codeAllowedRoots() {
        List<Path> roots = new ArrayList<>();
        roots.add(codeWorkspace());
        roots.addAll(codeWritableDirs());
        roots.addAll(codeReadOnlyDirs());
        return roots;
    }

    /**
     * 写入保护（{@code tool.code.write-protect}，默认开）。
     *
     * <p>开着时 {@code edit_file} 必须带 {@code expectedSha256}：模型先读文件、拿到
     * 工具返回的哈希，再拿这个哈希去改。这样"读完之后文件被别人改过"会被当场拦下，
     * 而不是把别人的改动覆盖掉。关掉就是普通的字符串替换。</p>
     */
    public boolean codeWriteProtect() {
        return !"false".equalsIgnoreCase(get("tool.code.write-protect", "true"));
    }

    /** 编码工具里单条命令的默认超时（秒）；模型可以在单次调用里覆盖，但不超过上限。 */
    public int codeCommandTimeoutSeconds() {
        return (int) getLong("tool.code.command-timeout-seconds", 120);
    }

    /**
     * 模型调用的重试策略：{@code model.max-retries}（默认 1）与 {@code model.retry-backoff-ms}（默认 800）。
     *
     * <p>给 0 就是完全不重试——排查"到底是稳定的错还是偶发"时用得上，
     * 因为重试会把偶发错误藏起来。</p>
     */
    public RetryPolicy retryPolicy() {
        return new RetryPolicy(
                (int) getLong("model.max-retries", 1),
                getLong("model.retry-backoff-ms", 800));
    }

    /** 多轮对话最多保留多少个会话（超出按最久未用淘汰）。 */
    public int maxConversations() {
        return (int) getLong("web.max-conversations", 32);
    }

    /**
     * 需要跨重启保留的数据目录（{@code web.data-dir}，默认 {@code target/data}）。
     *
     * <p>现在只有会话，将来审计日志、任务队列也放这里。容器部署时把它挂成一个卷，
     * 否则镜像一换数据就跟着没了——"会话落库"在容器里最容易被这条打回原形。</p>
     */
    public Path dataDir() {
        return Path.of(get("web.data-dir", "target/data"));
    }

    /** 会话存储方式：{@code file}（默认）或 {@code none}（纯内存）。 */
    public String conversationStore() {
        return get("web.conversation-store", "file");
    }

    /**
     * 会话保留时长（小时，{@code web.conversation-ttl-hours}，默认 168 = 7 天）。
     *
     * <p>过期即删：对话里通常有真实代码片段和业务数据，无限期留在一个没人管的目录里，
     * 等于给自己攒一个逐渐长大的未分类数据副本。</p>
     */
    public long conversationTtlHours() {
        return Math.max(1, getLong("web.conversation-ttl-hours", 168));
    }

    /** 结构化日志（JSONL）目录，{@code web.log-dir}，默认 {@code target/logs}。 */
    public Path logDir() {
        return Path.of(get("web.log-dir", "target/logs"));
    }

    /** 是否写结构化日志（{@code web.structured-log}，默认 true）。关掉只剩控制台可读日志。 */
    public boolean structuredLog() {
        return !"false".equalsIgnoreCase(get("web.structured-log", "true"));
    }

    /**
     * 同时允许在跑的运行数（{@code web.max-concurrent-runs}，默认 2）。
     *
     * <p>纯 CPU 推理时一次任务 3–9 分钟，并发不是「更快」而是「一起变慢到不可用」，
     * 所以这里给硬上限：超出的请求立刻收到 429，而不是排队等五分钟再超时。
     * 有 GPU 或云端模型时把这个值调大即可。</p>
     */
    public int maxConcurrentRuns() {
        return (int) Math.max(1, getLong("web.max-concurrent-runs", 2));
    }

    /** 当前地图服务商：{@code amap}（默认）或 {@code baidu}。具体实现见 {@code tool.travel.MapClients}。 */
    public String mapProvider() {
        return get("tool.map.provider", "amap");
    }

    // ---------- 加载 ----------

    private static AppConfig load() {
        Properties merged = new Properties();
        boolean hasClasspath = false;

        try (InputStream in = AppConfig.class.getResourceAsStream(CLASSPATH_FILE)) {
            if (in != null) {
                try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    merged.load(reader);
                }
                hasClasspath = true;
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取 classpath:" + CLASSPATH_FILE + " 失败", e);
        }

        String source = hasClasspath
                ? "classpath:" + CLASSPATH_FILE
                : "内置默认值（未找到 " + FILE_NAME + "）";

        Path external = externalFile();
        if (external != null) {
            merged.putAll(readProperties(external));
            source = external.toAbsolutePath() + "（覆盖 classpath 默认）";
        }

        // 本机私有覆盖层最后读：它优先级最高，用来放不该进版本库的密钥
        Path local = Path.of(LOCAL_FILE_NAME);
        if (Files.isRegularFile(local)) {
            merged.putAll(readProperties(local));
            source = source + " + " + LOCAL_FILE_NAME;
        }

        resolvePlaceholders(merged);
        return new AppConfig(merged, source);
    }

    private static Properties readProperties(Path file) {
        Properties loaded = new Properties();
        // 必须用 Reader：Properties.load(InputStream) 按 ISO-8859-1 解码，中文注释会乱码
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            loaded.load(reader);
        } catch (IOException e) {
            throw new IllegalStateException("读取配置文件失败: " + file.toAbsolutePath(), e);
        }
        return loaded;
    }

    /**
     * 把 {@code ${NAME}} / {@code ${NAME:默认}} 换成真实值。
     *
     * <p>查找顺序是"系统属性 → 环境变量 → 冒号后的默认值"，系统属性在前是为了让测试和
     * {@code -DTOOL_BAIDU_AK=xxx} 这类临时覆盖不必先 export 一次。</p>
     *
     * <p>解析不到又没写默认值时保留空串并把 key 列出来告警——静默变成空值的话，
     * 故障现象是"地图工具说 Key 没配"，而人明明记得自己配过，很难联想到是占位符写错了名字。</p>
     */
    private static void resolvePlaceholders(Properties values) {
        List<String> unresolved = new ArrayList<>();
        for (String key : values.stringPropertyNames()) {
            String raw = values.getProperty(key);
            if (raw == null || !raw.contains("${")) {
                continue;
            }
            java.util.regex.Matcher matcher = PLACEHOLDER.matcher(raw);
            StringBuilder resolved = new StringBuilder();
            while (matcher.find()) {
                String name = matcher.group(1);
                String fallback = matcher.group(2);
                String value = pick(System.getProperty(name), System.getenv(name), fallback);
                if (value.isEmpty() && (fallback == null || fallback.isEmpty())) {
                    unresolved.add(key + " ← ${" + name + "}");
                }
                matcher.appendReplacement(resolved,
                        java.util.regex.Matcher.quoteReplacement(value));
            }
            matcher.appendTail(resolved);
            values.setProperty(key, resolved.toString());
        }
        if (!unresolved.isEmpty()) {
            System.out.println("[配置] 以下占位符没有对应的环境变量，相关功能会按“未配置”处理："
                    + String.join("，", unresolved)
                    + "。用环境变量或 " + LOCAL_FILE_NAME + " 补上即可。");
        }
    }

    private static Path externalFile() {
        String explicit = System.getProperty(FILE_PROPERTY);
        if (explicit != null && !explicit.isBlank()) {
            Path path = Path.of(explicit.trim());
            if (!Files.isRegularFile(path)) {
                // 明确指定了却不存在，属于配置错误，不该静默回退
                throw new IllegalStateException("配置文件不存在: " + path.toAbsolutePath());
            }
            return path;
        }
        Path local = Path.of(FILE_NAME);
        return Files.isRegularFile(local) ? local : null;
    }

    private static String pick(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        return "";
    }

    private static int intValue(String raw, int fallback) {
        try {
            return raw == null || raw.isBlank() ? fallback : Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long longValue(String raw, long fallback) {
        try {
            return raw == null || raw.isBlank() ? fallback : Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double doubleValue(String raw, double fallback) {
        try {
            return raw == null || raw.isBlank() ? fallback : Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
