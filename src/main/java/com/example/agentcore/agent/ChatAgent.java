package com.example.agentcore.agent;

import com.example.agentcore.messaging.AgentEvent;
import com.example.agentcore.messaging.ChatMsg;
import com.example.agentcore.runtime.AgentContext;
import com.example.agentcore.support.RetryPolicy;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 会调用模型的对话 agent，对应 AutoGen 的 {@code AssistantAgent}。
 *
 * <p>工具循环是这里的关键：模型返回 tool_calls → 执行 → 把结果作为工具消息回灌 → 再问模型，
 * 直到模型给出纯文本。AutoGen 用 {@code max_tool_iterations} 兜底，这里用同名参数。</p>
 *
 * <p>两种模型形态共用同一套循环：</p>
 * <ul>
 *   <li>{@link ChatModel}：一次请求拿完整响应；</li>
 *   <li>{@link StreamingChatModel}：边生成边发 {@link AgentEvent.TextDelta}，
 *       但**最终文本仍以 {@code onCompleteResponse} 给出的完整响应为准**。</li>
 * </ul>
 *
 * <p>为什么不用增量拼接当结果：工具调用轮里模型可能先说半句话再调工具，那段增量不会进入
 * 最终回复；以完整响应为准才能保证"事件流是给人看的，消息是给编排用的"。</p>
 */
public class ChatAgent extends AbstractAgent {

    /** 流式装配超时，仅作兜底；真正的网络超时由模型客户端的 timeout 控制。 */
    private static final long STREAM_TIMEOUT_MINUTES = 10L;

    /**
     * 同一个工具连续失败几次就熔断。
     *
     * <p>起因是实测：没配高德 Key 时，本地 7B 会在 {@code get_location_coordinate} 和
     * {@code web_search} 之间来回横跳，把每轮的工具预算全部耗在同一个必然失败的工具上。
     * 提示词里写"换个工具"对它没用，所以这里在框架层直接把它从工具列表里摘掉。</p>
     *
     * <p>计数挂在 agent 实例上而不是单次 handle 上：旅游场景是多轮的，
     * 状态要跨轮保持才能真正止住循环。</p>
     */
    private static final int TOOL_FAILURE_LIMIT = 2;

    private final ChatModel model;
    private final StreamingChatModel streamingModel;
    private final String systemMessage;
    private final ToolRegistry tools;
    private final int maxToolIterations;
    private final RetryPolicy retryPolicy;
    private final Map<String, Integer> toolFailures = new ConcurrentHashMap<>();

    /**
     * 本次尝试是否已经往页面推过字。
     *
     * <p>流式输出重试的代价和别处不一样：用户屏幕上已经有半句话了，重来一遍会让同一段文字
     * 出现两次。所以只要 {@code onPartialResponse} 调过一次，这次失败就不再重试——
     * 能安全重试的只有"还没吐出任何内容就失败"的情形（连接失败、首包超时、503），
     * 而这恰好是绝大多数失败。</p>
     */
    private final AtomicBoolean streamedThisAttempt = new AtomicBoolean(false);

    /**
     * 已经成功执行过的「工具名 + 参数」→ 当时的状态版本号，用于拦住同参重复调用。
     *
     * <p>实测 qwen2.5:7b 在多轮里会把 {@code get_location_coordinate("岳麓山","长沙")}
     * 用一模一样的参数连调三轮，白烧掉本就紧张的轮次预算；提示词拦不住。
     * 这里记下参数指纹，重复时不再真的发请求，而是回一条明确的「你已经查过了」。</p>
     *
     * <p>只记成功的调用：失败的要允许换个说法重试（那是熔断器管的事）。</p>
     *
     * <p><b>为什么还要带版本号：</b>"同参 → 同结果"这条假设只对只读工具成立。编码场景里
     * {@code run_command("javac ... && java ...")} 第一次是 FAIL，改完代码再跑一次是 PASS——
     * 参数完全一样，但意义完全不同。所以每次有副作用的工具成功执行，就把
     * {@link #stateEpoch} 加一；指纹里记着当时的版本号，版本变过就放行。
     * 实测不加这一条，模型要用"给命令加个假参数"这种脏办法绕过拦截，白烧 6 次调用。</p>
     */
    private final Map<String, Integer> successfulToolCalls = new ConcurrentHashMap<>();

    /** 外部状态的版本号：任何有副作用的工具成功执行一次就加一。 */
    private int stateEpoch = 0;

    private ChatAgent(String name, ChatModel model, StreamingChatModel streamingModel,
                      String systemMessage, ToolRegistry tools, int maxToolIterations,
                      RetryPolicy retryPolicy) {
        super(name);
        if ((model == null) == (streamingModel == null)) {
            throw new IllegalArgumentException("必须且只能提供一种模型：ChatModel 或 StreamingChatModel");
        }
        this.model = model;
        this.streamingModel = streamingModel;
        this.systemMessage = systemMessage;
        this.tools = tools == null ? ToolRegistry.empty() : tools;
        this.maxToolIterations = maxToolIterations;
        this.retryPolicy = retryPolicy == null ? RetryPolicy.NONE : retryPolicy;
    }

    public static Builder builder(String name, ChatModel model) {
        return new Builder(name, model, null);
    }

    /** 流式版本：逐字产出 {@link AgentEvent.TextDelta}，最终回复与非流式一致。 */
    public static Builder streamingBuilder(String name, StreamingChatModel streamingModel) {
        return new Builder(name, null, streamingModel);
    }

    @Override
    public void handle(ChatMsg msg, AgentContext ctx) {
        ctx.emit(new AgentEvent.SpeakingStarted(name(), ctx.round()));

        List<ChatMessage> history = new ArrayList<>();
        history.add(SystemMessage.from(systemMessage));
        for (ChatMsg m : ctx.transcript()) {
            // 自己说过的话记为 assistant 消息，别人说的带上发言人前缀，形成共享上下文
            if (m.source().equals(name())) {
                history.add(AiMessage.from(m.text()));
            } else {
                history.add(UserMessage.from(m.source() + ": " + m.text()));
            }
        }

        String reply = null;
        boolean nudged = false;
        int promiseNudges = 0;
        for (int iteration = 0; iteration <= maxToolIterations; iteration++) {
            List<ToolSpecification> active = activeSpecifications();
            ChatRequest.Builder request = ChatRequest.builder().messages(history);
            if (!active.isEmpty()) {
                request.toolSpecifications(active);
            }
            AiMessage ai = callModel(request.build(), ctx).aiMessage();
            String text = ai.text();

            // 模型回了空消息：既没有文本也没有工具调用。小模型在「上下文里已经有答案」时
            // 会这样收尾（实测 qwen2.5:7b 第二轮必现）。
            // 注意空消息**不能进历史**：Ollama 会以
            // {"error":{"message":"invalid message content type: <nil>"}} 拒收整个请求。
            if (!ai.hasToolExecutionRequests() && (text == null || text.isBlank())) {
                if (!nudged) {
                    nudged = true;
                    history.add(UserMessage.from(
                            "[系统] 你上一条回复是空的。请直接用中文给出最终回答，不要再调用工具。"));
                    continue;
                }
                reply = "（模型连续两次返回空回复，本轮没有产出内容。可直接重试，或换一个模型。）";
                break;
            }

            history.add(ai);

            if (!ai.hasToolExecutionRequests()) {
                // 走到这里 text 一定非空（上面的空回复分支已经拦掉了），保留兜底以防返回空白
                String cleaned = stripThinking(text);

                // 「话说到一半」：小模型常见毛病是把"接下来我将分析 X"当成最终回答交卷，
                // 整轮就此结束，用户看到的就是分析做到一半没了下文。
                // 实测 qwen2.5:3b：调一次 list_files、答 546 字、以"接下来，我将分析 frequencyAnaly
                // 目录下的内容"结尾就停了，而后面的 pom.xml 一个字都没读到。
                // 这里给它一次机会真的去做，且只补一次——补多了等于把已答的话重做一遍。
                if (promiseNudges < 2 && iteration < maxToolIterations && !active.isEmpty()
                        && endsWithPromise(cleaned)) {
                    promiseNudges++;
                    history.add(UserMessage.from(
                            "[系统] 你上一条只写了「接下来要做什么」就结束了，没有真的去做，也没有给出结论。"
                                    + "现在立刻调用所需工具继续（不要复述计划、不要征求同意、不要问我是否继续），"
                                    + "拿到结果后直接给出结论。"));
                    continue;
                }

                if (cleaned.isEmpty()) {
                    reply = "（模型只输出了推理过程、没有给结论，本轮没有产出。"
                            + "deepseek-r1 这类推理模型要额外烧一倍 token 想事情，"
                            + "本机纯 CPU 上更慢；换 qwen2.5 这类非推理模型通常更划算。）";
                } else {
                    reply = cleaned;
                }
                break;
            }
            for (ToolExecutionRequest call : ai.toolExecutionRequests()) {
                ctx.emit(new AgentEvent.ToolCallRequested(name(), call.name(), call.arguments()));

                String fingerprint = call.name() + "|" + call.arguments();
                Integer atEpoch = successfulToolCalls.get(fingerprint);
                if (atEpoch != null && atEpoch == stateEpoch) {
                    // 只说"结果不会变"是不够的：实测模型上次拿到的结果被截断了（truncated=true），
                    // 它想多看一点，却又原样再调一次，被拦下后就僵在这里。所以这里必须告诉它
                    // 「换参数就能继续」，否则这条拦截等于把路堵死了。
                    String repeat = "（重复调用：本次会话你已经用完全相同的参数调用过 " + call.name()
                            + "，这之后没有任何文件或状态变化，结果不会变，这里不再重复请求。"
                            + "请直接使用上一次的结果，继续下一步或给出最终回答。"
                            + "如果上一次返回里 truncated=true（结果被截断），换参数就能看到更多——"
                            + "比如 list_files 传 dir=\"子目录\" 或调大 depth、read_file 传 from/to；"
                            + "参数不同就不会被拦。）";
                    ctx.emit(new AgentEvent.ToolExecuted(name(), call.name(), repeat, 0L));
                    history.add(ToolExecutionResultMessage.from(call, repeat));
                    continue;
                }

                long start = System.nanoTime();
                String result = tools.invoke(call.name(), call.arguments());
                long millis = (System.nanoTime() - start) / 1_000_000L;
                ctx.emit(new AgentEvent.ToolExecuted(name(), call.name(), result, millis));
                history.add(ToolExecutionResultMessage.from(call, result));
                recordOutcome(call.name(), result, history);
                if (isFailure(result)) {
                    // 失败的调用不占位：允许模型换个参数重试（同参重试由熔断器管）
                    successfulToolCalls.remove(fingerprint);
                } else {
                    // 先递增再记指纹：写文件这类调用自己也会改变状态，但"同样的内容再写一遍"
                    // 依然是纯粹的浪费，所以它自己的指纹要按新版本号记，连写两次第二遍就被拦
                    if (tools.isMutating(call.name())) {
                        stateEpoch++;
                    }
                    successfulToolCalls.put(fingerprint, stateEpoch);
                }
            }
        }

        if (reply == null) {
            reply = "（工具调用达到上限 " + maxToolIterations + " 次，仍未收敛出最终文本。"
                    + "通常是模型在反复调用工具，减少可用工具或换更大的模型会好转。）";
        }
        ctx.reply(new ChatMsg.Text(name(), reply));
        ctx.emit(new AgentEvent.SpeakingFinished(name(), ctx.round(), reply));
    }

    /**
     * 推理模型的思考块标记。
     *
     * <p>用拼接写死字面量：源码里出现完整的尖括号标签组合时，容易被编辑器/补全改写，
     * 实测就踩过一次（开标签里的 {@code <} 被吃掉，导致截断判断永远不生效）。</p>
     */
    private static final String THINK_OPEN = "<" + "think" + ">";
    private static final String THINK_END = "<" + "/think" + ">";

    /**
     * 计划句的"将来时"成分：出现这些词，说明这句话指向的是接下来还没发生的事。
     */
    private static final List<String> PLAN_WORDS = List.of(
            "接下来", "下一步", "下面我", "下面，我", "我将", "我会");

    /**
     * 动作动词。计划句必然带一个"要做的事"，靠它和"我会建议…"这类纯表态区分开。
     */
    private static final List<String> ACTION_WORDS = List.of(
            "查看", "分析", "读取", "读一下", "搜索", "查找", "检查", "确认", "继续",
            "梳理", "深入", "看一下", "看看", "遍历", "执行", "运行", "统计", "汇总", "核对");

    /**
     * 这条回复是不是"只交代了下一步要做什么"，而没真去做。
     *
     * <p>只看<b>最后一句</b>。要求"三个条件同时成立"：<b>将来时成分</b> + <b>第一人称</b> +
     * <b>动作动词</b>。只匹配词表踩过两次坑：先是漏了不带"接下来"的写法，后来模型换成
     * "我会使用 search_code 来查找…"，固定词表又没覆盖。</p>
     *
     * <p>反过来，只要求"含将来时"也会误判——"建议你下一步先看调度模块""所以我会建议用
     * OR-Tools 求解"都是正常结论，它们缺第一人称主语或缺动作动词，正好被第二、第三个条件挡掉。</p>
     *
     * <p>为什么不看最后两句："接下来我将分析 pom.xml。分析结果：依赖 Spring Boot 2.1.7。"
     * 是说完计划又真给了结论，属于已经做完的回答；把这种也拽回去重做，等于让用户多等几分钟。</p>
     */
    static boolean endsWithPromise(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String sentence : lastSentences(text.strip(), 1)) {
            if (isPromiseSentence(sentence)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPromiseSentence(String sentence) {
        if (sentence.isEmpty() || !sentence.contains("我")) {
            // "我会/我将"必然带"我"，这一条同时挡掉了"建议你下一步…"这种对用户说的计划
            return false;
        }
        boolean future = PLAN_WORDS.stream().anyMatch(sentence::contains);
        if (!future) {
            return false;
        }
        return ACTION_WORDS.stream().anyMatch(sentence::contains);
    }

    /** 取最后 n 句（以句末标点或换行切分），顺序保持原文。 */
    private static List<String> lastSentences(String text, int n) {
        List<String> all = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isSentenceBreak(c)) {
                if (!current.isEmpty()) {
                    all.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) {
            all.add(current.toString());
        }
        List<String> out = new ArrayList<>();
        for (String s : all) {
            String t = s.strip();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out.size() <= n ? out : out.subList(out.size() - n, out.size());
    }

    /**
     * 句子边界。
     *
     * <p>刻意<b>不</b>把空格和点号算进来：正文里 {@code "接下来，我将分析 frequencyAnaly
     * 目录下的内容"} 中间有空格，按空格切会只剩"目录下的内容"，计划句就漏判了；
     * 点号则会切坏 {@code pom.xml} 这类文件名。中文句子以 {@code 。！？} 收尾，够用。</p>
     */
    private static boolean isSentenceBreak(char c) {
        return c == '。' || c == '！' || c == '？' || c == '!' || c == '?' || c == '\n' || c == '\r';
    }

    /**
     * 去掉推理模型的"思考块"，只留结论。
     *
     * <p>实测 deepseek-r1:8b 经 Ollama 的 {@code /v1} 流式返回时，思考过程会混进 content，
     * 正文里留下 {@code ...}，页面上直接显示成一段元思考，用户看到的就是脏数据。
     * 这里只做「切掉最后一个 {@code }」这一件事：非推理模型的输出没有这个标记，
     * 原样返回，不受影响。</p>
     */
    static String stripThinking(String text) {
        if (text == null) {
            return "";
        }
        String cleaned = text;
        int end = cleaned.lastIndexOf(THINK_END);
        if (end >= 0) {
            cleaned = cleaned.substring(end + THINK_END.length());
        } else if (cleaned.indexOf(THINK_OPEN) >= 0) {
            // 只有开标签没有闭标签（思考被截断）：整段都不可信，宁可当作没产出
            cleaned = "";
        }
        return cleaned.strip();
    }

    /** 当前还愿意交给模型的工具：连续失败到上限的先摘掉。 */
    private List<ToolSpecification> activeSpecifications() {
        if (tools.isEmpty()) {
            return List.of();
        }
        List<ToolSpecification> active = new ArrayList<>();
        for (ToolSpecification specification : tools.specifications()) {
            if (toolFailures.getOrDefault(specification.name(), 0) < TOOL_FAILURE_LIMIT) {
                active.add(specification);
            }
        }
        return active;
    }

    /**
     * 记一次工具结果，决定要不要熔断。
     *
     * <p>熔断时往历史里插一条说明，是为了让模型知道"不是没给工具，是它被禁用了"——
     * 否则它会继续在文本里假装调用。</p>
     */
    private void recordOutcome(String toolName, String result, List<ChatMessage> history) {
        if (result != null && result.startsWith("ERROR")) {
            int count = toolFailures.merge(toolName, 1, Integer::sum);
            if (count == TOOL_FAILURE_LIMIT) {
                history.add(UserMessage.from("[系统] 工具 " + toolName + " 已连续失败 " + count
                        + " 次，本次会话不再提供它。请改用其他工具，或根据已有信息直接回答用户。"));
            }
        } else {
            // 成功、以及"参数写错要改"的可重试失败（retryable）都清零：
            // 实测连续两次 old_text 对不上就把 edit_file 停用，模型转而去写脚本改文件
            toolFailures.remove(toolName);
        }
    }

    /** 这次调用算不算失败：ERROR 前缀（工具坏了）与 retryable（参数要改）都算。 */
    private static boolean isFailure(String result) {
        return result != null && (result.startsWith("ERROR") || isRetryable(result));
    }

    /** ToolRegistry 对"参数级失败"返回的标记，见 RetryableToolError。 */
    private static boolean isRetryable(String result) {
        return result != null && result.contains("\"retryable\":true");
    }

    /**
     * 统一取一次完整响应：流式与非流式的差异只在这一处。
     *
     * <p>重试也包在这里，而不是包在工具循环外面——包在外面重试会把整轮的工具调用重跑一遍，
     * 白烧配额；只重试"这一次模型请求"才是对的。</p>
     *
     * <p>流式失败的可重试性还要多判一条：见 {@link #streamedThisAttempt}。</p>
     */
    private ChatResponse callModel(ChatRequest request, AgentContext ctx) {
        RuntimeException last = null;
        for (int attempt = 0; attempt <= retryPolicy.maxRetries(); attempt++) {
            if (attempt > 0) {
                long delay = retryPolicy.delayBefore(attempt);
                ctx.emit(new AgentEvent.Retrying(name(), attempt, retryPolicy.maxRetries(),
                        delay, brief(last)));
                if (!sleepQuietly(delay)) {
                    // 被停止按钮打断：不再重试，把上一次的失败抛出去
                    throw last == null ? new IllegalStateException("重试等待被中断") : last;
                }
            }
            streamedThisAttempt.set(false);
            try {
                return streamingModel == null ? model.chat(request) : chatToCompletion(request, ctx);
            } catch (RuntimeException e) {
                if (!retryPolicy.retryable(e)) {
                    throw e;
                }
                if (streamedThisAttempt.get()) {
                    // 页面已经有半句话了，重来会让同一段文字出现两次
                    throw e;
                }
                last = e;
            }
        }
        throw last;
    }

    /** 重试等待期间收到停止请求要能立刻退出，所以分段睡。 */
    private static boolean sleepQuietly(long millis) {
        long remaining = millis;
        while (remaining > 0) {
            long chunk = Math.min(remaining, 200L);
            try {
                Thread.sleep(chunk);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            remaining -= chunk;
        }
        return true;
    }

    /** 把异常压成一句话给页面看：完整堆栈在服务端日志里，事件里只要够用。 */
    private static String brief(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        String message = error.getMessage();
        String text = message == null || message.isBlank()
                ? error.getClass().getSimpleName()
                : message;
        return text.length() <= 120 ? text : text.substring(0, 117) + "...";
    }

    /**
     * 把异步流式回调收敛成同步返回。
     *
     * <p>编排层是按轮串行推进的，必须等这一轮真的结束才能选下一位发言人，
     * 所以这里用 {@link CompletableFuture} 做桥接，而不是把 Team 改成响应式。</p>
     */
    private ChatResponse chatToCompletion(ChatRequest request, AgentContext ctx) {
        CompletableFuture<ChatResponse> sink = new CompletableFuture<>();
        AtomicReference<String> failureStage = new AtomicReference<>("启动流式请求");
        Object deltaLock = new Object();

        streamingModel.chat(request, new StreamingChatResponseHandler() {

            @Override
            public void onPartialResponse(String partial) {
                if (partial == null || partial.isEmpty()) {
                    return;
                }
                // 回调发生在模型客户端的 IO 线程；事件列表本身是写时复制的，这里只需保证语义顺序
                synchronized (deltaLock) {
                    streamedThisAttempt.set(true);
                    ctx.emit(new AgentEvent.TextDelta(name(), ctx.round(), partial));
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                failureStage.set("接收完整响应");
                sink.complete(response);
            }

            @Override
            public void onError(Throwable error) {
                sink.completeExceptionally(error);
            }
        });

        try {
            return sink.get(STREAM_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("流式调用被中断", e);
        } catch (Exception e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            // ConnectException 这类异常的 message 是 null，只打 getMessage() 会得到一句
            // "失败: null"，排查时等于什么都没说
            String reason = cause.getMessage() == null || cause.getMessage().isBlank()
                    ? cause.getClass().getName()
                    : cause.getMessage();
            throw new IllegalStateException(name() + " 流式调用失败（" + failureStage.get() + "）: "
                    + reason, cause);
        }
    }

    public static final class Builder {

        private final String name;
        private final ChatModel model;
        private final StreamingChatModel streamingModel;
        private String systemMessage = "";
        private ToolRegistry tools = ToolRegistry.empty();
        private int maxToolIterations = 5;
        private RetryPolicy retryPolicy = RetryPolicy.defaults();

        private Builder(String name, ChatModel model, StreamingChatModel streamingModel) {
            this.name = name;
            this.model = model;
            this.streamingModel = streamingModel;
        }

        public Builder systemMessage(String value) {
            this.systemMessage = value;
            return this;
        }

        public Builder tools(Object... toolObjects) {
            this.tools = ToolRegistry.of(toolObjects);
            return this;
        }

        /**
         * 直接给一个注册表：需要"只挂一个工具类里的某几个方法"时用。
         *
         * <p>{@link #tools(Object...)} 是整类整类地挂，适合"这套工具全都要"的场景；
         * 而"能读不能写"的只读助手必须裁到方法级——见 {@link ToolRegistry#subset(String...)}。</p>
         */
        public Builder toolRegistry(ToolRegistry value) {
            this.tools = value == null ? ToolRegistry.empty() : value;
            return this;
        }

        public Builder maxToolIterations(int value) {
            this.maxToolIterations = value;
            return this;
        }

        /**
         * 模型调用的重试策略。
         *
         * <p>默认 {@link RetryPolicy#defaults()}（重试 1 次）：这个能力应该默认生效，
         * 否则每个装配点都要记得开，迟早漏一个。真要关掉时显式传 {@link RetryPolicy#NONE}——
         * 排查"这个错是偶发还是必然"时必须关，重试会把偶发错误藏起来。</p>
         */
        public Builder retryPolicy(RetryPolicy value) {
            this.retryPolicy = value == null ? RetryPolicy.defaults() : value;
            return this;
        }

        public ChatAgent build() {
            return new ChatAgent(name, model, streamingModel, systemMessage, tools,
                    maxToolIterations, retryPolicy);
        }
    }
}
