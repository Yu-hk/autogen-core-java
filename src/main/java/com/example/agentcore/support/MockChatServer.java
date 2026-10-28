package com.example.agentcore.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本地 OpenAI 兼容 Mock，用于离线验证请求链路与多智能体行为。
 *
 * <p>用 HTTP 而非 HTTPS：LangChain4j 的 OpenAI 客户端不做 HTTPS 强制校验，
 * 这一点比 Azure SDK 宽松，省掉自签证书的麻烦。</p>
 *
 * <p>脚本化响应：按请求顺序弹出预置回复；脚本耗尽后返回默认文本并带上递增序号，
 * 便于断言"到底跑了几轮"。</p>
 *
 * <p>同时支持两种传输：普通 JSON（{@code stream=false}）与 SSE 分块（{@code stream=true}）。
 * 后者用于验证流式链路——按 4 字符切片、片间 sleep 5ms，让 {@code TextDelta} 事件真的分多次到达。</p>
 */
public final class MockChatServer implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SELECTOR_MARKER = "群聊调度器";
    /** 流式切片粒度（字符数）与片间延迟，只为让增量可观测，不代表真实 token 粒度。 */
    private static final int STREAM_CHUNK_CHARS = 4;
    private static final long STREAM_CHUNK_DELAY_MILLIS = 5L;

    private final HttpServer server;
    private final ExecutorService executor;
    /** 打印每次请求命中了剧本第几条；只在排查测试替身本身时打开。 */
    private final boolean verbose;

    private final Deque<Reply> chatScript = new ArrayDeque<>();
    private final Deque<String> selectorScript = new ArrayDeque<>();
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    /** /api/tags 要报告的模型清单，由 {@link #declareModel(String)} 填。 */
    private final List<String> declaredModels = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger counter = new AtomicInteger();

    private MockChatServer(HttpServer server, ExecutorService executor, boolean verbose) {
        this.server = server;
        this.executor = executor;
        this.verbose = verbose;
    }

    public static MockChatServer start() throws IOException {
        return start(false);
    }

    public static MockChatServer start(boolean verbose) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // 流式响应会占住线程做分片推送，用可扩容的线程池，避免阻塞后续请求
        ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "mock-model");
            thread.setDaemon(true);
            return thread;
        });
        MockChatServer mock = new MockChatServer(server, executor, verbose);
        server.createContext("/", mock::handle);
        server.setExecutor(executor);
        server.start();
        return mock;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public synchronized void enqueueText(String text) {
        chatScript.add(Reply.text(text));
    }

    public synchronized void enqueueToolCall(String toolName, String argumentsJson) {
        chatScript.add(Reply.toolCall(toolName, argumentsJson));
    }

    public synchronized void enqueueSelector(String agentName) {
        selectorScript.add(agentName);
    }

    public synchronized void reset() {
        chatScript.clear();
        selectorScript.clear();
        requests.clear();
        counter.set(0);
    }

    /**
     * 声明这个 Mock 上有哪些模型，供 {@code OllamaProbe} 的 {@code /api/tags} 探测。
     *
     * <p>为什么需要：页面在跑之前会先确认"模型在不在"，Ollama 那条路走的是原生接口。
     * 不声明的话探测只能看到空列表，测试会被判定成"本地缺少模型"——挡在真正要验的编排之前。</p>
     */
    public void declareModel(String name) {
        declaredModels.add(name);
    }

    public List<String> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    public String lastRequest() {
        synchronized (requests) {
            return requests.isEmpty() ? null : requests.get(requests.size() - 1);
        }
    }

    public int requestCount() {
        synchronized (requests) {
            return requests.size();
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        // Ollama 原生探活：这条请求不是对话，绝不能记进 requests——
        // requestCount() 的语义是"调了几次模型"，掺进探测断言就废了。
        if (exchange.getRequestURI().getPath().endsWith("/api/tags")) {
            respond(exchange, 200, tagsResponse());
            return;
        }
        try (InputStream in = exchange.getRequestBody()) {
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            requests.add(body);

            if (!exchange.getRequestURI().getPath().endsWith("/chat/completions")) {
                respond(exchange, 404, "{\"error\":{\"message\":\"only /chat/completions is mocked\"}}");
                return;
            }
            boolean selector = body.contains(SELECTOR_MARKER);
            boolean streaming = body.contains("\"stream\":true") || body.contains("\"stream\" : true");

            // 只在取脚本时加锁；写响应（尤其是分片推送）放到锁外，避免长连接阻塞其他请求
            int index;
            Reply reply = null;
            String picked = null;
            synchronized (this) {
                index = counter.incrementAndGet();
                if (selector) {
                    picked = selectorScript.poll();
                } else {
                    reply = chatScript.poll();
                    if (reply == null) {
                        reply = Reply.text("（mock 默认回复 #" + index + "）");
                    }
                }
            }

            if (selector) {
                if (verbose) {
                    System.out.println("[mock] #" + index + " 调度请求（" + countRoles(body) + " 条消息）→ 选中 "
                            + (picked == null ? "default（剧本已空）" : picked));
                }
                respond(exchange, 200, textResponse(index, picked == null ? "default" : picked));
                return;
            }
            if (verbose) {
                // 把"请求内容完全不参与决策"这件事写在日志里，避免误以为是模型答非所问
                System.out.println("[mock] #" + index + " 对话请求（" + countRoles(body) + " 条消息）→ 剧本第 "
                        + index + " 条："
                        + (reply.toolName() == null ? "text「" + shorten(reply.text()) + "」" : "tool " + reply.toolName()));
            }
            if (streaming) {
                respondStream(exchange, index, reply);
            } else {
                respond(exchange, 200, reply.toolName() == null
                        ? textResponse(index, reply.text())
                        : toolCallResponse(index, reply.toolName(), reply.arguments()));
            }
        }
    }

    /** 粗略数请求里带了几条消息，只为日志可读，不做严格解析。 */
    private static int countRoles(String body) {
        int count = 0;
        int from = 0;
        while ((from = body.indexOf("\"role\"", from)) >= 0) {
            count++;
            from += 6;
        }
        return count;
    }

    private static String shorten(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 28 ? flat : flat.substring(0, 28) + "…";
    }

    private void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /**
     * SSE 分块推送，模拟 OpenAI 的 {@code stream=true}。
     *
     * <p>文本按固定字符数切片；工具调用一次性给出完整 {@code tool_calls}（真实服务会分片传
     * 参数增量，但 LangChain4j 聚合后的结果相同，这里不做无谓的复杂度）。</p>
     */
    private void respondStream(HttpExchange exchange, int index, Reply reply) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().add("Cache-Control", "no-cache");
        // 长度为 0 表示 chunked，响应体边算边发
        exchange.sendResponseHeaders(200, 0);

        try (OutputStream out = exchange.getResponseBody()) {
            if (reply.toolName() == null) {
                String text = reply.text() == null ? "" : reply.text();
                boolean first = true;
                for (int from = 0; from < text.length(); from += STREAM_CHUNK_CHARS) {
                    String slice = text.substring(from, Math.min(text.length(), from + STREAM_CHUNK_CHARS));
                    write(out, textChunk(index, slice, first));
                    first = false;
                    pause();
                }
                write(out, textChunk(index, null, false));
            } else {
                write(out, toolCallChunk(index, reply.toolName(), reply.arguments()));
                write(out, finishChunk(index, "tool_calls"));
            }
            write(out, "data: [DONE]\n\n");
            out.flush();
        }
    }

    private void write(OutputStream out, String payload) throws IOException {
        out.write(payload.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private void pause() {
        try {
            Thread.sleep(STREAM_CHUNK_DELAY_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** {@code content} 为 null 时只发结束块。 */
    private String textChunk(int index, String content, boolean first) {
        ObjectNode root = chunkBase(index);
        ObjectNode choice = root.withArray("choices").addObject();
        choice.put("index", 0);
        ObjectNode delta = choice.putObject("delta");
        if (first) {
            delta.put("role", "assistant");
        }
        if (content != null) {
            delta.put("content", content);
        }
        choice.putNull("finish_reason");
        return "data: " + root + "\n\n";
    }

    private String toolCallChunk(int index, String toolName, String argumentsJson) {
        ObjectNode root = chunkBase(index);
        ObjectNode choice = root.withArray("choices").addObject();
        choice.put("index", 0);
        ArrayNode calls = choice.putObject("delta").putArray("tool_calls");
        ObjectNode call = calls.addObject();
        call.put("index", 0);
        call.put("id", "call_" + index);
        call.put("type", "function");
        ObjectNode function = call.putObject("function");
        function.put("name", toolName);
        function.put("arguments", argumentsJson);
        choice.putNull("finish_reason");
        return "data: " + root + "\n\n";
    }

    private String finishChunk(int index, String reason) {
        ObjectNode root = chunkBase(index);
        ObjectNode choice = root.withArray("choices").addObject();
        choice.put("index", 0);
        choice.putObject("delta");
        choice.put("finish_reason", reason);
        return "data: " + root + "\n\n";
    }

    /** 伪造成 Ollama 原生 {@code /api/tags} 的响应，让就绪探测能通过。 */
    private String tagsResponse() {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode models = root.putArray("models");
        synchronized (declaredModels) {
            for (String name : declaredModels) {
                ObjectNode item = models.addObject();
                item.put("name", name);
                item.put("model", name);
            }
        }
        return root.toString();
    }

    private ObjectNode chunkBase(int index) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("id", "mock-" + index);
        root.put("object", "chat.completion.chunk");
        root.put("created", System.currentTimeMillis() / 1000L);
        root.put("model", "mock-model");
        return root;
    }

    private String textResponse(int index, String text) {
        ObjectNode root = base(index);
        ObjectNode choice = root.withArray("choices").addObject();
        choice.put("index", 0);
        ObjectNode message = choice.putObject("message");
        message.put("role", "assistant");
        message.put("content", text);
        choice.put("finish_reason", "stop");
        usage(root);
        return root.toString();
    }

    private String toolCallResponse(int index, String toolName, String argumentsJson) {
        ObjectNode root = base(index);
        ObjectNode choice = root.withArray("choices").addObject();
        choice.put("index", 0);
        ObjectNode message = choice.putObject("message");
        message.put("role", "assistant");
        message.putNull("content");
        ArrayNode calls = message.putArray("tool_calls");
        ObjectNode call = calls.addObject();
        call.put("id", "call_" + index);
        call.put("type", "function");
        ObjectNode function = call.putObject("function");
        function.put("name", toolName);
        function.put("arguments", argumentsJson);
        choice.put("finish_reason", "tool_calls");
        usage(root);
        return root.toString();
    }

    private ObjectNode base(int index) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("id", "mock-" + index);
        root.put("object", "chat.completion");
        root.put("created", System.currentTimeMillis() / 1000L);
        root.put("model", "mock-model");
        return root;
    }

    private void usage(ObjectNode root) {
        ObjectNode usage = root.putObject("usage");
        usage.put("prompt_tokens", 32);
        usage.put("completion_tokens", 16);
        usage.put("total_tokens", 48);
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    public record Reply(String text, String toolName, String arguments) {

        public static Reply text(String text) {
            return new Reply(text, null, null);
        }

        public static Reply toolCall(String toolName, String arguments) {
            return new Reply(null, toolName, arguments);
        }
    }
}
