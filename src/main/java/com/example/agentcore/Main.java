package com.example.agentcore;

import com.example.agentcore.agent.ChatAgent;
import com.example.agentcore.agent.EchoAgent;
import com.example.agentcore.messaging.AgentEvent;
import com.example.agentcore.messaging.ChatMsg;
import com.example.agentcore.messaging.Topic;
import com.example.agentcore.runtime.AgentId;
import com.example.agentcore.runtime.AgentRuntime;
import com.example.agentcore.runtime.SessionState;
import com.example.agentcore.support.AppConfig;
import com.example.agentcore.support.MockChatServer;
import com.example.agentcore.support.ModelConfig;
import com.example.agentcore.support.OllamaProbe;
import com.example.agentcore.support.TeamSnapshot;
import com.example.agentcore.team.RoundRobinTeam;
import com.example.agentcore.team.SelectorTeam;
import com.example.agentcore.team.TaskResult;
import com.example.agentcore.team.Team;
import com.example.agentcore.termination.StopCondition;
import com.example.agentcore.tool.CalculatorTool;
import com.example.agentcore.tool.travel.AttractionsTool;
import com.example.agentcore.tool.travel.LocationTool;
import com.example.agentcore.tool.travel.MapClient;
import com.example.agentcore.tool.travel.MapClients;
import com.example.agentcore.tool.travel.NearbyTool;
import com.example.agentcore.tool.travel.RouteTool;
import com.example.agentcore.tool.travel.StaticMapTool;
import com.example.agentcore.tool.travel.TravelNotebookTool;
import com.example.agentcore.tool.travel.TravelPrompt;
import com.example.agentcore.tool.travel.TravelTools;
import com.example.agentcore.tool.travel.WebSearchTool;
import com.example.agentcore.web.AgentWebServer;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

/**
 * 应用入口。
 *
 * <p>不带参数启动就是可视化页面，端口与模型全部来自 {@code agent.properties}
 * （配置搜索顺序见 {@link AppConfig}）：</p>
 *
 * <pre>
 *   mvn -q compile exec:java            启动控制台，浏览器打开 http://127.0.0.1:8099
 *   java -jar target/autogen-core-java-1.0.0.jar   同上，不需要 Maven
 *
 *   mvn -q exec:java -Dexec.args="console"    不开页面，用配置的模型跑一次群聊（终端打字机）
 *   mvn -q exec:java -Dexec.args="travel"     用配置的模型跑一次旅游规划（挂 7 个旅游工具）
 *   mvn -q exec:java -Dexec.args="tools"      旅游工具自检：绕开模型，逐个真调并打印结果
 *   mvn -q exec:java -Dexec.args="mock"       离线 Mock：轮询团队 + 工具调用 + 发布订阅
 *   mvn -q exec:java -Dexec.args="stream"     离线 Mock：流式输出
 *   mvn -q exec:java -Dexec.args="resume"     离线 Mock：快照落盘 + 断点续跑
 *   mvn -q exec:java -Dexec.args="selector"   离线 Mock：模型选发言人
 *   mvn -q exec:java -Dexec.args="web 9000"   显式指定端口（临时覆盖配置）
 * </pre>
 *
 * <p>这些子命令只用于演示具体机制；日常使用时不需要参数。</p>
 */
public final class Main {

    private static final String PM_SYSTEM = "你是产品经理，负责把需求拆成可执行任务，输出简洁的条目。";
    private static final String ENGINEER_SYSTEM = "你是后端工程师，给出实现方案；涉及估算时调用工具。";
    private static final String REVIEWER_SYSTEM = "你是评审人，指出方案的缺口；当方案可接受时回复 APPROVE。";

    private static final Object PRINT_LOCK = new Object();

    public static void main(String[] args) throws Exception {
        try {
            dispatch(args);
        } catch (IllegalArgumentException | IllegalStateException e) {
            // 配置写错时给一句人话，不要甩栈；栈里看不出哪个 key 写错了
            System.err.println("配置错误: " + e.getMessage());
            System.err.println("检查 agent.properties，或用 -Dagent.config=<path> 指定配置文件。");
            System.exit(1);
        }
    }

    private static void dispatch(String[] args) throws Exception {
        // 不带参数 = 起服务，这是默认形态；子命令只给机制演示用
        String mode = args.length > 0 ? args[0].toLowerCase() : "web";
        switch (mode) {
            case "web", "serve" -> runWeb(args);
            case "console" -> runConsole();
            case "ollama", "ollama-stream", "deepseek" -> {
                // 老命令名仍可用，但模型来源统一走配置，不再靠命令名切换
                System.out.println("提示：模型现在由 agent.properties 的 model.provider 决定，"
                        + "\"console\" 会使用当前配置。\n");
                runConsole();
            }
            case "mock" -> runMockRoundRobin();
            case "stream" -> runMockStreaming();
            case "resume" -> runMockResume();
            case "selector" -> runMockSelector();
            case "travel" -> runTravel(args);
            case "tools", "toolcheck" -> runToolCheck();
            default -> printUsage(mode);
        }
    }

    private static void printUsage(String unknown) {
        if (unknown != null) {
            System.out.println("未知模式: " + unknown + "\n");
        }
        System.out.println("""
                用法：
                  （无参数）或 web [端口]   启动可视化控制台，配置见 agent.properties
                  console                 用配置的模型跑一次群聊，输出到终端
                  travel [任务]           用配置的模型跑一次旅游规划（挂全部旅游工具）
                  tools                   旅游工具自检，绕开模型逐个真调
                  mock | stream | resume | selector
                                          离线 Mock 演示，不需要任何模型
                模型与端口在 agent.properties 中配置；临时覆盖用 -Dmodel.name=xxx""");
    }

    /** 启动控制台：端口、快照目录、模型全部来自配置文件。 */
    private static void runWeb(String[] args) throws Exception {
        AppConfig app = AppConfig.get();
        // 命令行端口仅作临时覆盖，常规用法不带参数
        int port = args.length > 1 ? Integer.parseInt(args[1]) : app.port();
        Path snapshotDir = app.snapshotDir();
        ModelConfig model = app.model();

        AgentWebServer web = AgentWebServer.start(port, snapshotDir);
        Runtime.getRuntime().addShutdownHook(new Thread(web::close));

        System.out.println("=== 可视化控制台已启动 ===");
        System.out.println("地址  : " + web.url());
        System.out.println("模型  : " + model.describe());
        System.out.println("快照  : " + snapshotDir.toAbsolutePath());
        System.out.println("配置  : " + app.source());
        System.out.println("模型不可用时页面会直接给出原因；Ctrl+C 结束。");
        web.awaitShutdown();
    }

    /** 没有页面的场景：直接用配置的模型跑一次群聊，终端里看打字机与工具调用。 */
    private static void runConsole() {
        ModelConfig config = AppConfig.get().model();
        if (!ensureModelReady(config)) {
            return;
        }
        System.out.println("=== 群聊（" + config.describe() + "）===");
        System.out.println("温度 " + config.temperature() + " · 超时 " + config.timeout().toSeconds() + "s");

        AgentRuntime runtime = new AgentRuntime();
        attachTypewriter(runtime);
        List<AgentId> members = List.of(
                runtime.register("pm", "planner", ChatAgent.streamingBuilder(
                                "planner", config.newStreamingChatModel()).systemMessage(PM_SYSTEM).build()),
                runtime.register("engineer", "coder", ChatAgent.streamingBuilder(
                                "coder", config.newStreamingChatModel()).systemMessage(ENGINEER_SYSTEM)
                        .tools(new CalculatorTool()).build()),
                runtime.register("reviewer", "reviewer", ChatAgent.streamingBuilder(
                                "reviewer", config.newStreamingChatModel()).systemMessage(REVIEWER_SYSTEM).build()));

        Team team = new RoundRobinTeam(runtime, members)
                .onStop(StopCondition.textMention("APPROVE").or(StopCondition.maxMessages(8)))
                .maxRounds(4);
        summary(team.run("帮我做一个订单导出功能，要求支持百万级数据。"));
    }

    private static void runMockRoundRobin() throws Exception {
        try (MockChatServer mock = MockChatServer.start()) {
            mock.enqueueText("需求拆解：1) 订单导出接口 2) 权限校验 3) 异步任务与进度查询。");
            mock.enqueueToolCall("estimateComplexity", "{\"requirement\":\"订单导出接口，含权限校验与异步任务\"}");
            mock.enqueueText("复杂度评分 1（评估工具给的）。方案：导出走异步任务表，权限在网关层统一校验，进度用轮询接口。");
            mock.enqueueText("方案可行，但缺少幂等设计。APPROVE");

            ModelConfig config = ModelConfig.forMock(mock.baseUrl());
            AgentRuntime runtime = new AgentRuntime();
            List<AgentId> members = List.of(
                    runtime.register("pm", "planner", ChatAgent.builder("planner", config.newChatModel())
                            .systemMessage(PM_SYSTEM).build()),
                    runtime.register("engineer", "coder", ChatAgent.builder("coder", config.newChatModel())
                            .systemMessage(ENGINEER_SYSTEM).tools(new CalculatorTool()).build()),
                    runtime.register("reviewer", "reviewer", ChatAgent.builder("reviewer", config.newChatModel())
                            .systemMessage(REVIEWER_SYSTEM).build()));

            Team team = new RoundRobinTeam(runtime, members)
                    .onStop(StopCondition.textMention("APPROVE").or(StopCondition.maxMessages(8)))
                    .maxRounds(6);

            System.out.println("=== 轮询团队（离线 Mock）===");
            TaskResult result = team.run("帮我做一个订单导出功能，要求支持百万级数据。");
            report(result, members.size());
            System.out.println("Mock 收到的请求数: " + mock.requestCount());
            demoPubSub();
            Path file = TeamSnapshot.write(Path.of("target", "snapshots"), "RoundRobinTeam",
                    team.snapshot(result));
            System.out.println("会话快照已写入: " + file.toAbsolutePath());
        }
    }

    /** 流式演示：增量事件实时打印，跑完再给摘要。 */
    private static void runMockStreaming() throws Exception {
        try (MockChatServer mock = MockChatServer.start()) {
            mock.enqueueText("需求拆解：1) 导出接口 2) 权限校验 3) 异步任务与进度查询。");
            mock.enqueueToolCall("estimateComplexity", "{\"requirement\":\"订单导出接口\"}");
            mock.enqueueText("复杂度 5，建议拆成异步任务 + 分页游标。");
            mock.enqueueText("缺幂等设计，其余可行。APPROVE");

            ModelConfig config = ModelConfig.forMock(mock.baseUrl());
            AgentRuntime runtime = new AgentRuntime();
            attachTypewriter(runtime);

            List<AgentId> members = List.of(
                    runtime.register("pm", "planner", ChatAgent.streamingBuilder(
                                    "planner", config.newStreamingChatModel()).systemMessage(PM_SYSTEM).build()),
                    runtime.register("engineer", "coder", ChatAgent.streamingBuilder(
                                    "coder", config.newStreamingChatModel()).systemMessage(ENGINEER_SYSTEM)
                            .tools(new CalculatorTool()).build()),
                    runtime.register("reviewer", "reviewer", ChatAgent.streamingBuilder(
                                    "reviewer", config.newStreamingChatModel()).systemMessage(REVIEWER_SYSTEM).build()));

            Team team = new RoundRobinTeam(runtime, members)
                    .onStop(StopCondition.textMention("APPROVE"))
                    .maxRounds(4);

            System.out.println("=== 流式输出（离线 Mock，边生成边打印）===");
            TaskResult result = team.run("帮我做一个订单导出功能，要求支持百万级数据。");
            summary(result);
        }
    }

    /** 续跑演示：第一段跑满轮次并落盘，第二段用全新运行时从快照继续。 */
    private static void runMockResume() throws Exception {
        try (MockChatServer mock = MockChatServer.start()) {
            mock.enqueueText("初版方案：同步导出，一次性读全表。");
            mock.enqueueText("问题：百万级会 OOM，缺少异步任务。");

            AgentRuntime first = new AgentRuntime();
            Team firstTeam = new RoundRobinTeam(first, mockMembers(first, mock))
                    .onStop(StopCondition.textMention("APPROVE"))
                    .maxRounds(2);

            System.out.println("=== 第一段：跑满 2 轮后存档 ===");
            TaskResult part1 = firstTeam.run("做一个订单导出功能");
            transcript(part1);
            System.out.println("停止原因: " + part1.stopReason() + "（累计轮次 " + part1.round() + "）");

            Path file = TeamSnapshot.write(Path.of("target", "snapshots"), "RoundRobinTeam",
                    firstTeam.snapshot(part1));
            System.out.println("快照已写入: " + file.toAbsolutePath());

            mock.enqueueText("修订版：改异步任务表 + 游标分页 + 幂等键。APPROVE");
            mock.enqueueText("可以上线。");

            AgentRuntime second = new AgentRuntime();
            Team secondTeam = new RoundRobinTeam(second, mockMembers(second, mock))
                    .onStop(StopCondition.textMention("APPROVE"))
                    .maxRounds(4);

            System.out.println("\n=== 第二段：全新运行时，从快照恢复续跑 ===");
            SessionState state = TeamSnapshot.read(file);
            System.out.println("恢复状态：消息 " + state.messageCount() + " 条，事件 "
                    + state.eventCount() + " 条，轮次 " + state.round()
                    + "，上次停止原因：" + state.lastStopReason());

            TaskResult part2 = secondTeam.resume(state);
            transcript(part2);
            System.out.println("停止原因: " + part2.stopReason() + "（累计轮次 " + part2.round() + "）");
            System.out.println("消息数 " + part1.messages().size() + " → " + part2.messages().size()
                    + "（续跑是追加而非重来）");
        }
    }

    private static void runMockSelector() throws Exception {
        try (MockChatServer mock = MockChatServer.start()) {
            mock.enqueueSelector("reviewer");
            mock.enqueueText("评审通过。APPROVE");

            ModelConfig config = ModelConfig.forMock(mock.baseUrl());
            AgentRuntime runtime = new AgentRuntime();
            List<AgentId> members = List.of(
                    runtime.register("pm", "planner", ChatAgent.builder("planner", config.newChatModel())
                            .systemMessage(PM_SYSTEM).build()),
                    runtime.register("reviewer", "reviewer", ChatAgent.builder("reviewer", config.newChatModel())
                            .systemMessage(REVIEWER_SYSTEM).build()));

            Team team = new SelectorTeam(runtime, members, config.newChatModel(0.0))
                    .onStop(StopCondition.textMention("APPROVE"))
                    .maxRounds(4);

            System.out.println("=== 模型选发言人（离线 Mock）===");
            report(team.run("这个方案能直接上线吗？"), members.size());
        }
    }

    /**
     * 真实旅游规划：单 agent + 全部旅游工具，走配置里的模型。
     *
     * <p>为什么是单 agent：旅游规划是一条"查资料 → 排行程"的线性链路，用团队反而让每个角色
     * 都要重建上下文。LangGraph 版也是"一个 agent + 工具节点"的循环，这里保持同一形态。</p>
     */
    private static void runTravel(String[] args) {
        ModelConfig config = AppConfig.get().model();
        if (!ensureModelReady(config)) {
            return;
        }
        String task = args.length > 1 ? String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length))
                : "帮我规划长沙两日游。先给我 3 个景点的名称、简介、开放时间、建议游玩时长，"
                + "以及每个景点的经纬度坐标。";

        AgentRuntime runtime = new AgentRuntime();
        attachTypewriter(runtime);

        AgentId planner = runtime.register("travel", "planner",
                ChatAgent.streamingBuilder("travel", config.newStreamingChatModel())
                        .systemMessage(TravelPrompt.SYSTEM)
                        .tools(TravelTools.all())
                        // 一个目的地要查好几个景点的坐标，默认 5 次不够
                        .maxToolIterations(12)
                        .build());

        // 本地小模型常会先说一句"我接下来查坐标"就结束本轮，留几轮把它拉回正题
        Team team = new RoundRobinTeam(runtime, List.of(planner)).maxRounds(3);

        System.out.println("=== 旅游规划（" + config.describe() + "）===");
        System.out.println("任务: " + task + "\n");
        summary(team.run(task));
    }

    /**
     * 旅游工具自检：不经过模型，直接逐个调用。
     *
     * <p>申请完地图 Key 之后最先该跑的就是这个——它能区分"Key 没配好"和"模型不给力"，
     * 省得在模型那头瞎调提示词。当前用哪家由 {@code tool.map.provider} 决定，
     * 自检里会打印实际生效的服务商，避免"配了百度却在报高德的错"。</p>
     */
    private static void runToolCheck() {
        MapClient map = MapClients.fromConfig();
        System.out.println("=== 旅游工具自检（不走模型）===");
        System.out.println("地图服务: " + map.displayName() + "（" + map.provider() + "）   Key/AK: "
                + map.maskedKey());
        if (!map.configured()) {
            System.out.println("          未配置 → 下面 4 个地图工具会失败。申请地址：");
            System.out.println("          " + map.applyUrl());
            System.out.println("          填到 agent.properties 的 "
                    + ("baidu".equals(map.provider()) ? "tool.baidu.ak" : "tool.amap.key")
                    + "，并把 tool.map.provider 设为 " + map.provider());
        }
        System.out.println();

        // 长沙岳麓山/橘子洲，坐标取自高德（GCJ-02），两家 provider 都能直接用
        check("get_location_coordinate", () -> new LocationTool(map).getLocationCoordinate("岳麓山", "长沙"));
        check("search_nearby_poi", () -> new NearbyTool(map)
                .searchNearbyPoi("112.938814,28.228209", "0731", "中餐厅", "", 2000, 5, 1));
        check("route_planning", () -> new RouteTool(map)
                .routePlanning("112.938814,28.228209", "112.960976,28.184095", "0731", "0731"));
        check("get_static_map_image", () -> new StaticMapTool(map, AppConfig.get().notesDir().resolve("maps"))
                .getStaticMapImage("112.938814,28.228209", 12));
        check("web_search", () -> new WebSearchTool().webSearch("长沙 岳麓山 开放时间", 3));
        check("get_attractions_information", () -> new AttractionsTool(3, "poi", map)
                .getAttractionsInformation("长沙"));
        check("save_info_and_clear_history", () -> new TravelNotebookTool()
                .saveInfoAndClearHistory("自检：岳阳楼 113.129362,29.371356；岳麓山 112.938814,28.228209"));
    }

    private static void check(String name, Supplier<String> action) {
        long start = System.nanoTime();
        try {
            String result = action.get();
            long millis = (System.nanoTime() - start) / 1_000_000L;
            System.out.printf("  [OK]   %-32s %5dms  %s%n", name, millis, truncate(result, 220));
        } catch (RuntimeException e) {
            long millis = (System.nanoTime() - start) / 1_000_000L;
            System.out.printf("  [FAIL] %-32s %5dms  %s%n", name, millis, e.getMessage());
        }
    }

    private static String truncate(String text, int limit) {
        if (text == null) {
            return "null";
        }
        String flat = text.replaceAll("\\s+", " ");
        return flat.length() <= limit ? flat : flat.substring(0, limit) + "…";
    }

    /** 演示 AutoGen 的发布订阅语义：无订阅静默丢弃，有订阅才投递，多租户按需实例化。 */    private static void demoPubSub() {
        System.out.println("\n=== 发布订阅（Topic / Subscription）===");
        AgentRuntime runtime = new AgentRuntime();
        runtime.registerFactory("auditor", id -> new EchoAgent("auditor-" + id.key()));

        runtime.publish(Topic.of("audit"), new ChatMsg.Text("planner", "无订阅时发布"));
        System.out.println("无订阅：dropped=" + runtime.droppedMessages() + "，投递 " + runtime.drain() + " 条");

        runtime.subscribe("audit", "auditor");
        runtime.publish(Topic.of("audit", "tenant-a"), new ChatMsg.Text("planner", "租户 A"));
        runtime.publish(Topic.of("audit", "tenant-b"), new ChatMsg.Text("planner", "租户 B"));
        System.out.println("订阅后：dropped=" + runtime.droppedMessages()
                + "，投递 " + runtime.drain() + " 条"
                + "，自动创建 agent " + runtime.agentCount() + " 个（topic source 即 agent key）");
    }

    /** 把事件流接成打字机输出。流式回调发生在模型客户端线程，打印要串起来。 */
    private static void attachTypewriter(AgentRuntime runtime) {
        runtime.onEvent(event -> {
            synchronized (PRINT_LOCK) {
                if (event instanceof AgentEvent.SpeakingStarted started) {
                    System.out.printf("%n[%-8s] ", started.agent());
                } else if (event instanceof AgentEvent.TextDelta delta) {
                    System.out.print(delta.delta());
                    System.out.flush();
                } else if (event instanceof AgentEvent.SpeakingFinished) {
                    System.out.println();
                } else if (event instanceof AgentEvent.ToolCallRequested requested) {
                    System.out.printf("%n  → 调用工具 %s %s%n", requested.tool(), requested.arguments());
                } else if (event instanceof AgentEvent.ToolExecuted executed) {
                    System.out.printf("  ← 返回 %s（%dms）%n", executed.result(), executed.millis());
                }
            }
        });
    }

    private static List<AgentId> mockMembers(AgentRuntime runtime, MockChatServer mock) {
        ModelConfig config = ModelConfig.forMock(mock.baseUrl());
        return List.of(
                runtime.register("pm", "planner", ChatAgent.builder("planner", config.newChatModel(0.0))
                        .systemMessage(PM_SYSTEM).build()),
                runtime.register("reviewer", "reviewer", ChatAgent.builder("reviewer", config.newChatModel(0.0))
                        .systemMessage(REVIEWER_SYSTEM).build()));
    }

    /** 探测配置的模型是否可用；不可用时打印可直接执行的修复命令。 */
    private static boolean ensureModelReady(ModelConfig config) {
        if (!config.isOllama()) {
            if (!config.hasApiKey()) {
                System.out.println("配置的端点 " + config.baseUrl() + " 需要 API Key。");
                System.out.println("在 agent.properties 里填 model.api-key，或设置环境变量：");
                System.out.println("  export " + config.provider().toUpperCase() + "_API_KEY=sk-xxx");
                return false;
            }
            return true;
        }

        OllamaProbe.Status status = OllamaProbe.probe(config.rootUrl());
        if (!status.reachable()) {
            System.out.println("连不上 Ollama：" + config.rootUrl() + "（" + status.error() + "）");
            System.out.println("先启动服务再重跑：");
            System.out.println("  ollama serve");
            System.out.println("不想装模型可离线自检：mvn -q exec:java -Dexec.args=\"mock\"");
            return false;
        }
        if (status.models().isEmpty()) {
            System.out.println("Ollama 已启动，但本地还没有任何模型。先拉一个：");
            System.out.println("  ollama pull " + config.model());
            return false;
        }
        if (!status.hasModel(config.model())) {
            System.out.println("本地没有模型 " + config.model() + "，当前可用：" + status.models());
            System.out.println("二选一：");
            System.out.println("  ollama pull " + config.model());
            System.out.println("  在 agent.properties 里把 model.name 改成上面已有的某个");
            return false;
        }
        System.out.println("Ollama 就绪，本地模型：" + status.models());
        return true;
    }

    private static void report(TaskResult result, int memberCount) {
        transcript(result);

        System.out.println("\n--- 事件时间线 ---");
        int index = 1;
        for (AgentEvent event : result.events()) {
            System.out.printf("%2d. %s%n", index++, describe(event));
        }

        System.out.println("\n--- 停止原因 ---");
        System.out.println(result.stopReason());
        System.out.println("成员数=" + memberCount
                + "  事件数=" + result.events().size()
                + "  消息数=" + result.messages().size()
                + "  轮次=" + result.round());
    }

    /** 流式模式下只给摘要：正文已经边生成边打印过了，重复贴一遍反而难读。 */
    private static void summary(TaskResult result) {
        System.out.println("\n--- 摘要 ---");
        System.out.println("停止原因: " + result.stopReason());
        long deltas = result.events().stream()
                .filter(e -> e instanceof AgentEvent.TextDelta)
                .count();
        long tools = result.events().stream()
                .filter(e -> e instanceof AgentEvent.ToolExecuted)
                .count();
        System.out.println("轮次=" + result.round()
                + "  消息数=" + result.messages().size()
                + "  事件数=" + result.events().size()
                + "（其中流式增量 " + deltas + " 片，工具调用 " + tools + " 次）");
    }

    private static void transcript(TaskResult result) {
        System.out.println("\n--- 对话记录 ---");
        for (ChatMsg msg : result.messages()) {
            System.out.printf("[%-9s] %s%n", msg.source(), msg.text());
        }
    }

    private static String describe(AgentEvent event) {
        if (event instanceof AgentEvent.SpeakingStarted started) {
            return "round " + started.round() + " · " + started.agent() + " 开始发言";
        }
        if (event instanceof AgentEvent.SpeakingFinished finished) {
            return "round " + finished.round() + " · " + finished.agent() + " 发言结束（"
                    + finished.text().length() + " 字）";
        }
        if (event instanceof AgentEvent.TextDelta delta) {
            return delta.agent() + " 增量片段 " + delta.delta().length() + " 字";
        }
        if (event instanceof AgentEvent.ToolCallRequested requested) {
            return requested.agent() + " 请求工具 " + requested.tool() + " " + requested.arguments();
        }
        if (event instanceof AgentEvent.ToolExecuted executed) {
            return executed.agent() + " 工具 " + executed.tool() + " 返回 " + executed.result()
                    + "（" + executed.millis() + "ms）";
        }
        return event.toString();
    }

    private Main() {
    }
}
