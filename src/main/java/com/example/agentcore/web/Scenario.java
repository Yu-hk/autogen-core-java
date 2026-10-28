package com.example.agentcore.web;

import java.util.List;

/**
 * 页面可选的演示场景：成员构成 + 默认任务 + 终止条件。
 *
 * <p>场景只描述"谁参与、各自被要求做什么"，回答内容完全由模型生成。此前这里还挂着一份离线剧本，
 * 用于 Mock 模式回放——那套剧本已经删掉：页面只跑真实模型，剧本搬到测试里当测试替身。</p>
 */
public record Scenario(
        String id,
        String title,
        String description,
        String task,
        List<Member> members,
        /** true 表示用 {@code SelectorTeam} 让模型挑下一位发言人，false 为固定轮询。 */
        boolean selector,
        String stopWord,
        int maxRounds) {

    /**
     * 一位团队成员：key 是发言人名字，system 是角色设定，toolset 表示挂哪套工具，
     * {@code model} 表示用哪套模型（{@code null} = 主模型，{@code summarizer} = 第二套模型）。
     *
     * <p>{@code toolset} 取值：{@code none} / {@code calculator} / {@code travel} / {@code coding}。
     * 用字符串而不是布尔，是因为工具从"有/没有"变成了"哪一套"。
     * {@code none} 也不等于"什么都看不到"：用户选了工作目录时，运行时会额外挂一副只读的
     * 文件工具给这些成员（详见 {@link #general()}）。</p>
     */
    public record Member(String key, String role, String system, String toolset, String model) {

        /** 单模型场景的写法：模型一律取主模型。 */
        public Member(String key, String role, String system, String toolset) {
            this(key, role, system, toolset, null);
        }

        /** 给接口和页面用的兼容字段：这个成员是否挂了工具。 */
        public boolean hasTools() {
            return toolset != null && !"none".equals(toolset) && !toolset.isBlank();
        }

        /** 这个成员是否走第二套（总结）模型。 */
        public boolean usesSummarizer() {
            return "summarizer".equals(model);
        }
    }

    public Scenario {
        members = List.copyOf(members);
    }

    public static List<Scenario> all() {
        return List.of(review(), refine(), pickSpeaker(), travel(), travelPipeline(), coding(), general());
    }

    public static Scenario byId(String id) {
        return all().stream()
                .filter(s -> s.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知场景: " + id));
    }

    /** 三角色评审：产品拆解 → 工程估算（带工具）→ 评审把关。 */
    private static Scenario review() {
        return new Scenario(
                "review",
                "需求评审",
                "产品、工程、评审三角色轮询，工程侧会调用复杂度评估工具。",
                "帮我做一个订单导出功能，要求支持百万级数据。",
                List.of(
                        new Member("planner", "产品经理",
                                "你是产品经理，把需求拆成 3-4 条可执行任务，总字数不超过 80 字，不写实现细节。",
                                "none"),
                        new Member("coder", "后端工程师",
                                "你是后端工程师。先调用一次 estimateComplexity 拿到复杂度，再给出不超过 120 字的"
                                        + "方案要点（异步任务表、游标分页、限流、分片合并）。"
                                        + "只调用一次工具，不要重复调用，不要写完整代码。",
                                "calculator"),
                        new Member("reviewer", "评审人",
                                "你是评审人，只指出 1-2 个缺口，不超过 60 字，不写实现。"
                                        + "方案可接受时在回复末尾单独一行写 APPROVE。",
                                "none")),
                false,
                "APPROVE",
                4);
    }

    /** 双人打磨：生成者与评审者来回两轮，展示 reflection 模式。 */
    private static Scenario refine() {
        return new Scenario(
                "refine",
                "方案打磨",
                "架构师与评审人一问一答，靠多轮反馈收敛，展示 reflection 循环。",
                "设计一个百万级订单的导出方案。",
                List.of(
                        new Member("architect", "架构师",
                                "你是架构师，第一轮给初版方案（可以粗糙），收到批评后直接给修订版，"
                                        + "不要重复解释已经改过的内容。用中文回答，控制在 120 字内。", "none"),
                        new Member("reviewer", "评审人",
                                "你是评审人，只指出 1-2 个缺口，不超过 60 字，不写实现。"
                                        + "方案可接受时在回复末尾单独一行写 APPROVE。",
                                "none")),
                false,
                "APPROVE",
                4);
    }

    /** 由模型决定下一位发言人，展示 SelectorTeam。 */
    private static Scenario pickSpeaker() {
        return new Scenario(
                "pick",
                "模型选人",
                "每轮由模型根据上下文挑下一位发言人，而不是固定顺序。",
                "这个导出方案能直接上线吗？",
                List.of(
                        new Member("planner", "产品经理",
                                "你是产品经理，从需求完整性角度回答，每次只讲一个新问题，不超过 80 字。", "none"),
                        new Member("reviewer", "评审人",
                                "你是评审人，从风险角度回答，不超过 60 字；"
                                        + "方案可接受时在回复末尾单独一行写 APPROVE。", "none")),
                true,
                "APPROVE",
                3);
    }

    /**
     * 旅游规划：单 agent + 7 个旅游工具，对应原 Python 项目的「旅游规划机器人」。
     *
     * <p>maxRounds=3：本地小模型常会先说一句"我接下来查坐标"就结束本轮，一轮不够把它拉回正题。
     * stopWord 用一个正常文本里不可能出现的串，等价于"只靠轮次结束"。</p>
     */
    private static Scenario travel() {
        return new Scenario(
                "travel",
                "旅游规划",
                "单个规划师 agent 挂 7 个旅游工具（地图地理编码/周边/路线/静态地图、必应搜索、景点抓取、笔记落盘）。"
                        + "四个地图工具需要先在 agent.properties 里配好 Key（tool.map.provider 选 amap 或 baidu），"
                        + "不配也能跑，只是这四个工具会返回失败原因。",
                "帮我规划长沙两日游。先给我 3 个景点的名称、简介、开放时间、建议游玩时长，"
                        + "以及每个景点的经纬度坐标。",
                List.of(new Member("travel", "旅游规划师",
                        com.example.agentcore.tool.travel.TravelPrompt.SYSTEM, "travel")),
                false,
                "___NEVER_MATCH___",
                // 只有一个 agent，多轮就是让它把同一个问题重答一遍：实测第 2 轮会丢掉工具
                // 拿到的坐标，自己另编一个（112.9174 → 113.0089）。而一次 handle 内部本来就有
                // 工具循环（最多 12 次工具调用），单轮足够查完多组数据，所以这里固定 1 轮。
                1);
    }

    /**
     * 通用问答：兜底路径，一个助手、一轮结束。
     *
     * <p>前五个场景都是"演示某个协作模式"用的，各有明确主题；用户随手问一句
     * "怎么给 Git 提交改作者信息"时，它们都不合适——把旅游工具挂上去是错的，
     * 用三角色评审也是错的。<b>没有这条兜底路径，"用户只提问"就不成立</b>，
     * 因为总有问题落不进任何场景。</p>
     *
     * <p>提示词刻意保持通用：不承诺联网、不假装知道时效性信息、不知道就说不知道。
     * 这比让它编一个像样的答案重要。</p>
     *
     * <p><b>工具集仍是 none，但运行时会被补上"只读眼睛"</b>：这条兜底路径承接了
     * "分析一下这个项目"。这句话不含任何技术域词，路由必然落到这里；如果这里一个工具都不挂，
     * 模型能给出的最诚实回答就是"我看不到你的项目"——而用户明明已经在页面上选好了目录。
     * 所以用户在页面上选了目录时，{@code AgentWebServer} 会给所有"本来没工具"的成员挂上
     * 4 个只读文件工具（见 {@link com.example.agentcore.tool.code.CodePrompt#readOnlyHint}）。
     * 这里不声明 readonly，是因为"能不能看"取决于这一次运行有没有选目录，不是场景的固有属性。</p>
     */
    private static Scenario general() {
        return new Scenario(
                "general",
                "直接回答",
                "通用助手。没有明确领域信号的问题走这条；用户在页面上选了工作目录时，"
                        + "它会自动获得 4 个**只读**文件工具（列目录/读文件/搜代码），但改不了文件、跑不了命令。",
                "介绍一下你自己，以及你这次回答会用到哪些能力。",
                List.of(new Member("assistant", "助手", """
                        你是一位讲中文的工程助手，直接回答用户的问题。

                        规则：
                        1. 先给结论，再给必要的解释；论证过程比客套话重要；
                        2. 你没有联网、没有地图、没有实时数据源。但先看手上有什么：给了你文件工具，
                        项目里的事实（版本号、常量、类名、目录结构、配置）就先去查，查到什么说什么；
                        只有真正要联网的实时信息（价格、营业时间、天气、新闻、具体地址）可以直说查不到，
                        不要拿"查不到"当成不去查的理由；
                        3. 不确定的地方明确标出来，宁可少说也不要猜；
                        4. 需要代码时给可直接运行的完整片段；
                        5. 不写"希望对您有帮助"这类空话。
                        6. 给了你文件工具就去看真东西：要几个文件才能下结论，就连续调用工具
                           把这几个文件都看完再回答，不要看一个就收尾；
                        7. 绝对不要用"接下来我将…""下一步我会…"结尾——要继续就直接调用工具
                           继续做；真做不下去了就说清楚卡在哪一步，不要把计划当成结论。
                        """, "none")),
                false,
                "___NEVER_MATCH___",
                1);
    }

    /**
     * 写代码：单个编码工程师 + 11 个本地工具（看目录/读文件/批量读目录/写文件/改文件/搜代码/跑命令，
     * 外加 4 个数据库工具）。
     *
     * <p>和前面几个场景的关键区别：<b>它的产出在磁盘上，不在聊天记录里</b>。所以判成功的标准
     * 不是"回答看起来对"，而是 {@code run_command} 返回的 exitCode 是不是 0。这也是
     * {@code maxToolIterations} 给到 30 的原因——真实闭环是"写→编译→报错→改→再编译"，
     * 默认 5 次连一轮错误修复都不够。</p>
     *
     * <p>任务里刻意要求"编译运行直到输出 PASS"：只写不跑，就无法验证这套工具到底有没有用。</p>
     */
    private static Scenario coding() {
        return new Scenario(
                "coding",
                "写代码",
                "单个编码工程师 agent，挂 11 个本地工具：列目录、读文件、批量读目录、写文件、改文件、搜代码、跑命令，"
                        + "外加连数据库的 4 个（看连接、看表结构、只读查询、建表插数据）。"
                        + "工作目录由用户在页面上选本机目录（不选就用配置默认的沙箱），选哪个就在哪个目录里干活："
                        + "读它、改它、在它里面编译。写已有文件必须带 sha256，改完回显 git 增删行数。"
                        + "命令按平台组装（Windows 用 cmd、Linux 用 sh，可切 powershell/bash），"
                        + "有白名单（javac/java/mvn/node/python/git 等），删除、推送、提权一律拒绝。"
                        + "数据库默认只连内置 H2 沙箱，真实库只读，写要显式开开关。",
                "用 Java 写一个线程安全的 LRU 缓存：泛型、固定容量、get/put，容量满时淘汰最久未使用的键。"
                        + "再写一个 main 自测（命中、淘汰、并发各覆盖一次），编译并运行，直到输出 PASS。",
                List.of(new Member("coder", "编码工程师",
                        com.example.agentcore.tool.code.CodePrompt.system(), "coding")),
                false,
                "___NEVER_MATCH___",
                // 单 agent 内部本来就有工具循环（30 次），多轮等于让它把同一个活重做一遍
                1);
    }

    /**
     * 收集侧的角色设定：只查事实、只交素材。
     *
     * <p>与 {@code TravelPrompt.SYSTEM} 的区别在于"不许下结论"。单 agent 时提示词必须同时
     * 教它查和写；分工之后，如果收集侧还顺手把结论写了，总结侧就只能润色，分工白做，
     * 而且两段回答会互相矛盾。这里用硬性禁令把"写"这件事从收集侧拿走。</p>
     */
    private static final String COLLECTOR_SYSTEM = """
            你是资料收集员，不是顾问。你的输出会原样交给另一位同事去写最终回答。

            工作方式：
            1. 先在心里数清用户问了几件事，再逐件调用工具查——地名转坐标用 get_location_coordinate，
               周边餐饮/景点用 search_nearby_poi，两地通行用 route_planning，开放式资料用 web_search；
            2. 全部查完再动笔，不要边查边给建议；
            3. 只输出「资料包」：分小节的清单，名称与数字**原样照抄工具返回值**，不改写、不换算、不四舍五入；
            4. 每个数据点后面用括号标出来源工具名，例如「橘子洲 112.961998,28.186938（get_location_coordinate）」；
            5. 工具没给的字段写「未返回」，绝不猜测、绝不用常识补齐。

            禁止：写行程建议、写推荐语、写"希望这些信息对您有帮助"这类客套、替用户做判断。
            你交的是原始素材，排版和取舍由下一位同事负责。
            """;

    /**
     * 总结侧的角色设定：只组织语言，不新增事实。
     *
     * <p>这条提示词的验收标准很具体——把资料包里随机一个数字改掉，回答里必须跟着错，
     * 说明它真的在读资料；如果回答里冒出资料包没有的数字，就是它在编。</p>
     */
    private static final String SUMMARIZER_SYSTEM = """
            你是行程撰写人。上一条以「collector:」开头的内容是同事查回来的资料包，
            用户最初的问题在它之前。你没有工具，也不要去猜。

            规则：
            1. 用户问了几件事就分几节逐条回答，一件都不能漏；先在心里列出问题清单，再逐条覆盖；
            2. 只用资料包里的数据。资料包里没有的字段写「未查到」，不要用常识补、不要估算、不要编数字；
            3. 名称、坐标、评分、人均、距离、时长、票价必须与资料包完全一致，不许改小数位、不许把精确值改写成"约"；
            4. 多条目数据用 markdown 表格，每张表前用一句话说明这张表是什么；
            5. 不要出现「资料包」「工具」「同事」「collector」这类内部说法，也不要复述工作流程。

            用中文回答，标题最多到三级。
            """;

    /**
     * 分工流水线：第 1 棒查数据，第 2 棒写终稿，两棒用不同的模型。
     *
     * <p>动机来自实测：本地 7B 单独跑旅游任务时，工具都调成功了，但最终只答了其中一段——
     * 瓶颈在"把多份工具结果汇总成回答"，不在工具链。于是把这两件事拆开：收集交给云端快模型
     * （工具调用准、10 秒能查完 7 项），总结交给另一个模型（不需要工具，只需要照资料组织语言）。</p>
     *
     * <p>{@code maxRounds=2}：轮询正好一人一轮。轮询顺序即流水线顺序，别改成 1——
     * 那会只跑收集、不跑总结。</p>
     */
    private static Scenario travelPipeline() {
        return new Scenario(
                "travel-pipeline",
                "分工流水线",
                "两棒接力：第 1 棒（主模型 + 7 个旅游工具）只查事实、输出资料包；"
                        + "第 2 棒（agent.properties 里 model.summarizer.* 配置的模型，无工具）只按资料包写终稿。"
                        + "没配 model.summarizer.name 时第 2 棒回退主模型，等于还是一个人干两遍。",
                "帮我规划长沙一日游：橘子洲、岳麓山、月亮岛三个景点各给经纬度坐标；"
                        + "再推荐橘子洲附近 3 家餐馆；最后说明从长沙南站到橘子洲的公交时长和票价。",
                List.of(
                        new Member("collector", "资料收集员", COLLECTOR_SYSTEM, "travel", null),
                        new Member("summarizer", "行程撰写", SUMMARIZER_SYSTEM, "none", "summarizer")),
                false,
                "___NEVER_MATCH___",
                2);
    }
}
