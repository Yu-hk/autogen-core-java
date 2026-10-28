package com.example.agentcore.web;

import com.example.agentcore.agent.ChatAgent;
import com.example.agentcore.messaging.AgentEvent;
import com.example.agentcore.runtime.AgentId;
import com.example.agentcore.runtime.AgentRuntime;
import com.example.agentcore.runtime.SessionState;
import com.example.agentcore.support.AppConfig;
import com.example.agentcore.support.JsonlLog;
import com.example.agentcore.support.ModelConfig;
import com.example.agentcore.support.TeamSnapshot;
import com.example.agentcore.team.RoundRobinTeam;
import com.example.agentcore.team.SelectorTeam;
import com.example.agentcore.team.TaskResult;
import com.example.agentcore.team.Team;
import com.example.agentcore.termination.StopCondition;
import com.example.agentcore.tool.CalculatorTool;
import com.example.agentcore.tool.code.CodeWorkspace;
import com.example.agentcore.tool.travel.MapClient;
import com.example.agentcore.tool.travel.MapClients;
import com.example.agentcore.tool.travel.TravelTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * 把框架的事件流暴露成浏览器可消费的接口。
 *
 * <p>为什么用 JDK 自带的 {@link HttpServer} 而不是 Spring Boot：这个项目的价值在于"机制可断言"，
 * 引入 Web 框架只会让依赖树变重、启动变慢，而这里需要的只有三件事——静态页、几个 JSON 接口、
 * 一条 SSE 长连接。项目里的 {@code MockChatServer} 已经证明这条路够用。</p>
 *
 * <p>SSE 与事件模型是天然匹配的：框架本来就产出 {@code TextDelta} 这类过程事件，
 * 页面不需要轮询、也不需要 WebSocket，{@code EventSource} 直接接。</p>
 */
public final class AgentWebServer implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 超过这个数量才动手清理，避免每开一轮都遍历一遍 map。 */
    private static final int SESSION_PRUNE_THRESHOLD = 64;
    /** 终态会话保留多久：覆盖"刚跑完又点了一次停止"这种误操作，够用就行。 */
    private static final long SESSION_IDLE_MILLIS = 30 * 60_000L;

    /** 目录浏览一次最多回多少个子目录：一个 node_modules 级别的目录能有一万个子项。 */
    private static final int FS_MAX_ENTRIES = 300;

    private final HttpServer server;
    private final ExecutorService pool;
    private final Path snapshotDir;
    private final ModelSource modelSource;
    /** 配置快照。生产取 {@link AppConfig#get()}；测试可注入内存配置，用来验证第二套模型。 */
    private final AppConfig appConfig;
    private final Map<String, RunSession> sessions = new ConcurrentHashMap<>();

    /**
     * 在跑任务的并发闸门。
     *
     * <p>乐观地假设「用户会自觉一个一个点」是不成立的：纯 CPU 推理时两个任务并行
     * 不是各自慢一点，而是双双从 5 分钟退化到十几分钟，页面看起来就是「都卡死了」。
     * 所以这里用信号量做硬上限，拿不到直接回 429 并说清上限是多少、现在几个在跑。</p>
     */
    private final Semaphore runSlots;

    /**
     * 多轮对话的上下文，按最久未用淘汰。
     *
     * <p>{@code LinkedHashMap} 的 {@code accessOrder=true} 就是 LRU：读一次会把它挪到队尾，
     * 淘汰时从队头（最久没被追问的）开始。容量由 {@code web.max-conversations} 决定，
     * 默认 32——按一段对话几十条消息算，占用还压得住。</p>
     *
     * <p>用 {@code synchronizedMap} 而不是 ConcurrentHashMap：LRU 依赖读写顺序，
     * 并发容器给不了这个保证。这里的竞争只发生在"新开一轮"的瞬间，锁的代价可以忽略。</p>
     */
    private final Map<String, Conversation> conversations;

    /**
     * 会话的持久层。
     *
     * <p>内存里的 {@link #conversations} 只是缓存：读不到就去磁盘捞，捞到就放回缓存。
     * 这样"重启"、"被 LRU 淘汰"、"滚动发布后请求落到另一个进程"三种情况走的是同一条恢复路径，
     * 不需要为重启单独写一套逻辑。</p>
     */
    private final ConversationStore conversationStore;

    /** 进程指标，{@code /api/metrics} 直接渲染它。 */
    private final Metrics metrics = new Metrics();

    /**
     * 结构化访问日志与运行日志。
     *
     * <p>关掉时是 {@code null}，各处都按"没有日志"处理——{@code null} 比一个空实现的
     * 好处是不会让人误以为日志写成功了。</p>
     */
    private final JsonlLog accessLog;
    private final JsonlLog runLogFile;

    private final CountDownLatch shutdown = new CountDownLatch(1);

    private AgentWebServer(HttpServer server, ExecutorService pool, Path snapshotDir,
                           ModelSource modelSource, AppConfig appConfig) {
        this.server = server;
        this.pool = pool;
        this.snapshotDir = snapshotDir;
        this.modelSource = modelSource;
        this.appConfig = appConfig;
        this.runSlots = new Semaphore(Math.max(1, appConfig.maxConcurrentRuns()), true);
        int capacity = Math.max(4, appConfig.maxConversations());
        this.conversations = Collections.synchronizedMap(
                new LinkedHashMap<>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, Conversation> eldest) {
                        return size() > capacity;
                    }
                });
        this.conversationStore = ConversationStore.from(appConfig);
        // 结构化日志默认跟着数据目录走：容器里只挂一个卷就够了，不用记两个路径
        this.accessLog = appConfig.structuredLog()
                ? new JsonlLog(appConfig.logDir(), "access") : null;
        this.runLogFile = appConfig.structuredLog()
                ? new JsonlLog(appConfig.logDir(), "run") : null;
        warmConversations(capacity);
    }

    /**
     * 启动时把最近的对话读回内存，顺便清掉过期的。
     *
     * <p>只预热 {@code capacity} 段：全部读进来既慢又没必要，剩下的按需 {@code load}。
     * 顺序是"旧 → 新"地 put，因为 LRU 的队尾是最新的；反着放会让第 4 段之后
     * 把最新的几段挤出淘汰线。</p>
     */
    private void warmConversations(int capacity) {
        long cutoff = System.currentTimeMillis() - appConfig.conversationTtlHours() * 3_600_000L;
        int pruned = conversationStore.prune(cutoff);
        List<Conversation> recent = conversationStore.recent(capacity);
        for (int i = recent.size() - 1; i >= 0; i--) {
            Conversation conversation = recent.get(i);
            conversations.put(conversation.id(), conversation);
        }
        if (!recent.isEmpty() || pruned > 0) {
            System.out.println("[会话] 存储 " + conversationStore.describe()
                    + " · 恢复 " + recent.size() + " 段对话 · 清理过期 " + pruned + " 段");
        }
    }

    /** 生产入口：模型来自 {@code agent.properties}。 */
    public static AgentWebServer start(int port, Path snapshotDir) throws IOException {
        return start(port, snapshotDir, ModelSource.configured(), AppConfig.get());
    }

    /** 可注入模型的入口，供接口测试使用——页面本身没有切换模型来源的能力。 */
    public static AgentWebServer start(int port, Path snapshotDir, ModelSource modelSource) throws IOException {
        return start(port, snapshotDir, modelSource, AppConfig.get());
    }

    /**
     * 连同配置一起注入：分工场景要读 {@code model.summarizer.*}，测试需要把第二套模型
     * 指到另一个 MockChatServer，才能断言"两个角色真的打了两个端点"。
     */
    public static AgentWebServer start(int port, Path snapshotDir, ModelSource modelSource,
                                       AppConfig appConfig) throws IOException {
        Path dir = snapshotDir.toAbsolutePath().normalize();
        Files.createDirectories(dir);

        requireSafeExposure(appConfig);

        // 绑定地址可配：本机演示默认回环（127.0.0.1）；Docker 部署时用 SERVER_HOST=0.0.0.0 暴露端口
        HttpServer server = HttpServer.create(new InetSocketAddress(appConfig.host(), port), 0);
        ExecutorService pool = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "agent-web");
            thread.setDaemon(true);
            return thread;
        });
        AgentWebServer web = new AgentWebServer(server, pool, dir, modelSource, appConfig);
        server.createContext("/", web::route);
        server.setExecutor(pool);
        server.start();
        return web;
    }

    /**
     * 启动前的暴露面检查。
     *
     * <p>规则只有一条：<b>要对外监听，就必须有口令</b>。三个出口按优先级依次是
     * 设口令、显式声明"前面有反代"、退回回环。默认配置（回环 + 无口令）不受影响，
     * 所以本机开发完全不用改习惯；只有把 {@code server.host} 改成 0.0.0.0 又忘了配口令时
     * 才会被拦下——那正是最危险、也最容易被忽略的组合。</p>
     *
     * <p>不这么做的后果很具体：{@code /api/run} 能指定工作目录、{@code read_file} 能读文件、
     * {@code code_command} 能执行白名单命令，四舍五入等于把宿主机交给整个网段。</p>
     */
    private static void requireSafeExposure(AppConfig app) {
        if (!app.authToken().isEmpty()) {
            return;
        }
        if (app.bindsLoopback()) {
            System.out.println("[安全] 未设置 server.auth-token，仅监听回环地址，按本机开发模式运行。");
            return;
        }
        if (app.authAllowAnonymous()) {
            System.out.println("[安全][警告] 正在对外监听 " + app.host() + " 却未设置口令，"
                    + "且已通过 server.auth-allow-anonymous=true 显式放行匿名访问。"
                    + "任何能访问该地址的人都可以读写被允许的目录、执行白名单命令。");
            return;
        }
        throw new IllegalStateException("拒绝启动：绑定 " + app.host() + " 对外监听，但没有设置访问口令。"
                + "任选其一：① 设置口令——-Dserver.auth-token=xxx 或环境变量 SERVER_AUTH_TOKEN=xxx"
                + "（页面首次打开会要求输入）；② 前面已有反向代理做统一登录——显式放行 "
                + "-Dserver.auth-allow-anonymous=true；③ 只在本机用——-Dserver.host=127.0.0.1。");
    }

    /** 这个请求有没有带对访问口令。 */
    private boolean authorized(HttpExchange exchange) {
        String expected = appConfig.authToken();
        if (expected.isEmpty()) {
            return true;
        }
        byte[] want = expected.getBytes(StandardCharsets.UTF_8);
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            header = header.substring(7);
        }
        if (matchesToken(want, header)
                || matchesToken(want, exchange.getRequestHeaders().getFirst("X-Auth-Token"))) {
            return true;
        }
        // EventSource 加不了自定义请求头，SSE 只能把口令放查询参数里
        return matchesToken(want, query(exchange).get("token"));
    }

    private static boolean matchesToken(byte[] want, String candidate) {
        if (candidate == null) {
            return false;
        }
        // 定长比较：避免按字符提前返回把口令长度与公共前缀泄露给计时攻击
        return MessageDigest.isEqual(want, candidate.trim().getBytes(StandardCharsets.UTF_8));
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public String url() {
        return "http://127.0.0.1:" + port();
    }

    /** 阻塞主线程，直到 {@link #close()}。 */
    public void awaitShutdown() throws InterruptedException {
        shutdown.await();
    }

    @Override
    public void close() {
        server.stop(0);
        pool.shutdownNow();
        if (accessLog != null) {
            accessLog.close();
        }
        if (runLogFile != null) {
            runLogFile.close();
        }
        shutdown.countDown();
    }

    // ---------- 路由 ----------

    private void route(HttpExchange exchange) throws IOException {
        // 先打 traceId：它要出现在响应头和这一条访问日志里，晚一步就写不进已经发出的响应了
        RequestLog.begin(exchange);
        applySecurityHeaders(exchange);
        try {
            String path = exchange.getRequestURI().getPath();
            // 静态页不拦：先能打开页面，才有地方输入口令；接口一律要口令
            if (path.startsWith("/api/") && !authorized(exchange)) {
                exchange.getResponseHeaders().add("WWW-Authenticate", "Bearer realm=\"agentcore\"");
                json(exchange, 401, "{\"error\":\"需要访问口令：在页面右上角填入，"
                        + "或带 Authorization: Bearer <token>\"}");
                return;
            }
            switch (path) {
                case "/", "/index.html" -> serveStatic(exchange, "index.html");
                case "/favicon.ico" -> {
                    // 浏览器默认会来要图标，避免刷出 404 噪音
                    exchange.sendResponseHeaders(204, -1);
                    exchange.close();
                }
                case "/api/health" -> serveHealth(exchange);
                case "/api/openapi.json" -> serveOpenApi(exchange);
                case "/api/metrics" -> serveMetrics(exchange);
                case "/api/scenarios" -> serveScenarios(exchange);
                case "/api/fs/list" -> serveFsList(exchange);
                case "/api/run" -> handleRun(exchange);
                case "/api/stream" -> handleStream(exchange);
                case "/api/snapshots" -> serveSnapshots(exchange);
                case "/api/conversations" -> serveConversations(exchange);
                case "/api/resume" -> handleResume(exchange);
                case "/api/stop" -> handleStop(exchange);
                // 非接口路径：交给静态资源处理器（/static/ 下的前端产物；缺失时回退旧 /web/index.html）
                default -> serveStatic(exchange, path.startsWith("/") ? path.substring(1) : path);
            }
        } catch (Exception e) {
            json(exchange, 500, "{\"error\":\"" + escape(e.getClass().getSimpleName() + ": " + e.getMessage()) + "\"}");
        } finally {
            // SSE 与静态资源不走 json()，这里兜底记一条；之后清掉线程上下文，避免下一个请求继承本次计时
            RequestLog.finishIfPending(exchange, accessLog);
            RequestLog.clear();
        }
    }

    /**
     * 每个响应都带的安全头。
     *
     * <p>这几行不是"合规清单打勾"，而是针对这个服务的具体风险：</p>
     * <ul>
     *   <li>{@code X-Content-Type-Options: nosniff} —— 模型产出的内容会经由 API 回到页面，
     *       一旦有人把某个 JSON 响应当 HTML 打开，没有 nosniff 就可能被当成页面执行；</li>
     *   <li>{@code X-Frame-Options} + {@code frame-ancestors} —— 内部工具最容易被"顺手嵌一下"，
     *       嵌进去之后点击劫持就是现成的；</li>
     *   <li>{@code Referrer-Policy: no-referrer} —— URL 里带着 {@code ?token=}，
     *       跳外链时不能把它当 Referer 送出去。</li>
     * </ul>
     *
     * <p>CSP 不在这里加：它要按页面分两套（React 产物没有内联脚本，旧单页有），
     * 见 {@link #serveStatic}。</p>
     */
    private void applySecurityHeaders(HttpExchange exchange) {
        var headers = exchange.getResponseHeaders();
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Permissions-Policy", "geolocation=(), microphone=(), camera=()");
        if (appConfig.hsts()) {
            headers.set("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        }
    }

    /**
     * 页面用的 CSP。
     *
     * <p>分两套是因为迁移期的旧单页把脚本和样式都内联在 HTML 里，严格策略会把它整个打黑；
     * 而正常部署（有 React 产物）不该为了兼容它而永久放宽 script-src ——
     * 放宽 script-src 等于放弃 CSP 的主要价值。</p>
     */
    static String contentSecurityPolicy(boolean legacyInlinePage) {
        String scriptSrc = legacyInlinePage ? "'self' 'unsafe-inline'" : "'self'";
        return "default-src 'self'; "
                + "script-src " + scriptSrc + "; "
                // style 必须留 inline：React 的 style 属性与一些图表初始化都靠它
                + "style-src 'self' 'unsafe-inline'; "
                + "img-src 'self' data: blob:; "
                + "font-src 'self' data:; "
                // 只允许同源：SSE、fetch、以及将来的同源接口都走这条
                + "connect-src 'self'; "
                + "object-src 'none'; "
                + "frame-ancestors 'none'; "
                + "base-uri 'self'; "
                + "form-action 'self'";
    }

    /**
     * 静态资源：前端构建产物落在 classpath 的 {@code /static/} 下（index.html + assets/*）。
     *
     * <p>用 React + Vite 重写前端后，{@code npm run build} 的输出目录就是
     * {@code src/main/resources/static}，由 Maven 原样拷进 {@code target/classes}。
     * 这里按相对路径读出来直出；index.html 缺失时回退旧的 {@code /web/index.html}，
     * 保证「没构建前端」时旧页面仍然能加载，不会被一棍子打死成 404。</p>
     */
    private void serveStatic(HttpExchange exchange, String relative) throws IOException {
        byte[] body = readResource("/static/" + relative);
        boolean legacyPage = false;
        if (body == null && ("index.html".equals(relative) || "web/index.html".equals(relative))) {
            // 兜底：迁移过渡期，构建产物还没生成时还能用旧的单文件页面；
            // 显式访问 /web/index.html 也直接指到旧页面，避免 404
            body = readResource("/web/index.html");
            legacyPage = body != null;
        }
        if (body == null) {
            json(exchange, 404, "{\"error\":\"not found: " + relative + "\"}");
            return;
        }
        if (relative.endsWith(".html")) {
            exchange.getResponseHeaders().set("Content-Security-Policy",
                    contentSecurityPolicy(legacyPage));
        }
        exchange.getResponseHeaders().add("Content-Type", contentTypeOf(relative));
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
        metrics.http(200);
        RequestLog.finish(exchange, 200, accessLog);
    }

    private static byte[] readResource(String classPath) {
        try (InputStream in = AgentWebServer.class.getResourceAsStream(classPath)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    private static String contentTypeOf(String relative) {
        int dot = relative.lastIndexOf('.');
        String ext = dot >= 0 ? relative.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        return switch (ext) {
            case "html" -> "text/html; charset=utf-8";
            case "js" -> "application/javascript; charset=utf-8";
            case "css" -> "text/css; charset=utf-8";
            case "json", "map" -> "application/json; charset=utf-8";
            case "svg" -> "image/svg+xml";
            case "ico" -> "image/x-icon";
            case "png" -> "image/png";
            case "woff2" -> "font/woff2";
            case "txt" -> "text/plain; charset=utf-8";
            default -> "application/octet-stream";
        };
    }

    /**
     * 接口描述文件：{@code openapi.json} 打在 jar 里，跟着版本走。
     *
     * <p>不做运行时生成——生成器会把内部结构也写进去，而且一旦反射出错就是启动崩。
     * 手写 + {@code OpenApiTest} 校验路径存在，是两个选项里更省心的那个。</p>
     */
    private void serveOpenApi(HttpExchange exchange) throws IOException {
        byte[] body = readResource("/openapi.json");
        if (body == null) {
            json(exchange, 500, "{\"error\":\"openapi.json 没有被打进 jar\"}");
            return;
        }
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
        metrics.http(200);
        RequestLog.finish(exchange, 200, accessLog);
    }

    private void serveHealth(HttpExchange exchange) throws IOException {
        AppConfig app = appConfig;
        ModelConfig config = app.model();
        ModelSource.Readiness readiness = ModelSource.Readiness.of(config);

        var node = MAPPER.createObjectNode();
        node.put("ok", true);
        node.put("port", port());
        node.put("provider", config.provider());
        node.put("model", config.model());
        node.put("baseUrl", config.baseUrl());
        node.put("ready", readiness.ready());
        node.put("detail", readiness.detail());
        node.put("configSource", app.source());
        // 会话持久化状态：出问题时第一句话通常是"重启后我的对话怎么没了"，
        // 与其让人去翻配置，不如直接告诉他存在哪、存了几段
        node.put("conversationStore", conversationStore.describe());
        node.put("conversations", conversations.size());
        // 分工流水线场景要用第二套模型；没配就回退主模型，页面据此决定要不要显示"→ 总结模型"。
        // 第二套模型不可用不该拦住整个页面（其他场景仍然能跑），所以只报告状态，不参与 ready 判定。
        try {
            ModelConfig summarizer = app.summarizerModel();
            node.put("summarizerModel", app.hasSeparateSummarizer() ? summarizer.model() : "");
            node.put("summarizerProvider", summarizer.provider());
            node.put("summarizerDetail", ModelSource.Readiness.of(summarizer).detail());
        } catch (RuntimeException e) {
            node.put("summarizerModel", "");
            node.put("summarizerProvider", "");
            node.put("summarizerDetail", "配置错误：" + e.getMessage());
        }
        // 旅游场景能不能跑，取决于当前地图服务商的 Key；页面据此给提示，而不是等运行失败才说。
        // 配置写错（如 tool.map.provider=tencent）不能让整个 /api/health 挂掉，否则页面连错误都显示不出来。
        try {
            MapClient map = MapClients.fromConfig();
            node.put("mapProvider", map.provider());
            node.put("mapName", map.displayName());
            node.put("mapApplyUrl", map.applyUrl());
            node.put("mapKeyField", "baidu".equals(map.provider()) ? "tool.baidu.ak" : "tool.amap.key");
            node.put("mapReady", map.configured());
        } catch (IllegalArgumentException e) {
            node.put("mapProvider", "invalid");
            node.put("mapName", "地图配置错误");
            node.put("mapApplyUrl", "");
            node.put("mapKeyField", "");
            node.put("mapReady", false);
            node.put("mapError", e.getMessage());
        }
        json(exchange, 200, node.toString());
    }

    /**
     * Prometheus 抓取口，文本格式（{@code text/plain; version=0.0.4}）。
     *
     * <p>走 {@code /api/} 前缀就意味着要口令——这是刻意的：指标里能看出业务量、
     * 失败率、内存水位，不该顺手对全网开放。Prometheus 的 scrape_config 里加一条
     * {@code authorization: {type: Bearer, credentials: <token>}} 即可。</p>
     */
    private void serveMetrics(HttpExchange exchange) throws IOException {
        int limit = Math.max(1, appConfig.maxConcurrentRuns());
        // 占用中的名额 = 上限 - 剩余，比单独维护一个计数器更不容易算错
        int active = limit - runSlots.availablePermits();
        byte[] bytes = metrics.render(conversations.size(), active, limit)
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
        metrics.http(200);
        RequestLog.finish(exchange, 200, accessLog);
    }

    private void serveScenarios(HttpExchange exchange) throws IOException {
        var array = MAPPER.createArrayNode();
        for (Scenario scenario : Scenario.all()) {
            var node = array.addObject();
            node.put("id", scenario.id());
            node.put("title", scenario.title());
            node.put("description", scenario.description());
            node.put("task", scenario.task());
            node.put("stopWord", scenario.stopWord());
            node.put("maxRounds", scenario.maxRounds());
            node.put("selector", scenario.selector());
            var members = node.putArray("members");
            for (Scenario.Member member : scenario.members()) {
                var item = members.addObject();
                item.put("key", member.key());
                item.put("role", member.role());
                item.put("tools", member.hasTools());
                item.put("toolset", member.toolset());
                item.put("model", member.usesSummarizer() ? "summarizer" : "main");
            }
        }
        json(exchange, 200, array.toString());
    }

    /**
     * 本机目录浏览：页面上"选择工作目录"用。
     *
     * <p>浏览器拿不到本地文件系统的路径，只能由本地服务代劳。这个端点能列任意目录，
     * 所以：只在 {@code tool.code.allow-select} 开启时提供；只列目录不读文件内容；
     * 每个目录都先跑一遍 {@link CodeWorkspace#rejectReason(Path)}，让页面提前标出
     * "这个不能当工作区"，而不是用户选完才在 run 的时候报错。</p>
     */
    private void serveFsList(HttpExchange exchange) throws IOException {
        if (!appConfig.codeAllowSelect()) {
            json(exchange, 403, "{\"error\":\"已关闭目录选择（tool.code.allow-select=false）\"}");
            return;
        }
        Map<String, String> q = query(exchange);
        String raw = q.getOrDefault("path", "").trim();

        var node = MAPPER.createObjectNode();
        node.put("ok", true);

        if (raw.isEmpty()) {
            // 没给 path：只返回配置里允许的根，页面用它画快捷入口。
            // 早先这里会把「用户主目录」和 C–Z 所有盘符都列出来，等于给了一个全盘浏览器；
            // 既然 /api/run 已经按白名单收口，这里再放盘符就只是自欺欺人。
            var entries = MAPPER.createArrayNode();
            List<String> seen = new ArrayList<>();
            addDirEntry(entries, seen, appConfig.codeWorkspace().toAbsolutePath().normalize(), "当前配置的工作目录");
            for (Path wr : appConfig.codeWritableDirs()) {
                addDirEntry(entries, seen, wr, "额外可写目录");
            }
            for (Path ro : appConfig.codeReadOnlyDirs()) {
                addDirEntry(entries, seen, ro, "只读目录");
            }
            node.put("path", "");
            node.put("parent", "");
            node.set("entries", entries);
            node.put("hint", "只显示配置允许的目录；要加别的工程，把它写进 tool.code.writable-dirs"
                    + "（可写）或 tool.code.read-only-dirs（只读），逗号分隔");
            json(exchange, 200, MAPPER.writeValueAsString(node));
            return;
        }

        Path dir;
        try {
            dir = Path.of(raw).toAbsolutePath().normalize();
        } catch (Exception e) {
            json(exchange, 400, "{\"error\":\"路径不合法：" + escape(raw) + "\"}");
            return;
        }
        if (!Files.isDirectory(dir)) {
            json(exchange, 400, "{\"error\":\"不是目录或不存在：" + escape(dir.toString()) + "\"}");
            return;
        }
        // 只能浏览白名单内的目录，否则这个端点就是一台「全盘目录树」服务
        if (!CodeWorkspace.underAnyRoot(dir, appConfig.codeAllowedRoots())) {
            json(exchange, 403, "{\"error\":\"这个目录不在允许范围内。把工程目录加到 "
                    + "tool.code.writable-dirs（可写）或 tool.code.read-only-dirs（只读）后再试\"}");
            return;
        }

        List<Path> children = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isDirectory).forEach(children::add);
        }
        children.sort(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)));

        var entries = MAPPER.createArrayNode();
        List<String> seen = new ArrayList<>();
        int shown = 0;
        for (Path child : children) {
            if (shown >= FS_MAX_ENTRIES) {
                break;
            }
            addDirEntry(entries, seen, child, null);
            shown++;
        }

        String reason = CodeWorkspace.rejectReason(dir);
        node.put("path", dir.toString().replace('\\', '/'));
        node.put("parent", dir.getParent() == null ? "" : dir.getParent().toString().replace('\\', '/'));
        node.put("writable", reason == null);
        node.put("blockReason", reason == null ? "" : reason);
        node.put("total", children.size());
        node.put("truncated", children.size() > shown);
        node.set("entries", entries);
        json(exchange, 200, MAPPER.writeValueAsString(node));
    }

    /** 一条目录候选；带上 git / maven 标记，用户能一眼看出这是不是一个工程目录。 */
    private void addDirEntry(com.fasterxml.jackson.databind.node.ArrayNode entries,
                             List<String> seen, Path dir, String note) {
        String abs = dir.toAbsolutePath().normalize().toString().replace('\\', '/');
        if (!seen.add(abs)) {
            return;
        }
        var item = MAPPER.createObjectNode();
        String name = dir.getFileName() == null ? abs : dir.getFileName().toString();
        item.put("name", name);
        item.put("path", abs);
        if (note != null) {
            item.put("note", note);
        }
        if (Files.exists(dir.resolve("pom.xml"))) {
            item.put("maven", true);
        }
        if (Files.exists(dir.resolve(".git"))) {
            item.put("git", true);
        }
        String reason = CodeWorkspace.rejectReason(dir);
        if (reason == null && !CodeWorkspace.underAnyRoot(dir, appConfig.codeAllowedRoots())) {
            // 能看见 ≠ 能选：白名单之外的子目录允许继续下钻浏览，但不会被当成工作区
            reason = "不在允许范围内（把上级目录加到 tool.code.writable-dirs 后可选用）";
        }
        if (reason != null) {
            item.put("blockReason", reason);
        }
        entries.add(item);
    }

    /**
     * 开始一次新运行：立刻返回 sessionId，真正的执行放到线程池。
     *
     * <p>{@code scenario} 默认 {@code auto}——用户只提问，走哪套协作方式由 {@link Router} 判断。</p>
     *
     * <p>{@code conversationId} 是可选的：带上它就是"在同一段对话里追问"，上一轮的消息会被接上；
     * 不带则开一段新对话。返回值里带回 {@code conversationId}，页面据此把下一句接到同一段上下文。</p>
     */
    private void handleRun(HttpExchange exchange) throws IOException {
        Map<String, String> query = query(exchange);
        String requested = query.getOrDefault("scenario", Router.AUTO);
        String task = query.getOrDefault("task", "");
        String conversationId = query.getOrDefault("conversationId", "").trim();

        // 追问要求这段对话还能找到：内存里有就直接用，没有就去磁盘捞（重启 / 被淘汰），
        // 两处都没有才当成新对话，别让页面卡在一个死 id 上
        Conversation conversation = conversationId.isEmpty() ? null : findConversation(conversationId);
        boolean followUp = conversation != null;

        Scenario scenario;
        int maxRounds;
        if (Router.AUTO.equals(requested)) {
            if (task.isBlank()) {
                json(exchange, 400, "{\"error\":\"请先输入你的问题\"}");
                return;
            }
            // null 表示"到后台再判断"；轮次跟路由结果走，页面上的轮次设置在这条路径上不生效
            scenario = null;
            maxRounds = 0;
        } else {
            scenario = Scenario.byId(requested);
            maxRounds = intOrDefault(query.get("maxRounds"), scenario.maxRounds());
            if (task.isBlank()) {
                task = scenario.task();
            }
        }

        // 工作目录：页面上选的本地目录，没选就用配置里的默认沙箱
        Path picked;
        try {
            picked = resolveWorkspaceParam(query);
        } catch (IllegalArgumentException e) {
            json(exchange, 400, "{\"error\":\"" + escape(e.getMessage()) + "\"}");
            return;
        }

        // 并发闸门：拿不到名额立刻 429。放在建对话之前，免得"被拒的请求"也占一个 LRU 槽位
        if (!acquireRunSlot(exchange)) {
            return;
        }

        if (conversation == null) {
            conversation = newConversation(scenario == null ? null : scenario.id());
            conversations.put(conversation.id(), conversation);
        } else {
            conversation.touch();
        }

        RunSession session = newSession();
        try {
            json(exchange, 200, "{\"sessionId\":\"" + session.id()
                    + "\",\"conversationId\":\"" + conversation.id()
                    + "\",\"followUp\":" + followUp
                    + ",\"workspace\":\"" + escape(picked == null
                    ? appConfig.codeWorkspace().toAbsolutePath().normalize().toString().replace('\\', '/')
                    : picked.toString().replace('\\', '/')) + "\"}");
        } catch (IOException e) {
            // 响应没写出去 → launch 不会执行，名额必须在这里还回去
            runSlots.release();
            throw e;
        }
        launch(session, scenario, maxRounds, null, task, conversation, picked);
    }

    /**
     * 申请一个运行名额；拿不到就回 429 并说明当前占用。
     *
     * <p>回 429 而不是排队，是刻意的：一次任务 3–9 分钟，排队的人只会看到一个
     * 「转圈但什么都没发生」的页面，比明确说「前面还有 2 个」更糟。</p>
     */
    private boolean acquireRunSlot(HttpExchange exchange) throws IOException {
        if (runSlots.tryAcquire()) {
            return true;
        }
        int limit = Math.max(1, appConfig.maxConcurrentRuns());
        int busy = limit - runSlots.availablePermits();
        json(exchange, 429, "{\"error\":\"当前已有 " + busy + " 个任务在跑（上限 " + limit
                + " 个）。单次任务在纯 CPU 上要 3–9 分钟，请等其中一个结束后再试；"
                + "机器够强时把 agent.properties 的 web.max-concurrent-runs 调大。\",\"busy\":" + busy
                + ",\"limit\":" + limit + "}");
        return false;
    }

    /**
     * 解析页面上选的工作目录。
     *
     * <p>返回 {@code null} 表示"没选，用配置默认"。除了挡系统目录和路径穿越，还要落在
     * {@link AppConfig#codeAllowedRoots()} 白名单内——「用户在选择框里点了一下」不等于授权，
     * 一旦服务对外提供，任何访问者都能构造这个参数；白名单是唯一不依赖调用方自觉的边界。
     * 模型碰不到这个参数（它在 URL 里，由页面带上来），所以模型没法给自己扩权。</p>
     */
    private Path resolveWorkspaceParam(Map<String, String> query) {
        String raw = query.getOrDefault("workspace", "").trim();
        if (raw.isEmpty()) {
            return null;
        }
        if (!appConfig.codeAllowSelect()) {
            throw new IllegalArgumentException("已关闭目录选择（tool.code.allow-select=false），"
                    + "只能用服务端配置的工作目录");
        }
        Path picked;
        try {
            picked = Path.of(raw).toAbsolutePath().normalize();
        } catch (Exception e) {
            throw new IllegalArgumentException("工作目录路径不合法：" + raw);
        }
        String reason = CodeWorkspace.rejectReason(picked);
        if (reason != null) {
            throw new IllegalArgumentException(reason);
        }
        if (!CodeWorkspace.underAnyRoot(picked, appConfig.codeAllowedRoots())) {
            throw new IllegalArgumentException("这个目录不在允许范围内：" + picked
                    + "。把工程目录加到 tool.code.writable-dirs（可写）或 tool.code.read-only-dirs（只读）后重启服务。");
        }
        return picked;
    }

    /** 从快照续跑：新开一个会话，先把历史回放给页面，再接着生成。 */
    private void handleResume(HttpExchange exchange) throws IOException {
        Map<String, String> query = query(exchange);
        // 续跑必须落到具体场景：快照里有历史消息，换一套角色去接会让上下文对不上
        String requested = query.getOrDefault("scenario", "general");
        Scenario scenario = Router.AUTO.equals(requested)
                ? Scenario.byId("general")
                : Scenario.byId(requested);
        int maxRounds = intOrDefault(query.get("maxRounds"), scenario.maxRounds());
        String file = query.getOrDefault("file", "");

        Path snapshot = resolveSnapshot(file);
        if (snapshot == null) {
            json(exchange, 400, "{\"error\":\"快照不存在: " + escape(file) + "\"}");
            return;
        }

        // 续跑沿用页面当前选的工作目录，否则"从快照继续"会突然跑到默认沙箱里去
        Path picked = null;
        try {
            picked = resolveWorkspaceParam(query);
        } catch (IllegalArgumentException ignored) {
            // 续跑不因为目录参数有问题就失败：退回配置默认，比让用户点不动强
        }

        if (!acquireRunSlot(exchange)) {
            return;
        }

        // 续跑也开一段对话：跑完之后的追问要能接上，否则页面得让用户再选一次场景
        Conversation conversation = newConversation(scenario.id());
        conversations.put(conversation.id(), conversation);

        RunSession session = newSession();
        try {
            json(exchange, 200, "{\"sessionId\":\"" + session.id()
                    + "\",\"conversationId\":\"" + conversation.id()
                    + "\",\"followUp\":true}");
        } catch (IOException e) {
            runSlots.release();
            throw e;
        }
        launch(session, scenario, maxRounds, snapshot, null, conversation, picked);
    }

    private void handleStop(HttpExchange exchange) throws IOException {
        Map<String, String> query = query(exchange);
        RunSession session = sessions.get(query.getOrDefault("sessionId", ""));
        if (session == null) {
            json(exchange, 404, "{\"error\":\"session 不存在\"}");
            return;
        }
        session.requestStop();
        json(exchange, 200, "{\"ok\":true}");
    }

    private void serveSnapshots(HttpExchange exchange) throws IOException {
        var array = MAPPER.createArrayNode();
        for (TeamSnapshot.Summary summary : TeamSnapshot.list(snapshotDir)) {
            var node = array.addObject();
            node.put("file", summary.file());
            node.put("team", summary.team());
            node.put("round", summary.round());
            node.put("messageCount", summary.messageCount());
            node.put("stopReason", summary.stopReason());
            node.put("savedAt", summary.savedAt());
        }
        json(exchange, 200, array.toString());
    }

    /**
     * 会话查询：不带 id 列最近若干段，带了 id 回整段消息。
     *
     * <p>页面刷新之后要靠它把上次的对话重新画出来。没有这个出口，"会话落库"只是把数据
     * 存在一个没人读得到的地方——用户能感知到的恢复入口只有两个：刷新页面，
     * 或者再问一句。前者靠这个接口，后者靠 {@code /api/run} 里那次 {@code load}。</p>
     *
     * <p>列表只回元信息，不回消息正文：几十段对话的正文加起来能到几 MB，
     * 而列表上只需要"哪段、聊了几轮、什么时候"。正文在点开那一段时再取。</p>
     */
    private void serveConversations(HttpExchange exchange) throws IOException {
        Map<String, String> q = query(exchange);
        String id = q.getOrDefault("conversationId", "").trim();
        if (id.isEmpty()) {
            var array = MAPPER.createArrayNode();
            for (Conversation conversation : conversationStore.recent(
                    Math.max(4, appConfig.maxConversations()))) {
                array.add(conversationNode(conversation, false));
            }
            json(exchange, 200, array.toString());
            return;
        }
        Conversation conversation = findConversation(id);
        if (conversation == null) {
            // 404 而不是 200 空对象：页面据此判断"这段对话没了"，直接开新对话
            json(exchange, 404, "{\"error\":\"对话不存在或已过期\"}");
            return;
        }
        json(exchange, 200, conversationNode(conversation, true).toString());
    }

    private com.fasterxml.jackson.databind.node.ObjectNode conversationNode(Conversation conversation,
                                                                           boolean withMessages) {
        var node = MAPPER.createObjectNode();
        node.put("conversationId", conversation.id());
        node.put("scenario", conversation.scenarioId() == null ? "" : conversation.scenarioId());
        node.put("turns", conversation.turns());
        node.put("messages", conversation.messageCount());
        node.put("lastActiveAt", conversation.lastActiveAt());
        node.put("cached", conversations.containsKey(conversation.id()));
        if (!withMessages) {
            return node;
        }
        var history = node.putArray("history");
        SessionState state = conversation.state();
        if (state != null) {
            for (var message : state.messages()) {
                var item = history.addObject();
                item.put("source", message.source());
                item.put("text", message.text());
            }
        }
        return node;
    }

    /** SSE：轮询会话队列，把 JSON 一条条写出去；空闲时发注释行保活。 */
    private void handleStream(HttpExchange exchange) throws IOException {
        Map<String, String> query = query(exchange);
        String sessionId = query.getOrDefault("sessionId", "");

        exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().add("Cache-Control", "no-cache");
        exchange.getResponseHeaders().add("Connection", "keep-alive");
        exchange.getResponseHeaders().add("X-Accel-Buffering", "no");
        exchange.sendResponseHeaders(200, 0);
        metrics.sseStream();

        try (OutputStream out = exchange.getResponseBody()) {
            // 断线重连间隔。浏览器默认 3 秒，写出来是为了少一次"为什么等这么久"的疑问
            write(out, "retry: 3000\n\n");

            RunSession session = sessions.get(sessionId);
            if (session == null) {
                // 会话已回收（服务重启、或这场运行结束太久）。这里不返回 404：EventSource 收到
                // 非 200 只会抛一个无信息的 error，页面只能笼统说"连接断开"。用一条正常事件
                // 说明原因，用户至少知道该重新发起，而不是反复刷新等它自己好。
                write(out, "data: " + expiredPayload() + "\n\n");
                return;
            }

            long cursor = readCursor(exchange, query);

            // 断太久、要补的事件已经被窗口挤掉：补不了就直说，让页面重拉全量。
            // 静默少一段的后果是页面停在半截对话上，看起来像模型答到一半没了。
            if (cursor > 0 && session.hasGap(cursor)) {
                long latest = session.latestSeq();
                write(out, "id: " + latest + "\n"
                        + "data: {\"kind\":\"resync\",\"seq\":" + latest
                        + ",\"message\":\"断线期间的事件已滚出保留窗口，请重新拉取完整对话\"}\n\n");
                cursor = latest;
                metrics.sseResume();
            } else if (cursor > 0) {
                metrics.sseResume();
            }

            while (true) {
                RunSession.Entry entry = session.pollAfter(cursor, 15_000);
                if (entry == null) {
                    write(out, ": keep-alive\n\n");
                    continue;
                }
                write(out, "id: " + entry.seq() + "\ndata: " + entry.payload() + "\n\n");
                cursor = entry.seq();
                if (entry.terminal()) {
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // 浏览器关了页面，正常断连
        }
    }

    /**
     * 客户端已经收到第几条事件。
     *
     * <p>优先看 {@code Last-Event-ID}：这是浏览器在自动重连时按 SSE 规范带上的请求头，
     * 前端什么都不用做。{@code ?lastEventId=} 是给命令行和 curl 复现用的——
     * 排查"补发到底对不对"时，很难让 curl 触发一次浏览器重连。</p>
     */
    private static long readCursor(HttpExchange exchange, Map<String, String> query) {
        String raw = exchange.getRequestHeaders().getFirst("Last-Event-ID");
        if (raw == null || raw.isBlank()) {
            raw = query.get("lastEventId");
        }
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Long.parseLong(raw.trim()));
        } catch (NumberFormatException e) {
            // 客户端给了个非数字：当成从头开始，比 500 好
            return 0;
        }
    }

    private static String expiredPayload() {
        return "{\"kind\":\"expired\",\"message\":\"这次运行的连接已失效"
                + "（服务可能重启过，或这轮已结束太久）。重新发起即可。\"}";
    }

    /** 写一行 SSE 并立刻 flush——增量就是这样一帧帧出去的，攒着就等于没有流式。 */
    private static void write(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    // ---------- 运行 ----------

    private RunSession newSession() {
        pruneSessions();
        String id = UUID.randomUUID().toString().substring(0, 8);
        RunSession session = new RunSession(id);
        sessions.put(id, session);
        return session;
    }

    /**
     * 按 id 找一段对话：先内存，再磁盘。
     *
     * <p>磁盘这一跳就是"重启之后还能接着聊"的全部实现——不需要在启动时把一切都读回来，
     * 用户点了哪段才读哪段。读到了放回内存缓存，同一段对话的下一轮就不用再读盘。</p>
     */
    private Conversation findConversation(String id) {
        Conversation cached = conversations.get(id);
        if (cached != null) {
            return cached;
        }
        Conversation loaded = conversationStore.load(id);
        if (loaded != null) {
            conversations.put(loaded.id(), loaded);
        }
        return loaded;
    }

    /**
     * 开一段新对话。
     *
     * <p>id 是 UUID 前 8 位，撞上已有会话的概率极低，但"极低"不等于不会——真撞了，
     * 落盘时会覆盖别人那段对话。这里用一次文件存在性检查把它变成不可能，
     * 代价是每开一段新对话多一次 stat。</p>
     */
    private Conversation newConversation(String scenarioId) {
        for (int attempt = 0; attempt < 5; attempt++) {
            Conversation conversation = Conversation.start(scenarioId);
            if (conversationStore.load(conversation.id()) == null) {
                return conversation;
            }
        }
        // 连续 5 次撞号，正常情况不可能发生；交给上层用最新那个 id 继续
        return Conversation.start(scenarioId);
    }

    /** 一轮跑完后落盘；失败只记一行日志，不能让"已经跑完的任务"变成一次报错。 */
    private void persist(Conversation conversation) {
        try {
            conversationStore.save(conversation);
        } catch (IOException | RuntimeException e) {
            System.out.println("[会话] 保存 " + conversation.id() + " 失败：" + e.getMessage()
                    + "（本轮结果仍然可用，但重启后接不上这次对话）");
        }
    }

    /**
     * 清掉"早已结束且没人再看"的运行。
     *
     * <p>之前这里只 {@code put} 从不 {@code remove}：会话对象 + 它队列里攒下的所有事件
     * 会一直留在内存里，服务开一天就攒一天。SSE 断开之后这些对象再没有人读，
     * 唯一的用途是让 {@code /api/stop} 能回一句 404 而不是 "session 不存在"——
     * 保留半小时足够覆盖这种误操作。</p>
     */
    private void pruneSessions() {
        if (sessions.size() < SESSION_PRUNE_THRESHOLD) {
            return;
        }
        long cutoff = System.currentTimeMillis() - SESSION_IDLE_MILLIS;
        sessions.entrySet().removeIf(entry -> entry.getValue().isTerminal()
                && entry.getValue().lastActivityAt() < cutoff);
    }

    /**
     * @param requested 指定场景；{@code null} 表示自动路由——判断放在线程里做，
     *                  这样 {@code /api/run} 能立刻返回 sessionId，不让浏览器干等
     */
    private void launch(RunSession session, Scenario requested,
                        int maxRounds, Path resumeFrom, String task, Conversation conversation,
                        Path pickedWorkspace) {
        // traceId 在这里取：lambda 会跑到线程池的另一个线程上，那时 ThreadLocal 已经没了
        String requestId = RequestLog.currentId();
        RunLog log = new RunLog(session.id(), runLogFile, requestId);
        metrics.runStarted();
        pool.submit(() -> {
            long startedAt = System.currentTimeMillis();
            try {
                Scenario scenario = requested;
                if (scenario == null) {
                    session.status("running", "判断该用哪种方式回答");
                    // 追问时不能重新 decide：一句话常常什么域词都不命中（"便宜点的呢"），
                    // 重判会掉进 general，上一轮的工具和角色全丢
                    Router.Route route = conversation.hasHistory() && conversation.scenarioId() != null
                            ? Router.decideFollowUp(conversation.scenarioId(), task)
                            : Router.decide(task);
                    scenario = route.scenario();
                    // 先告诉页面"我选了哪条路、为什么"，再开始跑：用户看到的第一个事件
                    // 就该是这次派活的依据，而不是模型已经开始说话了才补一句解释
                    session.route(scenario.id(), scenario.title(), route.reason());
                    conversation.useScenario(scenario.id());
                    log.note("路由 → " + scenario.id() + "：" + route.reason());
                } else {
                    // 手动指定也要记一笔：页面据此确认自己选的和实际跑的是同一套
                    conversation.useScenario(scenario.id());
                    session.route(scenario.id(), scenario.title(), "手动指定");
                }
                int rounds = maxRounds > 0 ? maxRounds : scenario.maxRounds();
                String actual = task == null || task.isBlank() ? scenario.task() : task;

                session.status("running", "连接配置的模型");
                ModelConfig config;
                try {
                    config = modelSource.resolve();
                } catch (ModelUnavailable e) {
                    String reason = e.getMessage() + "。请改 agent.properties 的 model.* 配置后重启服务。";
                    // 先记账再宣布：error 事件一推出去，客户端随时可能来抓 /api/metrics
                    log.failed(RunFailure.MODEL_UNAVAILABLE.code(), reason);
                    metrics.runFailed(RunFailure.MODEL_UNAVAILABLE.code(),
                            System.currentTimeMillis() - startedAt);
                    session.error(RunFailure.MODEL_UNAVAILABLE.code(), reason, requestId);
                    return;
                }
                log.start(scenario.id(), config.provider(), config.model(), rounds, actual);
                if (resumeFrom != null) {
                    log.note("续跑：从 " + resumeFrom.getFileName() + " 恢复");
                }

                boolean twoStage = scenario.members().stream().anyMatch(Scenario.Member::usesSummarizer);
                if (twoStage) {
                    // 总结模型在第 2 轮才用到，如果等它自己失败，第一轮的查数据已经白烧了。
                    ModelConfig summarizer = appConfig.summarizerModel();
                    ModelSource.Readiness sr = ModelSource.Readiness.of(summarizer);
                    if (!sr.ready()) {
                        String reason = "总结模型不可用：" + sr.detail()
                                + "。改 agent.properties 的 model.summarizer.* 后重启服务。";
                        log.failed(RunFailure.MODEL_UNAVAILABLE.code(), reason);
                        metrics.runFailed(RunFailure.MODEL_UNAVAILABLE.code(),
                                System.currentTimeMillis() - startedAt);
                        session.error(RunFailure.MODEL_UNAVAILABLE.code(), reason, requestId);
                        return;
                    }
                    log.note("分工：收集 " + config.model() + " → 总结 " + summarizer.model()
                            + "（" + summarizer.provider() + "）");
                }

                AgentRuntime runtime = new AgentRuntime();
                // 事件同时走两条路：推给页面，落一行服务端日志
                runtime.onEvent(event -> {
                    if (event instanceof AgentEvent.ToolCallRequested) {
                        metrics.toolCall();
                    } else if (event instanceof AgentEvent.Retrying) {
                        metrics.modelRetry();
                    }
                    log.event(event);
                    session.event(event);
                });
                Team team = buildTeam(runtime, scenario, config, rounds, session, pickedWorkspace);

                TaskResult result;
                if (resumeFrom != null) {
                    SessionState state = TeamSnapshot.read(resumeFrom);
                    session.history(state.messages(), state.round(),
                            "从快照恢复 · 轮次 " + state.round() + " · " + state.messageCount() + " 条消息");
                    result = team.resume(state);
                } else if (conversation.hasHistory()) {
                    // 第二轮：把上一轮的消息接上，再注入这一句新问题。
                    // 轮次在 carryOver() 里已经归零，否则 maxRounds 这份累计预算会让本轮一上来就结束。
                    SessionState carried = conversation.carryOver();
                    session.history(carried.messages(), 0,
                            "接着这段对话 · 已有 " + carried.messages().size() + " 条消息", true);
                    session.message("user", actual);
                    result = team.resume(carried, actual);
                    log.note("追问 · 带上 " + carried.messages().size() + " 条历史");
                } else {
                    session.message("user", actual);
                    result = team.run(actual);
                }

                Path snapshot = TeamSnapshot.write(snapshotDir,
                        team.getClass().getSimpleName(), team.snapshot(result));
                // 存回对话，供下一轮接着用；裁剪与轮次归零在 Conversation 里做
                conversation.remember(team.snapshot(result));
                // 落盘：写完才发 done，页面看到"完成"时磁盘上已经有了，刷新/重启都接得上
                persist(conversation);
                session.done(result, snapshot.getFileName().toString());
                log.done(result, session.deltaCount(), session.toolCallCount());
                metrics.runFinished(result.stopReason(), System.currentTimeMillis() - startedAt);
            } catch (Exception e) {
                // 分类在这里做：异常已经飞出来了，再晚就没有机会判断"用户下一步该做什么"
                RunFailure failure = RunFailure.classify(e);
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                log.error(failure.code(), e);
                metrics.runFailed(failure.code(), System.currentTimeMillis() - startedAt);
                session.error(failure.code(), message, requestId);
            } finally {
                // 名额必须还：漏还一次，并发上限就永久少一个，跑久了服务会变成"永不接单"
                runSlots.release();
            }
        });
    }


    private Team buildTeam(AgentRuntime runtime, Scenario scenario, ModelConfig config,
                           int maxRounds, RunSession session, Path pickedWorkspace) {
        ModelConfig summarizer = appConfig.summarizerModel();
        List<AgentId> members = new ArrayList<>();
        for (Scenario.Member member : scenario.members()) {
            // 分工场景里同一个团队可以有两套模型：收集侧用主模型（工具调用准、快），
            // 总结侧用第二套（不需要工具，只需要照素材组织语言）。
            ModelConfig memberConfig = member.usesSummarizer() ? summarizer : config;
            ChatAgent.Builder builder = ChatAgent
                    .streamingBuilder(member.key(), memberConfig.newStreamingChatModel())
                    .systemMessage(member.system())
                    .retryPolicy(appConfig.retryPolicy());
            if (member.hasTools()) {
                switch (member.toolset()) {
                    case "travel" -> {
                        builder.tools(TravelTools.all());
                        // 一个目的地要挨个查景点坐标/路线，默认 5 次不够用
                        builder.maxToolIterations(12);
                    }
                    case "calculator" -> builder.tools(new CalculatorTool());
                    case "coding" -> {
                        // 工作目录 = 用户在页面上选的本地目录（没选就用配置里的默认沙箱）。
                        // 必须走 forUserSelection：只读目录、额外可写目录都在这个实例里，
                        // 直接 new CodeWorkspace(root) 会让这两项配置静默失效。
                        CodeWorkspace workspace = CodeWorkspace.forUserSelection(pickedWorkspace);
                        // 提示词里那句"工作目录是 X"必须跟着页面上的选择走，
                        // 否则用户选了 abrspro2、模型却以为自己该在默认沙箱里干活
                        builder.systemMessage(com.example.agentcore.tool.code.CodePrompt.system(
                                workspace, com.example.agentcore.tool.code.Platform.current()));
                        session.status("running", "工作目录：" + workspace.describe());
                        builder.tools(com.example.agentcore.tool.code.CodeTools.all(
                                workspace,
                                appConfig.codeCommandTimeoutSeconds()));
                        // 真实的写码闭环是"写→编译→报错→改→再编译"，5 次连一轮修复都不够。
                        // 改存量工程时读文件占大头（一个 4000 行的 Service 要读好几段），
                        // 30 次实测卡在"还没开始写配额就没了"，放到 40。
                        builder.maxToolIterations(40);
                    }
                    default -> throw new IllegalStateException("未知工具集: " + member.toolset());
                }
            } else if (pickedWorkspace == null) {
                // 场景本身不挂工具，用户也没选目录：把话说准。
                // "我没有文件系统权限"是假的，会让用户以为这条路走不通；
                // "你还没指定目录"能直接指到左栏那个按钮。
                builder.systemMessage(member.system() + "\n\n"
                        + com.example.agentcore.tool.code.CodePrompt.noWorkspaceHint());
            } else {
                // 场景本身不挂工具（需求评审 / 方案打磨 / 模型选人 / 直接回答），但用户选了目录：
                // 给它一副"只读的眼睛"。起因是一句实测回答——用户问"分析一下这个项目"，
                // 这句话没有任何技术域词，路由必然落到兜底场景，而兜底场景一个工具都没有，
                // 于是模型答"我这次没有文件系统和工具权限，读不到"，用户明明已经选好了目录。
                //
                // 只挂 4 个读方法，写文件/改文件/跑命令不进 schema：挂上去再拒绝没用，
                // 模型看见写工具就会去写，而这类场景的定位就是"看和说"，不是"改"。
                CodeWorkspace workspace = CodeWorkspace.forUserSelection(pickedWorkspace);
                session.status("running", "工作目录（只读）：" + workspace.describe());
                builder.systemMessage(member.system() + "\n\n"
                        + com.example.agentcore.tool.code.CodePrompt.readOnlyHint(
                                workspace, com.example.agentcore.tool.code.Platform.current()));
                builder.toolRegistry(com.example.agentcore.tool.code.CodeTools.readOnly(workspace));
                // 看一个真实项目要读十几个文件：给 5 次等于没给，但这类场景不该像写代码那样给到 40
                builder.maxToolIterations(20);
            }
            members.add(runtime.register(member.role(), member.key(), builder.build()));
        }

        Team team = scenario.selector()
                ? new SelectorTeam(runtime, members, config.newChatModel(0.0))
                : new RoundRobinTeam(runtime, members);

        return team.onStop(StopCondition.textMention(scenario.stopWord())
                        .or(StopCondition.external(session.stopRequested()))
                        .or(StopCondition.timeout(15 * 60_000L)))
                .maxRounds(maxRounds);
    }

    // ---------- 工具方法 ----------

    /** 只允许访问快照目录内的文件，挡住 ../ 之类的路径穿越。 */
    private Path resolveSnapshot(String file) {
        if (file == null || file.isBlank()) {
            return null;
        }
        Path candidate = snapshotDir.resolve(file).normalize();
        if (!candidate.startsWith(snapshotDir) || !Files.isRegularFile(candidate)) {
            return null;
        }
        return candidate;
    }

    private static Map<String, String> query(HttpExchange exchange) {
        Map<String, String> result = new HashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        for (String pair : raw.split("&")) {
            int at = pair.indexOf('=');
            if (at <= 0) {
                continue;
            }
            String key = URLDecoder.decode(pair.substring(0, at), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.substring(at + 1), StandardCharsets.UTF_8);
            result.put(key, value);
        }
        return result;
    }

    private static int intOrDefault(String raw, int fallback) {
        try {
            return raw == null ? fallback : Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void json(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
        // 指标与访问日志挂在唯一的 JSON 出口上：新增接口不用记得补计数
        metrics.http(status);
        RequestLog.finish(exchange, status, accessLog);
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
