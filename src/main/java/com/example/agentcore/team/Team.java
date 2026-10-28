package com.example.agentcore.team;

import com.example.agentcore.messaging.ChatMsg;
import com.example.agentcore.runtime.AgentId;
import com.example.agentcore.runtime.AgentRuntime;
import com.example.agentcore.runtime.SessionState;
import com.example.agentcore.termination.StopCondition;
import com.example.agentcore.termination.TeamState;

import java.util.List;
import java.util.Optional;

/**
 * 团队抽象，对应 AutoGen 的 {@code BaseGroupChat}。
 *
 * <p>循环结构完全照搬：注入任务 → 选发言人 → 派发 → 收到回复 → 求值终止条件 → 未终止则继续。
 * 子类唯一要改的是"怎么选下一位发言人"。</p>
 *
 * <p>三条运行入口：</p>
 * <ul>
 *   <li>{@link #run(String)} 从零开始；</li>
 *   <li>{@link #resume(SessionState)} 从快照接着跑，不追加新指令；</li>
 *   <li>{@link #resume(SessionState, String)} 从快照接着跑，并追加一条新指令（"继续，把 X 补上"）。</li>
 * </ul>
 *
 * <p><b>轮次预算是累计的</b>：{@code maxRounds} 对续跑同样生效，续跑会消耗同一份额度。
 * 若希望续跑有全新预算，请显式调大 {@link #maxRounds(int)}。</p>
 */
public abstract class Team {

    protected final AgentRuntime runtime;
    protected final List<AgentId> members;

    protected StopCondition termination = StopCondition.never();
    protected int maxRounds = 20;

    protected Team(AgentRuntime runtime, List<AgentId> members) {
        if (members == null || members.isEmpty()) {
            throw new IllegalArgumentException("团队成员不能为空");
        }
        this.runtime = runtime;
        this.members = List.copyOf(members);
    }

    public Team onStop(StopCondition condition) {
        this.termination = condition;
        return this;
    }

    public Team maxRounds(int rounds) {
        this.maxRounds = rounds;
        return this;
    }

    /** 从零开始跑一轮任务。 */
    public TaskResult run(String task) {
        return execute(null, task);
    }

    /** 从快照恢复后继续跑，不追加新指令。 */
    public TaskResult resume(SessionState state) {
        return execute(state, null);
    }

    /** 从快照恢复后继续跑，并追加一条新指令。 */
    public TaskResult resume(SessionState state, String task) {
        return execute(state, task);
    }

    /** 把一次运行结果封成可落盘的状态，配合 {@link #resume(SessionState)} 使用。 */
    public SessionState snapshot(TaskResult result) {
        return new SessionState(result.messages(), result.events(), result.round(),
                result.stopReason(), System.currentTimeMillis());
    }

    private TaskResult execute(SessionState restored, String task) {
        if (restored != null) {
            runtime.restore(restored);
        }
        // 基线要在注入新指令之前取：本轮注入的任务算"新增"，终止条件应该能看到它
        int baselineMessages = runtime.transcript().size();
        int baselineEvents = runtime.events().size();

        if (task != null) {
            runtime.inject(new ChatMsg.Text("user", task));
        }

        ChatMsg last = runtime.lastMessage();
        if (last == null) {
            throw new IllegalStateException("没有可用的起始消息：请传入 task，或提供一份非空快照");
        }

        long startedAt = System.currentTimeMillis();
        int round = restored == null ? 0 : restored.round();
        String stopReason = null;

        while (round < maxRounds) {
            AgentId speaker = selectSpeaker(round, last);
            round++;
            runtime.beginRound(round);

            int before = runtime.transcript().size();
            runtime.dispatch(speaker, last);
            List<ChatMsg> after = runtime.transcript();
            if (after.size() == before) {
                stopReason = "成员 " + speaker + " 未产出回复";
                break;
            }
            last = after.get(after.size() - 1);

            TeamState state = new TeamState(after, runtime.events(),
                    System.currentTimeMillis() - startedAt, round,
                    baselineMessages, baselineEvents);
            Optional<String> hit = termination.test(state);
            if (hit.isPresent()) {
                stopReason = hit.get();
                break;
            }
        }

        if (stopReason == null) {
            stopReason = "达到最大轮次 " + maxRounds;
        }
        return new TaskResult(runtime.transcript(), runtime.events(), stopReason, round);
    }

    /** 选择下一位发言人。 */
    protected abstract AgentId selectSpeaker(int round, ChatMsg last);

    protected AgentId roundRobin(int round) {
        return members.get(round % members.size());
    }
}
