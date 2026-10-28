package com.example.agentcore;

import com.example.agentcore.web.Router;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自动路由的规则测试。
 *
 * <p>这些断言写的是"用户会怎么说话"，不是"代码怎么实现的"。加词、调阈值时先跑这里，
 * 能立刻看出有没有把某类问法挤出正确的路。路由错了不会报错——只会派错人、多花几倍时间，
 * 所以它是少数值得逐条钉死的判断逻辑。</p>
 */
class RouterTest {

    private static String route(String task) {
        return Router.decide(task).scenarioId();
    }

    @Test
    @DisplayName("单个旅游诉求：一个规划师带工具就够，不必分两棒")
    void singleTravelAskGoesToSingleAgent() {
        assertEquals("travel", route("岳麓山的经纬度是多少"));
        assertEquals("travel", route("橘子洲附近有什么好吃的"));
        assertEquals("travel", route("帮我规划长沙两日游"));
    }

    @Test
    @DisplayName("旅游问题里要查两类以上信息：先查后写，避免只答其中一段")
    void multiAskTravelGoesToPipeline() {
        String ask = "帮我规划长沙一日游：橘子洲、岳麓山、月亮岛三个景点各给经纬度坐标；"
                + "再推荐橘子洲附近 3 家餐馆；最后说明从长沙南站到橘子洲的公交时长和票价。";
        Router.Route decision = Router.decide(ask);
        assertEquals("travel-pipeline", decision.scenarioId());
        assertTrue(decision.reason().contains("类信息"), "理由里应说明为什么要分两棒：" + decision.reason());
        assertEquals(3, Router.askClasses(ask), "位置 / 餐饮 / 交通 三类诉求");
    }

    @Test
    @DisplayName("带「设计」的旅游问题仍然是旅游问题：域归属看不可共用的词")
    void travelWinsOverTechWords() {
        assertEquals("travel-pipeline", route("设计一条长沙两日游路线，包含美食和地铁"));
    }

    @Test
    @DisplayName("技术议题分两种：要人把关走三角色，只要初稿走打磨")
    void techQuestionsSplitByIntent() {
        assertEquals("review", route("帮我做一个订单导出功能，要求支持百万级数据"));
        assertEquals("review", route("这个导出方案能直接上线吗？"));
        assertEquals("refine", route("设计一个百万级订单的导出方案"));
        assertEquals("refine", route("这个接口并发 2000 怎么优化"));
    }

    @Test
    @DisplayName("日常提问走兜底路径，不能被硬塞进旅游或评审")
    void everydayQuestionsFallBackToGeneral() {
        assertEquals("general", route("怎么给 Git 提交改作者信息"));
        assertEquals("general", route("你好"));
        assertEquals("general", route(""));
        assertEquals("general", route(null));
    }

    @Test
    @DisplayName("理由必须能说清楚，不能只给一个编号")
    void reasonIsHumanReadable() {
        Router.Route decision = Router.decide("橘子洲的经纬度是多少");
        assertFalse(decision.reason().isBlank());
        assertTrue(decision.reason().contains("经纬度"), "理由应指出命中了哪个词：" + decision.reason());
        // 结论要能真的构造出场景，不能是个不存在的 id
        assertEquals("travel", decision.scenario().id());
    }

    // ---------- 多轮追问 ----------

    @Test
    @DisplayName("追问里没有域词时沿用上一轮，不能掉回兜底路径")
    void followUpWithoutDomainWordsKeepsCurrentScenario() {
        // "那第二个呢" 单看什么也不命中；如果重新 decide 就成了 general，地图工具全丢
        assertEquals("general", Router.decide("那第二个呢").scenarioId(),
                "前提：单看这句会落兜底，这正是不能重新路由的理由");
        Router.Route kept = Router.decideFollowUp("travel", "那第二个呢");
        assertEquals("travel", kept.scenarioId());
        assertTrue(kept.reason().contains("沿用"), "应说明是沿用：" + kept.reason());
    }

    @Test
    @DisplayName("同域的追问继续沿用，不要因为换了说法就换人")
    void sameDomainFollowUpStays() {
        Router.Route kept = Router.decideFollowUp("travel", "再推荐一家餐馆");
        assertEquals("travel", kept.scenarioId());

        // 上一轮是两棒，追问也还是两棒：用户仍在问同类问题
        Router.Route stillPipeline = Router.decideFollowUp("travel-pipeline", "那公交要多少钱");
        assertEquals("travel-pipeline", stillPipeline.scenarioId());
    }

    @Test
    @DisplayName("明确换到另一个域时才切，且要够强（≥2 个命中词）")
    void switchingDomainRequiresStrongSignal() {
        Router.Route switched = Router.decideFollowUp("travel", "帮我设计一个百万级订单的导出方案");
        assertTrue(switched.scenarioId().startsWith("review") || switched.scenarioId().startsWith("refine"),
                "换到技术侧，实际：" + switched.scenarioId());
        assertTrue(switched.reason().contains("换到") || switched.reason().contains("沿用"),
                "理由要说清楚做了什么决定：" + switched.reason());

        // 只蹦出一个弱信号词，不切：来回抖比不换更糟
        Router.Route weak = Router.decideFollowUp("travel", "这个方案呢");
        assertEquals("travel", weak.scenarioId(), "一个弱信号不足以换域");
    }

    @Test
    @DisplayName("通用会话里的追问也沿用通用，不会被单个词拽去旅游")
    void generalConversationStaysGeneral() {
        Router.Route kept = Router.decideFollowUp("general", "那再详细说说");
        assertEquals("general", kept.scenarioId());
    }

    @Test
    @DisplayName("点名到源码文件的修复请求必须动手，不能派评审去讨论怎么修")
    void filePathItselfIsACodingSignal() {
        String fix = "src/Calc.java 里的 average 算错了，现在跑 src/Main.java 会输出 FAIL。"
                + "先读代码定位原因，改到输出 PASS。只改实现，不要改 Main.java 里的期望值。";
        assertEquals("coding", route(fix),
                "这句话只命中一个「实现」，靠词表判会掉进方案打磨，实际一个字都改不到磁盘上");
    }

    @Test
    @DisplayName("要动手写的东西走编码场景：产物在磁盘上，不是一段解释")
    void codingTasksGoToCoding() {
        assertEquals("coding", route("用 Java 写一个线程安全的 LRU 缓存，写单测并跑通"));
        assertEquals("coding", route("帮我写个 Python 脚本把 csv 转成 json"));
        assertEquals("coding", route("这段报错怎么解决：NullPointerException at App.main"));
        assertEquals("coding", route("写一个接口，支持并发 2000"),
                "「写一个」这种明确动手信号，不该被「接口」「并发」两个技术词抢去写方案");
        // 数据库任务是"要结果"不是"要方案"：建表、插数据、查出来都得真的落到库里
        assertEquals("coding", route("在沙箱数据库里建一张 employee 表，插入 5 条数据，按部门统计平均工资"));
        assertEquals("coding", route("帮我把 CREATE TABLE 那段 SQL 跑一遍看看对不对"));

        // 触发词只来自强信号时，理由里不能出现空括号（页面上会显示"代码任务（）"），
        // 信号数也不能是 0——0 会让多轮换域判定以为"太弱"，明明已经判成代码任务了
        Router.Route db = Router.decide("在沙箱数据库里建一张 employee 表，插入 5 条数据");
        assertEquals("coding", db.scenarioId());
        assertTrue(db.reason().contains("建一张"), "理由要写清是哪个词触发的：" + db.reason());
        assertTrue(db.hits() >= 1, "信号数不该是 0：" + db.hits());
    }

    @Test
    @DisplayName("只是在讨论数据库设计时不要去连库")
    void databaseDesignTalkStaysOutOfCoding() {
        assertEquals("refine", route("这个订单表的索引怎么设计"),
                "讨论索引设计是要一句判断，不是让 agent 去建表");
    }

    @Test
    @DisplayName("技术判断类问题仍然走评审/打磨，不能被编码场景抢走")
    void techJudgementStaysOutOfCoding() {
        // 有「代码」「缓存」但没有动手信号：用户要的是判断，不是文件
        assertEquals("refine", route("这个接口并发 2000 怎么优化"));
        assertEquals("review", route("帮我做一个订单导出功能，要求支持百万级数据"));
        assertEquals("refine", route("设计一个百万级订单的导出方案"));
    }

    @Test
    @DisplayName("编码场景属于自己的域，追问时不会掉回兜底")
    void codingHasItsOwnDomain() {
        assertEquals("code", Router.domainOf("coding"));
        Router.Route kept = Router.decideFollowUp("coding", "再给它加上过期时间");
        assertEquals("coding", kept.scenarioId(), "追问应继续由编码工程师处理，工具不能丢");
    }

    @Test
    @DisplayName("域归属映射要覆盖所有场景")
    void domainMappingCoversAllScenarios() {
        assertEquals("travel", Router.domainOf("travel"));
        assertEquals("travel", Router.domainOf("travel-pipeline"));
        assertEquals("tech", Router.domainOf("review"));
        assertEquals("tech", Router.domainOf("refine"));
        assertEquals("none", Router.domainOf("general"));
        assertEquals("none", Router.domainOf(null));
    }
}
