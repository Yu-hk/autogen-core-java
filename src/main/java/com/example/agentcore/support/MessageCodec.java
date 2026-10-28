package com.example.agentcore.support;

import com.example.agentcore.messaging.ChatMsg;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 一条消息的 JSON 读写。
 *
 * <p>快照（{@link TeamSnapshot}）和会话落库（{@code FileConversationStore}）都要把消息写到磁盘，
 * 两处格式必须一致——否则"从快照续跑"和"接着上次对话"会各自解析出一半的消息。
 * 之前这份逻辑只存在于快照里，第二处需要时就得复制粘贴，所以抽到这里。</p>
 *
 * <p>只写三个字段：类型、发言者、正文；{@link ChatMsg.Handoff} 多一个目标。
 * 消息里没有时间戳（框架的 {@code ChatMsg} 本身就不带），所以不假装有。</p>
 */
public final class MessageCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MessageCodec() {
    }

    public static ObjectNode write(ChatMsg msg) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", msg.getClass().getSimpleName());
        node.put("source", msg.source());
        if (msg instanceof ChatMsg.Handoff handoff) {
            node.put("target", handoff.target());
        }
        node.put("text", msg.text());
        return node;
    }

    public static ChatMsg read(JsonNode node) {
        String source = node.path("source").asText("");
        String text = node.path("text").asText("");
        String type = node.path("type").asText("Text");
        return switch (type) {
            case "Handoff" -> new ChatMsg.Handoff(source, node.path("target").asText(""), text);
            case "Stop" -> new ChatMsg.Stop(source, text);
            default -> new ChatMsg.Text(source, text);
        };
    }
}
