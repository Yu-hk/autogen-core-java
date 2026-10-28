package com.example.agentcore.web;

import com.example.agentcore.support.MockChatServer;
import com.example.agentcore.support.ModelConfig;
import com.example.agentcore.team.TaskResult;
import com.example.agentcore.messaging.ChatMsg;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SSE 断点续传：浏览器断线重连时，服务端要能从 {@code Last-Event-ID} 之后接着发。
 *
 * <p>这条能力在内网比在公网更要紧：会议室切 Wi-Fi、笔记本合盖、代理悄悄掐长连接，
 * 都会让一条已经跑了五分钟的流断掉。断线时如果只会"从头重放"或者"直接结束"，
 * 用户看到的就是"答到一半没了"——之前排查过的那个观感问题，根因之一就在这里。</p>
 *
 * <p>下面两层都测：{@link RunSession} 的序号与保留窗口是机制本身，
 * HTTP 那一层验证浏览器实际会经历的事情。</p>
 */
@Timeout(60)
class SseResumeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .proxy(ProxySelector.of(null))
            .build();

    // ---------- 机制本身 ----------

    /** 序号必须从 1 开始严格连续：客户端说"收到 42"，服务端就得能确定 43 是哪条。 */
    @Test
    void sequenceIsDenseAndStartsAtOne() throws Exception {
        RunSession session = new RunSession("abc12345");
        session.status("running", "开始");
        session.message("reviewer", "第一句");
        session.message("reviewer", "第二句");

        assertEquals(3, session.latestSeq());

        List<RunSession.Entry> all = session.entriesAfter(0);
        assertEquals(3, all.size());
        for (int i = 0; i < all.size(); i++) {
            assertEquals(i + 1, all.get(i).seq(), "序号应连续，不能有空洞");
        }
        assertEquals(2, session.entriesAfter(1).size(), "游标之后应剩两条");
        assertEquals(0, session.entriesAfter(3).size(), "游标已到最新就没有了");
    }

    /** 轮询要能阻塞等待新事件，而不是一没数据就返回——否则 SSE 会空转烧 CPU。 */
    @Test
    void pollAfterWaitsForNewEvents() throws Exception {
        RunSession session = new RunSession("abc12345");
        session.status("running", "开始");

        Thread producer = new Thread(() -> {
            try {
                Thread.sleep(120);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            session.message("writer", "迟到的发言");
        });
        producer.start();

        RunSession.Entry entry = session.pollAfter(1, 5_000);
        producer.join();

        assertNotNull(entry, "等到了新事件就不该返回 null");
        assertEquals(2, entry.seq());
        assertTrue(entry.payload().contains("迟到的发言"));
    }

    /** 收尾事件要带标记，SSE 循环据此关闭连接；否则连接会一直挂着发心跳。 */
    @Test
    void terminalEventIsFlagged() throws Exception {
        RunSession session = new RunSession("abc12345");
        session.status("running", "开始");

        TaskResult result = new TaskResult(
                List.of(new ChatMsg.Text("writer", "做完了")),
                List.of(), "APPROVE", 1);
        session.done(result, "snap-1.json");

        List<RunSession.Entry> entries = session.entriesAfter(0);
        assertEquals(2, entries.size());
        assertFalse(entries.get(0).terminal(), "status 不是收尾事件");
        assertTrue(entries.get(1).terminal(), "done 必须是收尾事件");
        assertTrue(session.isTerminal());
    }

    /**
     * 保留窗口滚过之后，要能诚实地说"补不了"。
     *
     * <p>默默少发一段是最坏的处理：页面会停在半截对话上，看不出是模型没答完还是事件丢了。
     * {@code hasGap} 就是让上层有机会改走"重拉全量"这条路。</p>
     */
    @Test
    void gapIsReportedAfterWindowSlidesPast() {
        RunSession session = new RunSession("abc12345");
        int total = RunSession.MAX_RETAINED + 50;
        for (int i = 0; i < total; i++) {
            session.status("running", "第 " + i + " 条");
        }

        assertEquals(total, session.latestSeq());
        assertTrue(session.hasGap(0), "最早的事件已经被挤出去了，从 0 补不全");
        assertTrue(session.hasGap(10), "落后超过窗口一律算缺口");
        assertFalse(session.hasGap(total - 5), "只落后几条，能补");

        // 窗口内的条数不应超过上限，否则保留窗口就失去意义了
        assertTrue(session.entriesAfter(0).size() <= RunSession.MAX_RETAINED,
                "实际保留 " + session.entriesAfter(0).size() + " 条，超过上限");
    }

    // ---------- 浏览器视角 ----------

    /**
     * 断线重连的完整走法：读到一半挂掉，带 {@code Last-Event-ID} 回来，接着发。
     *
     * <p>断言的重点是"第二次的第一条正好是第一次的下一条"——如果服务端从头重放，
     * 页面上的增量会重复打印一遍；如果服务端直接丢弃，页面就永远缺那一段。</p>
     */
    @Test
    void reconnectResumesFromLastEventId(@TempDir Path dir) throws Exception {
        try (MockChatServer mock = reviewScript();
             AgentWebServer web = AgentWebServer.start(0, dir, () -> ModelConfig.forMock(mock.baseUrl()))) {
            String sessionId = startRun(web);
            String stream = web.url() + "/api/stream?sessionId=" + sessionId;

            List<Frame> first = readFrames(stream, null, 5);
            assertEquals(5, first.size(), "应能读到 5 条就主动断开");
            long cursor = first.get(first.size() - 1).seq();
            assertTrue(cursor >= 5);

            // 带游标重连：服务端应当从 cursor + 1 开始，而不是从 1 开始
            List<Frame> rest = readFrames(stream, cursor, -1);
            assertFalse(rest.isEmpty(), "重连后应有后续事件");
            assertEquals(cursor + 1, rest.get(0).seq(), "应从断点之后无缝续上");

            // 两段拼起来：序号连续无缺口，且以 done 收尾
            List<Frame> merged = new ArrayList<>(first);
            merged.addAll(rest);
            for (int i = 0; i < merged.size(); i++) {
                assertEquals(i + 1, merged.get(i).seq(),
                        "第 " + i + " 帧序号应连续（重连不能重复也不能跳）");
            }
            assertEquals("done", merged.get(merged.size() - 1).kind(),
                    "续传到底应停在 done，而不是停在半截");
        }
    }

    /** 不带游标（首次连接）必须从头开始：页面刚打开时手里什么都没有。 */
    @Test
    void freshConnectionStartsWithFirstEvent(@TempDir Path dir) throws Exception {
        try (MockChatServer mock = reviewScript();
             AgentWebServer web = AgentWebServer.start(0, dir, () -> ModelConfig.forMock(mock.baseUrl()))) {
            String sessionId = startRun(web);
            List<Frame> frames = readFrames(web.url() + "/api/stream?sessionId=" + sessionId, null, 3);
            assertEquals(1, frames.get(0).seq());
        }
    }

    /**
     * 会话已回收时给一条能读懂的 {@code expired}，而不是 404。
     *
     * <p>EventSource 收到非 200 只会抛一个没有任何信息的 error，页面只能说"连接断开"，
     * 用户就会反复刷新等它自己好。</p>
     */
    @Test
    void unknownSessionYieldsExpiredEvent(@TempDir Path dir) throws Exception {
        try (AgentWebServer web = AgentWebServer.start(0, dir)) {
            List<Frame> frames = readFrames(web.url() + "/api/stream?sessionId=deadbeef", null, -1);
            assertEquals(1, frames.size());
            assertEquals("expired", frames.get(0).kind());
            assertTrue(frames.get(0).json().path("message").asText().contains("重新发起"),
                    "提示里要告诉用户下一步做什么");
        }
    }

    // ---------- 工具 ----------

    private record Frame(long seq, String kind, JsonNode json) {
    }

    private static MockChatServer reviewScript() throws IOException {
        MockChatServer mock = MockChatServer.start();
        mock.enqueueText("需求拆解：导出接口、权限校验、异步任务。");
        mock.enqueueText("方案：异步任务表落库，游标分页读数据。");
        mock.enqueueText("补充进度推送与失败重试。APPROVE");
        return mock;
    }

    private static String startRun(AgentWebServer web) throws IOException, InterruptedException {
        HttpResponse<String> response = CLIENT.send(
                HttpRequest.newBuilder(URI.create(web.url() + "/api/run?scenario=review&maxRounds=4"))
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .header("Content-Type", "application/json")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "发起运行应成功：" + response.body());
        return MAPPER.readTree(response.body()).path("sessionId").asText();
    }

    /**
     * 读若干帧。
     *
     * @param lastEventId 断点游标；{@code null} 表示首次连接
     * @param limit       读到多少帧就主动断开；负数表示读到流被服务端关闭为止
     */
    private static List<Frame> readFrames(String url, Long lastEventId, int limit)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).GET();
        if (lastEventId != null) {
            builder.header("Last-Event-ID", String.valueOf(lastEventId));
        }

        HttpResponse<InputStream> response = CLIENT.send(builder.build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode(), "SSE 应返回 200（会话失效也走事件流）");

        List<Frame> frames = new ArrayList<>();
        long pendingSeq = -1;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("id: ")) {
                    pendingSeq = Long.parseLong(line.substring(4).trim());
                } else if (line.startsWith("data: ")) {
                    JsonNode json = MAPPER.readTree(line.substring(6));
                    frames.add(new Frame(pendingSeq, json.path("kind").asText(), json));
                    pendingSeq = -1;
                    if (limit > 0 && frames.size() >= limit) {
                        break; // 主动断开，模拟浏览器被掐线
                    }
                }
            }
        }
        return frames;
    }
}
