package com.example.agentcore.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 推理模型的思考块清理。
 *
 * <p>起因：内网换 deepseek-r1:8b 实测，Ollama 的 /v1 流式会把思考过程混进正文，
 * 回答里夹着结束标签，页面上直接显示成一段元思考。</p>
 */
class ThinkingStripTest {

    /** 用拼接写标签，避免源码里出现容易被编辑器改写全角的尖括号组合。 */
    private static final String END = "<" + "/think>";
    private static final String OPEN = "<" + "think" + ">";

    @Test
    void keepsAnswerOnly() {
        assertEquals("结论在这里", ChatAgent.stripThinking("先想一下……\n然后呢\n" + END + "结论在这里"));
    }

    @Test
    void leavesPlainTextUntouched() {
        assertEquals("普通模型的回答", ChatAgent.stripThinking("普通模型的回答"));
    }

    @Test
    void truncatedThinkingIsNotAnAnswer() {
        assertEquals("", ChatAgent.stripThinking(OPEN + "思考到一半被截断了"));
    }

    @Test
    void nullAndBlankAreSafe() {
        assertEquals("", ChatAgent.stripThinking(null));
        assertEquals("", ChatAgent.stripThinking("   "));
        assertEquals("", ChatAgent.stripThinking(END));
    }
}
