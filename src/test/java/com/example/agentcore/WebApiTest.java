package com.example.agentcore;

import com.example.agentcore.support.AppConfig;
import com.example.agentcore.support.MockChatServer;
import com.example.agentcore.support.ModelConfig;
import com.example.agentcore.web.AgentWebServer;
import com.example.agentcore.web.ModelSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 页面后端的接口测试：起真服务、走真 SSE，断言浏览器会收到什么。
 *
 * <p>刻意不 mock 掉 HTTP 层——SSE 的分块与终止时机正是最容易出问题的地方，
 * 只有真连一次才能验到。</p>
 *
 * <p>模型这一层用 {@link ModelSource} 注入 {@link MockChatServer}：页面本身已经不支持离线剧本，
 * 但测试不该依赖一个真跑得动的 7B 模型，也不想让断言随模型输出漂移。剧本由测试自己排，
 * 因此下面每个用例的"剧本"就是它要验证的那条编排假设。</p>
 */
@Timeout(60)
class WebApiTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 客户端显式直连：JDK 会把回环地址也交给系统代理。 */
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .proxy(ProxySelector.of(null))
            .build();

    /** review 场景的替身剧本：拆解 → 调工具 → 出方案 → 通过。 */
    private static MockChatServer reviewScript() throws IOException {
        MockChatServer mock = MockChatServer.start();
        mock.enqueueText("需求拆解：1) 导出接口 2) 权限校验 3) 异步任务与进度查询。");
        mock.enqueueToolCall("estimateComplexity",
                "{\"requirement\":\"订单导出，百万级数据，含权限校验、异步任务、分片合并与审计\"}");
        mock.enqueueText("复杂度 5。方案：异步任务表落库，游标分页读数据，权限在网关层统一校验。");
        mock.enqueueText("缺进度推送与失败重试，其余可行。APPROVE");
        return mock;
    }

    private static ModelSource source(MockChatServer mock) {
        return () -> ModelConfig.forMock(mock.baseUrl());
    }

    /**
     * 允许把这些目录当工作区的配置。
     *
     * <p>白名单默认是空的（只有 {@code target/code-workspace}），所以凡是要用
     * {@code @TempDir} 当工作区的用例都必须显式声明——这正是新行为的一部分：
     * 「传什么目录就用什么目录」已经不成立了。</p>
     */
    private static AppConfig allowDirs(Path... dirs) {
        Properties props = new Properties();
        props.setProperty("tool.code.allow-select", "true");
        StringBuilder sb = new StringBuilder();
        for (Path dir : dirs) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(dir.toAbsolutePath().normalize());
        }
        props.setProperty("tool.code.writable-dirs", sb.toString());
        return AppConfig.of(props);
    }

    @Test
    void scenariosDescribeMembersAndTools(@TempDir Path dir) throws Exception {
        try (AgentWebServer web = AgentWebServer.start(0, dir)) {
            JsonNode list = get(web.url() + "/api/scenarios");
            assertEquals(7, list.size(),
                    "应暴露七个场景：需求评审 / 方案打磨 / 模型选人 / 旅游规划 / 分工流水线 / 写代码 / 直接回答");

            JsonNode review = list.get(0);
            assertEquals("review", review.path("id").asText());
            assertEquals(3, review.path("members").size());
            assertTrue(review.path("members").get(1).path("tools").asBoolean(),
                    "工程角色应带工具");
            assertEquals("calculator", review.path("members").get(1).path("toolset").asText());

            JsonNode travel = list.get(3);
            assertEquals("travel", travel.path("id").asText());
            assertEquals(1, travel.path("members").size(), "旅游规划是单 agent");
            assertEquals("travel", travel.path("members").get(0).path("toolset").asText(),
                    "旅游场景应挂整套旅游工具");

            // 分工场景：第 1 棒挂工具走主模型，第 2 棒无工具走第二套模型
            JsonNode pipeline = list.get(4);
            assertEquals("travel-pipeline", pipeline.path("id").asText());
            assertEquals(2, pipeline.path("members").size(), "流水线是两个角色接力");
            assertEquals("travel", pipeline.path("members").get(0).path("toolset").asText());
            assertEquals("main", pipeline.path("members").get(0).path("model").asText());
            assertFalse(pipeline.path("members").get(1).path("tools").asBoolean(),
                    "总结角色不该有工具");
            assertEquals("summarizer", pipeline.path("members").get(1).path("model").asText(),
                    "总结角色应走第二套模型");

            // 写代码场景：单 agent，挂文件与命令工具，否则"写代码"只能写进聊天记录
            JsonNode coding = list.get(5);
            assertEquals("coding", coding.path("id").asText());
            assertEquals(1, coding.path("members").size(), "编码是单 agent 干活");
            assertEquals("coding", coding.path("members").get(0).path("toolset").asText(),
                    "编码场景必须挂读写文件与跑命令的工具");

            // 兜底路径必须存在：没有它，"用户只提问"就有问题落不进任何场景
            JsonNode general = list.get(6);
            assertEquals("general", general.path("id").asText());
            assertEquals(1, general.path("members").size());
            // 场景本身仍然"不挂工具"：只读文件工具是运行时按"这一轮有没有选工作目录"叠加的，
            // 不是场景固有属性。能不能看由用户在页面上决定，所以这里断言 none 是对的。
            assertEquals("none", general.path("members").get(0).path("toolset").asText(),
                    "只读能力是运行时叠加的，场景本身不声明工具");
        }
    }

    /** 一次完整运行：流式增量、工具事件、终止统计都要能通过 SSE 到达客户端。 */
    @Test
    void runStreamsDeltasToolsAndDone(@TempDir Path dir) throws Exception {
        try (MockChatServer mock = reviewScript();
             AgentWebServer web = AgentWebServer.start(0, dir, source(mock))) {
            String sessionId = post(web.url() + "/api/run?scenario=review&maxRounds=4")
                    .path("sessionId").asText();
            assertFalse(sessionId.isBlank());

            List<JsonNode> events = drain(web.url() + "/api/stream?sessionId=" + sessionId);

            long deltas = count(events, "delta");
            long tools = events.stream()
                    .filter(e -> "tool".equals(e.path("kind").asText()))
                    .filter(e -> "result".equals(e.path("phase").asText()))
                    .count();
            JsonNode done = events.stream()
                    .filter(e -> "done".equals(e.path("kind").asText()))
                    .findFirst().orElseThrow(() -> new AssertionError("缺少 done 事件"));

            assertTrue(deltas > 3, "流式应产生多片增量，实际: " + deltas);
            assertEquals(1, tools, "应有一次工具结果");
            assertTrue(done.path("stopReason").asText().contains("APPROVE"));
            assertEquals(4, done.path("messages").asInt());
            assertFalse(done.path("snapshot").asText().isBlank(), "结束时应写出快照");

            // 快照确实落盘，且能出现在列表里
            try (Stream<Path> files = Files.list(dir)) {
                assertEquals(1, files.count());
            }
            JsonNode snapshots = get(web.url() + "/api/snapshots");
            assertEquals(1, snapshots.size());
            assertEquals(4, snapshots.get(0).path("messageCount").asInt());
        }
    }

    /** 续跑：新会话先回放历史，再接着生成，轮次不清零。 */
    @Test
    void resumeReplaysHistoryThenContinues(@TempDir Path dir) throws Exception {
        try (MockChatServer mock = reviewScript();
             AgentWebServer web = AgentWebServer.start(0, dir, source(mock))) {
            String first = post(web.url() + "/api/run?scenario=review&maxRounds=4")
                    .path("sessionId").asText();
            drain(web.url() + "/api/stream?sessionId=" + first);

            String file = get(web.url() + "/api/snapshots").get(0).path("file").asText();

            // 续跑的回复同样来自剧本：补一条收尾，否则剧本空了会拿到默认文本
            mock.enqueueText("（续跑）补齐监控与重试，其余沿用上一版。APPROVE");

            String second = post(web.url() + "/api/resume?scenario=review&file=" + file)
                    .path("sessionId").asText();
            List<JsonNode> events = drain(web.url() + "/api/stream?sessionId=" + second);

            // 首条业务事件应是历史回放（前面还会有一条 status 生命周期事件）
            JsonNode history = events.stream()
                    .filter(e -> "history".equals(e.path("kind").asText()))
                    .findFirst().orElseThrow(() -> new AssertionError("缺少历史回放事件"));
            assertEquals(4, history.path("messages").size());
            assertEquals(3, history.path("round").asInt(), "应带着上次的轮次");

            JsonNode done = events.stream()
                    .filter(e -> "done".equals(e.path("kind").asText()))
                    .findFirst().orElseThrow(() -> new AssertionError("缺少 done 事件"));
            assertEquals(4, done.path("round").asInt(), "续跑后轮次应递增到 4");
            assertEquals(5, done.path("messages").asInt(), "4 条历史 + 1 条续跑发言");
        }
    }

    /** 自定义任务应原样进入共享上下文。 */
    @Test
    void customTaskOverridesScenario(@TempDir Path dir) throws Exception {
        try (MockChatServer mock = reviewScript();
             AgentWebServer web = AgentWebServer.start(0, dir, source(mock))) {
            String task = java.net.URLEncoder.encode("导出对账单", java.nio.charset.StandardCharsets.UTF_8);
            String sessionId = post(web.url() + "/api/run?scenario=review&maxRounds=1&task=" + task)
                    .path("sessionId").asText();

            List<JsonNode> events = drain(web.url() + "/api/stream?sessionId=" + sessionId);
            JsonNode user = events.stream()
                    .filter(e -> "message".equals(e.path("kind").asText()))
                    .filter(e -> "user".equals(e.path("source").asText()))
                    .findFirst().orElseThrow();
            assertEquals("导出对账单", user.path("text").asText());
        }
    }

    /**
     * 不传 scenario 时，后端必须自己判断——页面只有一个输入框，用户不该知道"场景"这回事。
     *
     * <p>这条同时守住两件事：默认值确实是 auto（而不是悄悄退回某个写死的场景），
     * 以及判断结果能通过 {@code route} 事件告知页面——页面的成员列表靠它渲染。</p>
     */
    @Test
    void autoRouteDecidesWithoutUserChoosing(@TempDir Path dir) throws Exception {
        try (MockChatServer mock = MockChatServer.start();
             AgentWebServer web = AgentWebServer.start(0, dir, source(mock))) {
            mock.enqueueText("橘子洲位于长沙市岳麓区，坐标 112.961998,28.186938。");

            String task = java.net.URLEncoder.encode("橘子洲的经纬度是多少",
                    java.nio.charset.StandardCharsets.UTF_8);
            // 刻意不带 scenario 参数
            String sessionId = post(web.url() + "/api/run?task=" + task).path("sessionId").asText();

            List<JsonNode> events = drain(web.url() + "/api/stream?sessionId=" + sessionId);
            JsonNode route = events.stream()
                    .filter(e -> "route".equals(e.path("kind").asText()))
                    .findFirst().orElseThrow(() -> new AssertionError("没有 route 事件：" + events));

            assertEquals("travel", route.path("scenario").asText());
            assertEquals("旅游规划", route.path("title").asText());
            assertFalse(route.path("reason").asText().isBlank(), "路由必须给出理由");
            assertEquals(1, count(events, "done"), "自动路由之后仍要正常跑完");
        }
    }

    /** 自动模式下没写问题就发送，要给明确提示，而不是跑一个空任务白烧一次模型调用。 */
    @Test
    void autoRouteRejectsEmptyTask(@TempDir Path dir) throws Exception {
        try (AgentWebServer web = AgentWebServer.start(0, dir)) {
            JsonNode res = post(web.url() + "/api/run?scenario=auto");
            assertTrue(res.path("error").asText().contains("问题"), "应提示先输入问题：" + res);
        }
    }

    /**
     * 工作目录由页面传上来：/api/run 带 workspace 就用它，/api/fs/list 能把本机目录列给页面。
     *
     * <p>这两条是配套的——浏览器读不到本地路径，所以"选目录"必须由服务端代劳；
     * 而选完的目录要真的进到这次 run 里，否则用户选了 demo-project，模型还在默认沙箱里写。</p>
     */
    @Test
    void workspaceSelectablePerRun(@TempDir Path dir, @TempDir Path project) throws Exception {
        try (MockChatServer mock = MockChatServer.start();
             AgentWebServer web = AgentWebServer.start(0, dir, source(mock), allowDirs(project))) {
            java.nio.file.Files.writeString(project.resolve("pom.xml"), "<project/>");
            java.nio.file.Files.createDirectories(project.resolve("src"));
            String projectPath = project.toString().replace("\\", "/");

            // 1. 目录浏览：只列目录，并告诉页面这个目录能不能当工作区
            JsonNode listed = get(web.url() + "/api/fs/list?path=" + enc(projectPath));
            assertTrue(listed.path("ok").asBoolean(), listed.toString());
            assertTrue(listed.path("writable").asBoolean(), listed.toString());
            assertTrue(listed.path("blockReason").asText().isEmpty(), listed.toString());
            assertEquals("src", listed.path("entries").get(0).path("name").asText(), listed.toString());

            // 2. 带上 workspace 发起一次 coding 运行，返回值里应回显实际用的目录
            mock.enqueueText("看过了，是个 Maven 单模块工程。");
            String task = enc("看一下这个项目的结构");
            JsonNode started = post(web.url() + "/api/run?scenario=coding&task=" + task
                    + "&workspace=" + enc(projectPath));
            assertEquals(projectPath, started.path("workspace").asText(), started.toString());

            // 3. 选到盘符根：直接 400，别等跑起来才报越界
            Path driveRoot = project.toAbsolutePath().getRoot();
            if (driveRoot != null) {
                JsonNode bad = post(web.url() + "/api/run?scenario=coding&task=" + task
                        + "&workspace=" + enc(driveRoot.toString().replace("\\", "/")));
                assertTrue(bad.path("error").asText().contains("根目录"), bad.toString());
            }
        }
    }

    /**
     * "分析一下这个项目"必须拿到只读文件工具，而不是"我没有工具权限"。
     *
     * <p>这句话不含任何技术域词（"项目"不是域词），路由必然落到 general。而 general 原来
     * {@code toolset=none}、提示词里还写着"你没有工具"——于是用户明明已经在页面上选好了目录，
     * 得到的回答是"我这次没有文件系统和工具权限，读不到"。这条用例钉三件事：
     * 路由仍归 general、请求里带上了 4 个读工具、<b>没有</b>带写工具与命令工具。</p>
     *
     * <p>为什么断言请求体而不是回复：回复可以靠模型自己圆，请求体里有没有 tools 骗不了人。</p>
     */
    @Test
    void analysisTaskGetsReadOnlyFileTools(@TempDir Path dir, @TempDir Path project) throws Exception {
        try (MockChatServer mock = MockChatServer.start();
             AgentWebServer web = AgentWebServer.start(0, dir, source(mock), allowDirs(project))) {
            Files.writeString(project.resolve("pom.xml"), "<project/>");
            Files.createDirectories(project.resolve("src/main/java"));
            Files.writeString(project.resolve("src/main/java/App.java"), "class App {}");
            String projectPath = project.toString().replace("\\", "/");

            mock.enqueueText("看了下根目录：单模块 Maven 工程，一个 App.java。");
            JsonNode started = post(web.url() + "/api/run?task=" + enc("分析一下这个项目")
                    + "&workspace=" + enc(projectPath));
            List<JsonNode> events = drain(web.url() + "/api/stream?sessionId="
                    + started.path("sessionId").asText());

            JsonNode route = events.stream()
                    .filter(e -> "route".equals(e.path("kind").asText()))
                    .findFirst().orElseThrow(() -> new AssertionError("缺少 route 事件：" + events));
            assertEquals("general", route.path("scenario").asText(),
                    "这句话不含技术域词，应走 general：" + route.path("reason").asText());

            String body = mock.lastRequest();
            assertTrue(body.contains("\"list_files\""), "分析项目必须能看目录：" + body);
            assertTrue(body.contains("\"read_dir\""), "分析项目要能批量读目录：" + body);
            assertTrue(body.contains("\"read_file\""), "分析项目必须能读文件：" + body);
            assertTrue(body.contains("\"search_code\""), "分析项目必须能搜代码：" + body);
            assertFalse(body.contains("\"write_file\""), "只读助手不该拿到写工具：" + body);
            assertFalse(body.contains("\"edit_file\""), "只读助手不该拿到改文件工具：" + body);
            assertFalse(body.contains("\"run_command\""), "只读助手不该拿到命令工具：" + body);
            assertTrue(body.contains(project.getFileName().toString()),
                    "提示词里要写出工作目录，否则模型不知道该看哪里：" + body);
            assertTrue(body.contains("只读"), "提示词必须说明这些工具只读：" + body);
        }
    }

    /**
     * 没选工作目录时，要"请用户选目录"，不能说"我没有工具权限"。
     *
     * <p>"我没有文件系统权限"和"你还没告诉我看哪里"是两件事，前者是假的、还会把用户堵死；
     * 后者能直接指到页面上的入口。这条用例钉的是措辞——它决定用户下一步做什么。</p>
     */
    @Test
    void analysisWithoutWorkspaceAsksForDirectory(@TempDir Path dir) throws Exception {
        try (MockChatServer mock = MockChatServer.start();
             AgentWebServer web = AgentWebServer.start(0, dir, source(mock))) {
            mock.enqueueText("还没指定目录。请用左栏「工作目录」选一个本地目录。");
            JsonNode started = post(web.url() + "/api/run?task=" + enc("分析一下这个项目"));
            drain(web.url() + "/api/stream?sessionId=" + started.path("sessionId").asText());

            String body = mock.lastRequest();
            assertFalse(body.contains("\"list_files\""), "没选目录时没有可看的目录，不该挂工具：" + body);
            assertTrue(body.contains("没有指定工作目录"), "要说清是没指定目录而不是没权限：" + body);
            assertTrue(body.contains("工作目录"), "要指到页面上的入口：" + body);
        }
    }

    /**
     * 同一个只读能力要覆盖到"场景本身就带工具"之外的每一条路。
     *
     * <p>"评审一下这个项目的架构"会命中技术域词，路由去 review 而不是 general——
     * 如果只在 general 上补只读工具，这类问题照样答"我看不到你的项目"。所以这条用例
     * 直接指定 review 场景，断言它的成员也拿到了只读文件工具、且没有写工具。</p>
     */
    @Test
    void reviewScenarioAlsoSeesWorkspaceReadOnly(@TempDir Path dir, @TempDir Path project) throws Exception {
        try (MockChatServer mock = reviewScript();
             AgentWebServer web = AgentWebServer.start(0, dir, source(mock), allowDirs(project))) {
            Files.writeString(project.resolve("pom.xml"), "<project/>");
            String projectPath = project.toString().replace("\\", "/");

            JsonNode started = post(web.url() + "/api/run?scenario=review&maxRounds=4&task="
                    + enc("评审一下这个项目的架构") + "&workspace=" + enc(projectPath));
            drain(web.url() + "/api/stream?sessionId=" + started.path("sessionId").asText());

            String body = mock.requests().get(0);
            assertTrue(body.contains("\"list_files\""), "评审类场景也要能看目录：" + body);
            assertTrue(body.contains("\"read_file\""), "评审类场景也要能读文件：" + body);
            assertFalse(body.contains("\"write_file\""), "评审不是动手改，不该有写工具：" + body);
            assertFalse(body.contains("\"run_command\""), "评审不该能跑命令：" + body);
        }
    }

    private static String enc(String text) {
        return java.net.URLEncoder.encode(text, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * 多轮：同一 conversationId 的第二次提问，必须把上一轮的消息接在上下文里。
     *
     * <p>这是之前最缺的一环——每次提问都是新会话，用户问完"长沙一日游"再问"那第二个呢"，
     * 系统会把第二句当成全新问题，坐标和餐馆全丢。断言直接看模型收到的请求体，
     * 不看最终回复：回复可以靠模型自己圆，请求体里有没有上文是骗不了人的。</p>
     */
    @Test
    void followUpCarriesPreviousMessages(@TempDir Path dir) throws Exception {
        try (MockChatServer mock = MockChatServer.start();
             AgentWebServer web = AgentWebServer.start(0, dir, source(mock))) {
            mock.enqueueText("橘子洲位于长沙市岳麓区，坐标 112.961998,28.186938。");

            String firstTask = enc("橘子洲的经纬度是多少");
            JsonNode first = post(web.url() + "/api/run?task=" + firstTask);
            String conversationId = first.path("conversationId").asText();
            assertFalse(conversationId.isBlank(), "第一次提问也要返回 conversationId：" + first);
            assertFalse(first.path("followUp").asBoolean(), "第一次不是追问");
            drain(web.url() + "/api/stream?sessionId=" + first.path("sessionId").asText());

            // 第二句刻意不含任何域词：单看它会被判成 general，只有"接着上文"才对
            mock.enqueueText("那第二个呢。");
            JsonNode second = post(web.url() + "/api/run?task=" + enc("那第二个呢")
                    + "&conversationId=" + conversationId);
            assertEquals(conversationId, second.path("conversationId").asText(),
                    "追问必须复用同一个 conversationId");
            assertTrue(second.path("followUp").asBoolean(), "第二次应标记为追问");

            List<JsonNode> events = drain(web.url() + "/api/stream?sessionId="
                    + second.path("sessionId").asText());
            assertEquals(1, count(events, "done"), "追问也要正常跑完");

            JsonNode route = events.stream()
                    .filter(e -> "route".equals(e.path("kind").asText()))
                    .findFirst().orElseThrow(() -> new AssertionError("缺少 route 事件：" + events));
            assertEquals("travel", route.path("scenario").asText(),
                    "追问不该掉回 general：" + route.path("reason").asText());

            String body = mock.lastRequest();
            assertTrue(body.contains("橘子洲的经纬度是多少"), "第二轮请求里应带上第一轮的问题：" + body);
            assertTrue(body.contains("112.961998"), "第二轮请求里应带上第一轮的回答：" + body);
        }
    }

    /** 追问时明确换了话题：该切到另一套装配，而不是硬用地图工具回答技术问题。 */
    @Test
    void followUpSwitchesWhenTopicChanges(@TempDir Path dir) throws Exception {
        try (MockChatServer mock = MockChatServer.start();
             AgentWebServer web = AgentWebServer.start(0, dir, source(mock))) {
            mock.enqueueText("橘子洲坐标 112.961998,28.186938。");
            JsonNode first = post(web.url() + "/api/run?task=" + enc("橘子洲的经纬度是多少"));
            String conversationId = first.path("conversationId").asText();
            drain(web.url() + "/api/stream?sessionId=" + first.path("sessionId").asText());

            mock.enqueueText("先从分片和索引入手。");
            JsonNode second = post(web.url() + "/api/run?task="
                    + enc("帮我设计一个百万级订单的导出方案") + "&conversationId=" + conversationId);
            List<JsonNode> events = drain(web.url() + "/api/stream?sessionId="
                    + second.path("sessionId").asText());

            JsonNode route = events.stream()
                    .filter(e -> "route".equals(e.path("kind").asText()))
                    .findFirst().orElseThrow(() -> new AssertionError("缺少 route 事件：" + events));
            assertTrue(route.path("scenario").asText().startsWith("review")
                            || route.path("scenario").asText().startsWith("refine"),
                    "换话题应切到技术侧，实际：" + route.path("scenario").asText());
        }
    }

    /** 会话 id 过期（重启或已淘汰）时要能优雅地当成新对话，而不是报错卡住页面。 */
    @Test
    void unknownConversationIdStartsFresh(@TempDir Path dir) throws Exception {
        try (MockChatServer mock = MockChatServer.start();
             AgentWebServer web = AgentWebServer.start(0, dir, source(mock))) {
            mock.enqueueText("好的。");

            JsonNode res = post(web.url() + "/api/run?task=" + enc("你好")
                    + "&conversationId=deadbeef");
            assertFalse(res.has("error"), "过期 id 不该报错：" + res);
            assertFalse(res.path("conversationId").asText().equals("deadbeef"),
                    "应换成一个新的会话 id");
            assertFalse(res.path("followUp").asBoolean(), "过期 id 不算追问");
        }
    }

    /** 模型不可用时，页面必须收到一句能被用户看懂的原因，而不是一个空会话。 */    @Test
    void unavailableModelReportsReason(@TempDir Path dir) throws Exception {
        ModelSource broken = () -> {
            throw new com.example.agentcore.web.ModelUnavailable("连不上 127.0.0.1:11434，先执行 ollama serve");
        };
        try (AgentWebServer web = AgentWebServer.start(0, dir, broken)) {
            String sessionId = post(web.url() + "/api/run?scenario=review").path("sessionId").asText();
            List<JsonNode> events = drain(web.url() + "/api/stream?sessionId=" + sessionId);

            JsonNode error = events.stream()
                    .filter(e -> "error".equals(e.path("kind").asText()))
                    .findFirst().orElseThrow(() -> new AssertionError("缺少 error 事件"));
            assertTrue(error.path("message").asText().contains("ollama serve"),
                    "应把不可用原因透给页面：" + error.path("message").asText());
        }
    }

    @Test
    void unknownScenarioReturnsError(@TempDir Path dir) throws Exception {
        try (AgentWebServer web = AgentWebServer.start(0, dir)) {
            HttpResponse<String> res = CLIENT.send(
                    HttpRequest.newBuilder(URI.create(web.url() + "/api/run?scenario=nope"))
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(500, res.statusCode());
            assertTrue(res.body().contains("未知场景"));
        }
    }

    /**
     * 分工场景：收集侧与总结侧必须打到两个不同的端点，且总结侧能拿到收集侧的输出。
     *
     * <p>这是"分角色"唯一值得自动化的断言——如果两棒共用了一个模型，接口照样 200、
     * 页面照样有回答，肉眼完全看不出分工没生效。所以这里用两个 MockChatServer，
     * 一边断言请求次数，一边检查总结侧真正收到的报文。</p>
     */
    @Test
    void pipelineUsesSecondModelForSummary(@TempDir Path dir) throws Exception {
        try (MockChatServer collector = MockChatServer.start();
             MockChatServer summarizer = MockChatServer.start()) {
            // 收集侧不返回 tool_calls：工具是"可用"不是"必调"，这样不会真的打到地图接口
            collector.enqueueText("资料包\n## 景点坐标\n- 橘子洲 112.961998,28.186938（get_location_coordinate）");
            summarizer.enqueueText("## 景点坐标\n\n| 景点 | 坐标 |\n| --- | --- |\n"
                    + "| 橘子洲 | 112.961998,28.186938 |");
            // 服务端跑之前会探一次 /api/tags，Mock 得先声明"我这儿有这个模型"
            summarizer.declareModel("mock-summarizer");

            Properties props = new Properties();
            props.setProperty("model.provider", "ollama");
            props.setProperty("model.base-url", collector.baseUrl());
            props.setProperty("model.name", "mock-collector");
            props.setProperty("model.summarizer.provider", "ollama");
            props.setProperty("model.summarizer.base-url", summarizer.baseUrl());
            props.setProperty("model.summarizer.name", "mock-summarizer");

            try (AgentWebServer web = AgentWebServer.start(0, dir,
                    () -> ModelConfig.forMock(collector.baseUrl()), AppConfig.of(props))) {
                String sessionId = post(web.url() + "/api/run?scenario=travel-pipeline")
                        .path("sessionId").asText();
                List<JsonNode> events = drain(web.url() + "/api/stream?sessionId=" + sessionId);

                JsonNode done = events.stream()
                        .filter(e -> "done".equals(e.path("kind").asText()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("没有收到 done 事件，最后一条：" + events));
                // messages 是 runtime 里的共享消息数：user 指令 + 收集 + 总结 = 3
                assertEquals(3, done.path("messages").asInt(), "应有 user + 收集 + 总结三条消息");
            }

            assertEquals(1, collector.requestCount(), "收集侧应只调一次模型");
            assertEquals(1, summarizer.requestCount(), "总结侧应只调一次模型");
            String sent = summarizer.lastRequest();
            assertTrue(sent.contains("112.961998,28.186938"), "总结侧应收到资料包里的坐标");
            assertTrue(sent.contains("collector"), "总结侧应看到这段资料来自 collector");
        }
    }

    // ---------- 辅助 ----------
    /** 读完整个 SSE 流（服务端在 done 后关闭连接，故会自然结束）。 */
    private List<JsonNode> drain(String url) throws IOException, InterruptedException {
        HttpResponse<Stream<String>> response = CLIENT.send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofLines());

        List<JsonNode> events = new ArrayList<>();
        try (Stream<String> lines = response.body()) {
            lines.filter(line -> line.startsWith("data: "))
                    .forEach(line -> {
                        try {
                            events.add(MAPPER.readTree(line.substring(6)));
                        } catch (IOException e) {
                            throw new IllegalStateException("SSE 负载不是合法 JSON: " + line, e);
                        }
                    });
        }
        assertFalse(events.isEmpty(), "SSE 流不应为空");
        return events;
    }

    private long count(List<JsonNode> events, String kind) {
        return events.stream().filter(e -> kind.equals(e.path("kind").asText())).count();
    }

    private JsonNode get(String url) throws IOException, InterruptedException {
        return MAPPER.readTree(CLIENT.send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body());
    }

    private JsonNode post(String url) throws IOException, InterruptedException {
        return MAPPER.readTree(CLIENT.send(
                HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString()).body());
    }
}
