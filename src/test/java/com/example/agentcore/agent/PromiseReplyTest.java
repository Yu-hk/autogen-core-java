package com.example.agentcore.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「只写计划不干活」的判定。
 *
 * <p>回归的是实测那一轮：qwen2.5:3b 调了一次 list_files，答 546 字，以
 * 「接下来，我将分析 frequencyAnaly 目录下的内容」收尾就结束了，pom.xml 一个字没读。
 * 误判的代价比漏判高——会把一条本来完整的回答再喂回去重做一遍，白烧几分钟，
 * 所以下面既测"该抓的抓到"，也测"正常的结论不能被当成计划"。</p>
 */
class PromiseReplyTest {

    @Test
    void detectsTheExactCaseFromTheScreenshot() {
        String reply = "项目结构如下：\n\n- 根目录下有 README.md 文件\n- 根目录下有 frequencyAnaly 目录\n\n"
                + "根据 pom.xml 文件，这个项目使用了 Maven 构建工具。"
                + "接下来，我将分析 frequencyAnaly 目录下的内容，以了解项目的具体架构和实现细节。";
        assertTrue(ChatAgent.endsWithPromise(reply), "句尾的「接下来，我将…」应被识别");
    }

    @Test
    void detectsBarePromise() {
        assertTrue(ChatAgent.endsWithPromise("接下来我将读取配置文件。"));
        assertTrue(ChatAgent.endsWithPromise("下一步我会检查依赖版本"));
    }

    @Test
    void detectsTheSecondPhrasing() {
        // 加了提示词以后模型换了个说法，光靠"接下来我"这组固定词会漏掉
        assertTrue(ChatAgent.endsWithPromise("我会使用 `search_code` 来查找入口类和配置文件。"));
        assertTrue(ChatAgent.endsWithPromise(
                "接下来，我将搜索入口类和配置文件以确定项目的架构和分层结构。\n"
                        + "我会使用 `search_code` 来查找入口类和配置文件。"));
    }

    @Test
    void factsOnlyIsNotAPromise() {
        String reply = "这个工程用的是 Spring Boot 2.1.7.RELEASE，依赖 OR-Tools 9.11.4210，"
                + "数据库同时接了 Oracle 和达梦，打包方式是 Maven 单模块。";
        assertFalse(ChatAgent.endsWithPromise(reply));
    }

    @Test
    void adviceToUserIsNotAPromise() {
        // 说的是"你下一步该干什么"，没有第一人称，不能当成模型自己要去做
        assertFalse(ChatAgent.endsWithPromise("结论：这是 Spring Boot 2.1.7 单模块工程。建议你下一步先看调度模块。"));
        // 有"我"但只是表态，没有动作动词
        assertFalse(ChatAgent.endsWithPromise("所以我会建议用 OR-Tools 求解，不建议自己写调度。"));
    }

    @Test
    void promiseEarlierInTheTextDoesNotCount() {
        // 计划句出现在中间、结尾是实打实的结论 —— 这种不能触发补做
        String reply = "接下来我将分析 pom.xml。分析结果：依赖 Spring Boot 2.1.7.RELEASE 和 OR-Tools。";
        assertFalse(ChatAgent.endsWithPromise(reply));
    }

    @Test
    void blankInputIsSafe() {
        assertFalse(ChatAgent.endsWithPromise(null));
        assertFalse(ChatAgent.endsWithPromise(""));
        assertFalse(ChatAgent.endsWithPromise("   \n  "));
    }
}
