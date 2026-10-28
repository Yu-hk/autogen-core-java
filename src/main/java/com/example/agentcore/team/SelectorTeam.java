package com.example.agentcore.team;

import com.example.agentcore.messaging.ChatMsg;
import com.example.agentcore.runtime.AgentId;
import com.example.agentcore.runtime.AgentRuntime;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 模型选择发言人的团队，对应 AutoGen 的 {@code SelectorGroupChat}。
 *
 * <p>每轮把候选名单与最近一条消息交给模型，由模型决定下一位发言人。
 * 解析失败时退回轮询，保证不会因为模型输出跑偏而卡死。</p>
 */
public class SelectorTeam extends Team {

    private static final String SELECTOR_PROMPT = "你是群聊调度器，只输出姓名，不要解释。";

    private final ChatModel selectorModel;

    public SelectorTeam(AgentRuntime runtime, List<AgentId> members, ChatModel selectorModel) {
        super(runtime, members);
        this.selectorModel = selectorModel;
    }

    @Override
    protected AgentId selectSpeaker(int round, ChatMsg last) {
        String candidates = members.stream().map(AgentId::key).collect(Collectors.joining("、"));
        String recent = last == null ? "（无）" : last.source() + ": " + last.text();
        ChatRequest request = ChatRequest.builder()
                .messages(
                        SystemMessage.from(SELECTOR_PROMPT),
                        UserMessage.from("候选成员：" + candidates + "\n最近一条消息：\n" + recent
                                + "\n\n下一位发言人姓名："))
                .build();
        String answer = selectorModel.chat(request).aiMessage().text();
        if (answer == null) {
            return roundRobin(round);
        }
        String normalized = answer.trim();
        for (AgentId member : members) {
            if (normalized.contains(member.key())) {
                return member;
            }
        }
        return roundRobin(round);
    }
}
