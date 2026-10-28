package com.example.agentcore.termination;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 终止条件，对应 AutoGen 的 {@code TerminationCondition}。
 *
 * <p>AutoGen 用 Python 的运算符重载表达 {@code &} 与 {@code |}，Java 没有运算符重载，
 * 这里用 default 方法达到同样表达力：{@code textMention("DONE").or(maxMessages(10))}。</p>
 *
 * <p>注意 AutoGen 的语义：条件是有状态的，但每次 {@code run()} 结束会自动重置。
 * 本项目把状态放在 {@link TeamState} 里按轮求值，天然不残留，省掉 reset 的坑。</p>
 */
public interface StopCondition {

    /** 命中则返回停止原因，未命中返回空。 */
    Optional<String> test(TeamState state);

    /** 任一命中即停止，对应 {@code |}。 */
    default StopCondition or(StopCondition other) {
        return state -> {
            Optional<String> first = test(state);
            return first.isPresent() ? first : other.test(state);
        };
    }

    /** 全部命中才停止，对应 {@code &}。 */
    default StopCondition and(StopCondition other) {
        return state -> {
            Optional<String> first = test(state);
            return first.isEmpty() ? Optional.empty() : other.test(state);
        };
    }

    static StopCondition never() {
        return state -> Optional.empty();
    }

    static StopCondition always(String reason) {
        return state -> Optional.of(reason);
    }

    /** 消息数达到上限，对应 {@code MaxMessageTermination}。按**累计**计数：预算是跨进程的。 */
    static StopCondition maxMessages(int max) {
        return state -> state.messageCount() >= max
                ? Optional.of("消息数达到 " + max)
                : Optional.empty();
    }

    /**
     * 出现指定文本即停止，对应 {@code TextMentionTermination}。
     *
     * <p>只看本次运行新增的消息。若按全量判定，从快照续跑时历史里的 {@code APPROVE}
     * 会让条件在第一时间命中，续跑等于没跑。</p>
     */
    static StopCondition textMention(String text) {
        return state -> {
            var fresh = state.lastFresh();
            if (fresh == null) {
                return Optional.empty();
            }
            String content = fresh.text() == null ? "" : fresh.text();
            return content.contains(text)
                    ? Optional.of("命中终止词 " + text + "（来自 " + fresh.source() + "）")
                    : Optional.empty();
        };
    }

    /** 指定成员发言后停止，对应 {@code SourceMatchTermination}。同样只看本次运行。 */
    static StopCondition sourceMatch(String agent) {
        return state -> {
            var fresh = state.lastFresh();
            return fresh != null && agent.equals(fresh.source())
                    ? Optional.of("成员 " + agent + " 已完成发言")
                    : Optional.empty();
        };
    }

    /** 超时停止，对应 {@code TimeoutTermination}。计时从本次运行开始，不累计历史耗时。 */
    static StopCondition timeout(long millis) {
        return state -> state.elapsedMillis() >= millis
                ? Optional.of("运行超时 " + millis + "ms")
                : Optional.empty();
    }

    /** 某个工具被调用即停止，对应 {@code FunctionCallTermination}。只看本次运行内的调用。 */
    static StopCondition toolCalled(String tool) {
        return state -> state.anyToolCalled(tool)
                ? Optional.of("工具 " + tool + " 已被调用")
                : Optional.empty();
    }

    /** 外部信号停止，对应 {@code ExternalTermination}（UI 的"停止"按钮）。 */
    static StopCondition external(AtomicBoolean flag) {
        return state -> flag.get()
                ? Optional.of("收到外部停止信号")
                : Optional.empty();
    }

    /** 组合多个条件的快捷方式。 */
    static StopCondition anyOf(List<StopCondition> conditions) {
        return conditions.stream().reduce(never(), StopCondition::or);
    }
}
