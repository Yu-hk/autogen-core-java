package com.example.agentcore.support;

import com.example.agentcore.messaging.AgentEvent;
import com.example.agentcore.messaging.ChatMsg;
import com.example.agentcore.runtime.SessionState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 会话快照的落盘与回读，对应 AutoGen 的 {@code save_state()} / {@code load_state()}。
 *
 * <p>AutoGen 的状态就是消息列表，所以"存档"等于把共享上下文与过程事件序列化，
 * 恢复时按顺序重放即可，不需要额外的状态机。</p>
 *
 * <p>两条刻意的取舍：</p>
 * <ul>
 *   <li><b>{@link AgentEvent.TextDelta} 不落盘</b>。它是渲染用的瞬时碎片，重放只会重复打印；
 *       权威文本在 {@link AgentEvent.SpeakingFinished} 与消息里。</li>
 *   <li><b>工具调用的中间态不落盘</b>。工具结果只存在于单轮内的模型请求历史里，
 *       从不进入共享上下文——这点与 AutoGen 一致。所以续跑恢复的是**对话层**，
 *       不会把上一次轮次里的工具返回值重新喂给模型。</li>
 * </ul>
 */
public final class TeamSnapshot {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int FORMAT_VERSION = 1;

    private TeamSnapshot() {
    }

    /** 落盘一份快照（team 名记为 unknown）。 */
    public static Path write(Path directory, SessionState state) throws IOException {
        return write(directory, "unknown", state);
    }

    /**
     * 落盘一份快照，通常这样调用：{@code TeamSnapshot.write(dir, "RoundRobinTeam", team.snapshot(result))}。
     *
     * @param teamName 仅写入文件便于人工辨认，回读时不参与校验
     * @return 快照文件路径，交给 {@link #read(Path)} 即可恢复
     */
    public static Path write(Path directory, String teamName, SessionState state) throws IOException {
        Files.createDirectories(directory);

        ObjectNode root = MAPPER.createObjectNode();
        root.put("version", FORMAT_VERSION);
        root.put("team", teamName);
        root.put("savedAt", state.savedAt() == 0L ? System.currentTimeMillis() : state.savedAt());
        root.put("round", state.round());
        root.put("stopReason", state.lastStopReason());
        root.put("messageCount", state.messages().size());

        ArrayNode messages = root.putArray("messages");
        for (ChatMsg msg : state.messages()) {
            messages.add(MessageCodec.write(msg));
        }

        ArrayNode events = root.putArray("events");
        int skipped = 0;
        for (AgentEvent event : state.events()) {
            ObjectNode node = writeEvent(event);
            if (node == null) {
                skipped++;
                continue;
            }
            events.add(node);
        }
        if (skipped > 0) {
            root.put("skippedStreamEvents", skipped);
        }

        Path file = directory.resolve("session-" + System.currentTimeMillis() + ".json");
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), root);
        return file;
    }

    /** 从快照文件恢复状态。 */
    public static SessionState read(Path file) throws IOException {
        JsonNode root = MAPPER.readTree(Files.readString(file));

        List<ChatMsg> messages = new ArrayList<>();
        for (JsonNode node : root.path("messages")) {
            messages.add(MessageCodec.read(node));
        }

        List<AgentEvent> events = new ArrayList<>();
        for (JsonNode node : root.path("events")) {
            AgentEvent event = readEvent(node);
            if (event != null) {
                events.add(event);
            }
        }

        return new SessionState(messages, events,
                root.path("round").asInt(0),
                root.path("stopReason").asText(""),
                root.path("savedAt").asLong(0L));
    }

    /** 取目录中最新的一份快照（按写入时间戳文件名排序）。 */
    public static Path latest(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            throw new IOException("快照目录不存在: " + directory);
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(p -> p.getFileName().toString().startsWith("session-"))
                    .max(Comparator.comparing(p -> p.getFileName().toString()))
                    .orElseThrow(() -> new IOException("目录中没有快照: " + directory));
        }
    }

    /** 快照概要，供页面列表展示，不必把整份消息读进来。 */
    public record Summary(String file, String team, int round, int messageCount,
                          String stopReason, long savedAt) {
    }

    /** 列出目录下的快照概要，最新的在前。 */
    public static List<Summary> list(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .map(TeamSnapshot::summarize)
                    .filter(java.util.Objects::nonNull)
                    .sorted(Comparator.comparing(Summary::savedAt).reversed())
                    .collect(java.util.stream.Collectors.toList());
        }
    }

    private static Summary summarize(Path file) {
        try {
            JsonNode root = MAPPER.readTree(Files.readString(file));
            return new Summary(file.getFileName().toString(),
                    root.path("team").asText(""),
                    root.path("round").asInt(0),
                    root.path("messageCount").asInt(0),
                    root.path("stopReason").asText(""),
                    root.path("savedAt").asLong(0L));
        } catch (IOException e) {
            return null;
        }
    }

    /** 返回 null 表示该事件不进快照。 */
    private static ObjectNode writeEvent(AgentEvent event) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("agent", event.agent());

        if (event instanceof AgentEvent.SpeakingStarted started) {
            node.put("type", "SpeakingStarted");
            node.put("round", started.round());
            return node;
        }
        if (event instanceof AgentEvent.SpeakingFinished finished) {
            node.put("type", "SpeakingFinished");
            node.put("round", finished.round());
            node.put("text", finished.text());
            return node;
        }
        if (event instanceof AgentEvent.TextDelta) {
            return null;
        }
        if (event instanceof AgentEvent.ToolCallRequested requested) {
            node.put("type", "ToolCallRequested");
            node.put("tool", requested.tool());
            node.put("arguments", requested.arguments());
            return node;
        }
        if (event instanceof AgentEvent.ToolExecuted executed) {
            node.put("type", "ToolExecuted");
            node.put("tool", executed.tool());
            node.put("result", executed.result());
            node.put("millis", executed.millis());
            return node;
        }
        return null;
    }

    private static AgentEvent readEvent(JsonNode node) {
        String agent = node.path("agent").asText("");
        String type = node.path("type").asText("");
        return switch (type) {
            case "SpeakingStarted" -> new AgentEvent.SpeakingStarted(agent, node.path("round").asInt(0));
            case "SpeakingFinished" -> new AgentEvent.SpeakingFinished(agent,
                    node.path("round").asInt(0), node.path("text").asText(""));
            case "ToolCallRequested" -> new AgentEvent.ToolCallRequested(agent,
                    node.path("tool").asText(""), node.path("arguments").asText(""));
            case "ToolExecuted" -> new AgentEvent.ToolExecuted(agent,
                    node.path("tool").asText(""), node.path("result").asText(""),
                    node.path("millis").asLong(0L));
            default -> null;
        };
    }
}
