package com.example.agentcore.web;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 把用户的一句话问题路由到合适的协作方式。
 *
 * <p>此前的页面要求用户先在五个场景卡片里选一个——那是演示者视角，不是使用者视角。
 * 真实用法里用户只该说"帮我规划长沙一日游"，至于是几棒、挂哪些工具、要不要评审角色，
 * 是系统的事。这个类就是那层判断。</p>
 *
 * <p><b>为什么用规则而不是让模型选：</b>路由本身要快（几十毫秒）且要可解释——用户会问
 * "凭什么给我派三个人"。规则命中哪个词可以直接说出来，落到哪个词也能事后复查；
 * 用模型选则多一次调用、多一份延迟，还会给出编造的理由。规则误判的代价也低：
 * 所有路径都能回答，只是形式不同，不会答不上来。</p>
 *
 * <p>判断顺序：先看是不是"动手写代码"（产物在磁盘上、要能跑）；再比旅游域与技术议题的命中词数，
 * 谁强走谁；旅游域再按"要查几类信息"决定单 agent 还是两棒分工；都不命中就走 {@code general} 兜底。</p>
 */
public final class Router {

    /** 前端传这个值表示"别问我，你自己判断"。 */
    public static final String AUTO = "auto";

    /**
     * 一次路由的结论。
     *
     * @param scenarioId 落到哪个 {@link Scenario}
     * @param domain     所属域：{@code travel} / {@code tech} / {@code code} / {@code none}。多轮追问靠它判断"要不要换人"
     * @param hits       本轮命中的域词个数。换域要求它够强，见 {@link #decideFollowUp}
     * @param reason     给人看的一句话理由，会出现在页面和日志里
     */
    public record Route(String scenarioId, String domain, int hits, String reason) {

        public Scenario scenario() {
            return Scenario.byId(scenarioId);
        }
    }

    private static final List<String> TRAVEL = List.of(
            "旅游", "旅行", "一日游", "两日游", "三日游", "几日游", "行程", "攻略",
            "景点", "景区", "古镇", "公园", "博物馆", "寺庙", "打卡",
            "坐标", "经纬度", "地图", "多远", "怎么坐", "怎么去", "怎么走", "路线",
            "地铁", "公交", "打车", "自驾", "高铁", "机场", "车程",
            "附近", "周边", "美食", "餐馆", "餐厅", "小吃", "吃饭",
            "住宿", "酒店", "民宿", "门票", "开放时间");

    /**
     * 技术议题词。
     *
     * <p>不含"路线""方案"这类会被旅游问题借走的词——"设计一条长沙两日游路线"必须判成旅游。
     * 域归属靠的是不可共用的词，不是靠数量硬压。</p>
     */
    private static final List<String> TECH = List.of(
            "方案", "设计", "架构", "重构", "优化", "并发", "性能", "吞吐", "容量",
            "百万级", "千万级", "选型", "排查", "根因", "实现", "接口", "数据库",
            "缓存", "事务", "部署", "灰度", "回滚", "幂等", "限流", "分片", "索引");

    /** 出现这些词说明用户要的是"有人替我把关"，而不是"有人替我写"。 */
    private static final List<String> REVIEW = List.of(
            "评审", "风险", "能不能上线", "能上线吗", "能直接上线", "上线", "该不该",
            "把关", "评估", "需求", "帮我做", "帮我设计", "要不要", "可行吗");

    /**
     * 写代码的信号词：要的东西在磁盘上、能跑起来，而不是一段解释。
     *
     * <p>刻意和 {@link #TECH} 分开：技术议题要的是<b>判断</b>（该不该这么设计、风险在哪），
     * 编码任务要的是<b>产物</b>（文件 + exitCode=0）。同一句话里两者都出现时，
     * 靠命中数比强弱——"写一个 LRU 缓存并跑通单测"里 {@code 写一个}、{@code 单测}
     * 两个信号压过 {@code 缓存} 一个，该动手而不是该评审。</p>
     *
     * <p>{@code 报错}/{@code 异常}/{@code 堆栈} 收进来，是因为"这段报错怎么解决"只有真去看代码、
     * 真跑一遍才能答对，交给不挂工具的助手只会得到一段泛泛的可能原因。</p>
     */
    private static final List<String> CODE = List.of(
            "写代码", "写一个", "写个", "写一段", "实现一个", "改代码", "加个", "加一个",
            "代码", "函数", "方法", "类", "单元测试", "单测", "测试用例", "编译", "跑通",
            "运行一下", "跑一下", "脚本", "算法", "报错", "异常", "堆栈", "bug", "调试",
            "修复", "修好", "改到", "改一下", "修 bug", "跑不起来", "编译不过",
            "接口实现", "crud", "leetcode");

    /**
     * 出现这些扩展名就等于"用户指的是一份真实源码"。
     *
     * <p>实测漏判：{@code "src/Calc.java 里的 average 算错了……改到输出 PASS"}
     * 一个域词都没命中——它既没说"写代码"也没说"实现"，结果被判成技术议题，派了架构师
     * 和评审人去讨论"该怎么修"，一个字都没改到磁盘上。<b>点名到文件就是要在那个文件上动手</b>，
     * 这条判断比任何关键词都硬。</p>
     */
    private static final java.util.regex.Pattern SOURCE_FILE = java.util.regex.Pattern.compile(
            "\\.(java|kt|py|ts|tsx|js|jsx|go|rs|cs|cpp|cc|c|h|hpp|rb|php|swift|scala|sh|sql|vue)\\b");

    /**
     * 上面那批里"明确要动手"的部分。
     *
     * <p>分强弱是因为两类词的歧义程度不一样：{@code 缓存}/{@code 代码} 出现在
     * "缓存该不该用"这种判断题里也正常，而 {@code 写一个}/{@code 单测}/{@code 编译}
     * 几乎只在真要做东西时出现。弱信号要两个以上才算，强信号一个就够——
     * 不然"写一个接口，支持并发 2000"会被 {@code 接口}/{@code 并发} 两个技术词
     * 抢去走方案评审，用户等了半天只拿到一份设计。</p>
     */
    private static final List<String> CODE_STRONG = List.of(
            "写代码", "写一个", "写个", "写一段", "实现一个", "改代码", "加个", "加一个",
            "单元测试", "单测", "测试用例", "编译", "跑通", "跑一下", "运行一下", "调试",
            "报错", "堆栈", "bug",
            // 数据库是"动手"而不是"讨论"的信号：说到建表/插数据/查出来，用户要的是结果，
            // 不是一段 SQL 草稿。实测"在沙箱建一张 employee 表…查出来"只命中一个「实现」，
            // 被判成方案打磨，两轮下来 0 次工具调用、库里什么都没建。
            "建表", "建一张", "创建表", "插数据", "插入数据", "插几条", "查出来",
            "create table", "insert into", "create database",
            "数据库工具", "db_execute", "db_query", "db_schema", "沙箱数据库");

    /**
     * 诉求类别。一条问题里命中两类以上，说明要挨个查多组数据——实测这就是单 agent
     * 容易只答其中一段的地方，改走"先查后写"两棒。
     */
    private static final List<List<String>> ASKS = List.of(
            List.of("坐标", "经纬度", "位置", "在哪"),
            List.of("美食", "餐馆", "餐厅", "吃饭", "小吃", "吃的"),
            List.of("地铁", "公交", "路线", "怎么坐", "怎么去", "多远", "打车", "票价", "车程"),
            List.of("开放时间", "游玩时长", "多久", "几点"),
            List.of("住宿", "酒店", "民宿"));

    private Router() {
    }

    /** 判断一条任务该走哪条路。空任务也返回结论，调用方不必先判空。 */
    public static Route decide(String task) {
        String text = normalize(task);
        if (text.isEmpty()) {
            return new Route("general", "none", 0, "没有识别到问题内容");
        }

        List<String> travelHits = hits(text, TRAVEL);
        List<String> techHits = hits(text, TECH);
        List<String> codeHits = hits(text, CODE);

        // 先看"要不要动手写"：产物在磁盘上、要能跑起来，这件事别的场景都做不了
        if (isCoding(text, codeHits, travelHits)) {
            // 触发词可能只来自强信号（"建一张""写个"），这时 codeHits 是空的——
            // 直接 first(codeHits) 会让理由变成"代码任务（）"，页面上看起来像坏了
            List<String> strongHits = hits(text, CODE_STRONG);
            String trigger = first(strongHits.isEmpty() ? codeHits : strongHits);
            // hits 至少记 1：0 会让多轮换域判定以为"信号太弱"，明明已经判成代码任务了
            return new Route("coding", "code", Math.max(1, codeHits.size()),
                    "代码任务（" + trigger + "）→ 编码工程师带文件与命令工具，写完自己编译验证");
        }

        if (travelHits.size() > techHits.size()) {
            int asks = askClasses(text);
            if (asks >= 2) {
                return new Route("travel-pipeline", "travel", travelHits.size(),
                        "旅游域（" + first(travelHits) + "），要查 " + asks + " 类信息 → 先查素材再写终稿");
            }
            return new Route("travel", "travel", travelHits.size(),
                    "旅游域（" + first(travelHits) + "），单个诉求 → 一个规划师带地图工具查");
        }

        if (!techHits.isEmpty()) {
            List<String> reviewHits = hits(text, REVIEW);
            if (!reviewHits.isEmpty()) {
                return new Route("review", "tech", techHits.size() + reviewHits.size(),
                        "技术议题（" + first(reviewHits) + "）→ 产品拆解、工程估算、评审把关过一遍");
            }
            return new Route("refine", "tech", techHits.size(),
                    "技术议题（" + first(techHits) + "）→ 架构师起草、评审人追问，两轮收敛");
        }

        return new Route("general", "none", 0, "没有明确的旅游或技术信号 → 单个助手直接回答");
    }

    /**
     * 多轮对话里的一轮：沿用上一轮的装配，还是换一套。
     *
     * <p><b>为什么不能每轮都重新 {@link #decide}</b>：追问常常没有域词——"便宜点的呢""
     * 第二个呢""这些地方怎么去"单独拎出来什么也不命中，重新判就会掉进 {@code general}，
     * 上一轮挂的工具和角色全丢。多轮的前提是"默认还是刚才那个人在答"。</p>
     *
     * <p><b>什么时候才换</b>：新一轮明确落在另一个域，且命中够强（≥2 个词）。用户第一轮问
     * 旅游、第二轮突然问"顺便看下这个导出方案怎么设计"，换到技术侧是对的；
     * 但如果只蹦出一个"方案"两个字就换人，后面还得再换回来，来回抖比不换更糟。</p>
     */
    public static Route decideFollowUp(String currentScenarioId, String task) {
        Route fresh = decide(task);
        String current = domainOf(currentScenarioId);
        String title = Scenario.byId(currentScenarioId).title();

        if (fresh.domain().equals(current) || "none".equals(fresh.domain())) {
            String why = "none".equals(fresh.domain())
                    ? "本轮没有更强的换域信号"
                    : "本轮仍属" + domainLabel(current) + "域";
            return new Route(currentScenarioId, current, fresh.hits(),
                    "沿用「" + title + "」· " + why);
        }

        if (fresh.hits() < 2) {
            return new Route(currentScenarioId, current, fresh.hits(),
                    "沿用「" + title + "」· 本轮只命中 " + fresh.hits() + " 个换域词，不足以切换");
        }

        return new Route(fresh.scenarioId(), fresh.domain(), fresh.hits(),
                "换到「" + fresh.scenario().title() + "」· " + fresh.reason());
    }

    /** 场景属于哪个域。多轮沿用/换域、以及测试都靠它。 */
    public static String domainOf(String scenarioId) {
        return switch (scenarioId == null ? "" : scenarioId) {
            case "travel", "travel-pipeline" -> "travel";
            case "review", "refine", "pick" -> "tech";
            case "coding" -> "code";
            default -> "none";
        };
    }

    private static String domainLabel(String domain) {
        return switch (domain) {
            case "travel" -> "旅游";
            case "tech" -> "技术";
            case "code" -> "代码";
            default -> "通用";
        };
    }

    /**
     * 这条问题是不是该动手写代码。
     *
     * <p>判断次序：点名到源码文件就直接算（用户已经指着文件说"改这里"）；有明确的动手词
     * （{@code 写一个}/{@code 建表}/{@code 插入数据}/{@code 查出来}）也直接算——强信号不该
     * 因为"一个代码域词都没命中"就被否掉，实测数据库任务就是栽在这一条上：
     * "在沙箱建一张表…查出来"没有出现「代码」「函数」这类词，被当成纯讨论；
     * 一点都不沾代码就不用往下看；旅游信号更强就让旅游（"写个脚本规划两日游"里用户真正
     * 想要的是行程）；只有弱词（{@code 代码}/{@code 报错}）时，要么凑够两个，要么技术域
     * 完全没命中——否则"这段代码的缓存策略怎么优化"会被拽去写代码，而用户要的是一句判断。</p>
     */
    private static boolean isCoding(String text, List<String> codeHits, List<String> travelHits) {
        boolean strong = !hits(text, CODE_STRONG).isEmpty();
        if (SOURCE_FILE.matcher(text).find() && codeHits.size() >= travelHits.size()) {
            return true;
        }
        // 强信号只要不输给旅游域就够，不要求先命中代码域的词
        if (strong && codeHits.size() >= travelHits.size()) {
            return true;
        }
        if (codeHits.isEmpty() || codeHits.size() < travelHits.size()) {
            return false;
        }
        return codeHits.size() >= 2;
    }

    /** 命中了几个诉求类别。 */
    public static int askClasses(String task) {
        String text = normalize(task);
        int count = 0;
        for (List<String> group : ASKS) {
            if (!hits(text, group).isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private static List<String> hits(String text, List<String> words) {
        Set<String> found = new LinkedHashSet<>();
        for (String word : words) {
            if (matches(text, word)) {
                found.add(word);
            }
        }
        return new ArrayList<>(found);
    }

    /**
     * 词表命中判断。
     *
     * <p>{@link #normalize} 会把所有空白抹掉（{@code "CREATE TABLE"} → {@code "createtable"}），
     * 所以带空格的词（{@code create table}、{@code insert into}、{@code 跑 sql}）按原样永远匹配不上。
     * 这里把词也压一遍再比，词表就能写成正常可读的样子。</p>
     */
    private static boolean matches(String text, String word) {
        if (word == null || word.isEmpty()) {
            return false;
        }
        if (text.contains(word)) {
            return true;
        }
        String compact = word.replaceAll("\\s+", "");
        return !compact.equals(word) && text.contains(compact);
    }

    private static String first(List<String> words) {
        return words.isEmpty() ? "" : words.get(0);
    }

    /** 去空白、转小写：中文不分大小写，但 Git / SQL 这类词要能命中。 */
    private static String normalize(String task) {
        return task == null ? "" : task.replaceAll("\\s+", "").toLowerCase();
    }
}
