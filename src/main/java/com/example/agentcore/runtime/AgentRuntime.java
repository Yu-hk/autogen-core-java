package com.example.agentcore.runtime;

import com.example.agentcore.agent.AbstractAgent;
import com.example.agentcore.messaging.AgentEvent;
import com.example.agentcore.messaging.ChatMsg;
import com.example.agentcore.messaging.Topic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 单进程运行时，对应 AutoGen 的 {@code SingleThreadedAgentRuntime}。
 *
 * <p>职责三件：注册 agent、按订阅把广播消息解析成投递目标、记录共享上下文与过程事件。
 * 刻意不引入线程池：先保证语义清晰，需要并发时再把 {@link #drain()} 换成虚拟线程调度即可。</p>
 */
public final class AgentRuntime {

    private final Map<AgentId, AbstractAgent> agents = new LinkedHashMap<>();
    private final Map<String, Function<AgentId, AbstractAgent>> factories = new LinkedHashMap<>();
    private final List<Subscription> subscriptions = new ArrayList<>();
    private final List<Delivery> queue = new ArrayList<>();
    private final List<ChatMsg> transcript = new ArrayList<>();
    /** 流式回调在 HTTP 客户端线程上 emit，必须用写时复制列表，否则并发写会丢事件。 */
    private final List<AgentEvent> events = new CopyOnWriteArrayList<>();
    private final List<Consumer<AgentEvent>> listeners = new CopyOnWriteArrayList<>();

    private int dropped;
    /** 流式回调线程会读到它，必须 volatile。 */
    private volatile int currentRound;

    public AgentId register(String type, String key, AbstractAgent agent) {
        AgentId id = new AgentId(type, key);
        if (agents.putIfAbsent(id, agent) != null) {
            throw new IllegalStateException("agent 已注册: " + id);
        }
        return id;
    }

    /** key 取 {@code default} 的简写，配合 {@link Topic#of(String)} 使用。 */
    public AgentId register(String type, AbstractAgent agent) {
        return register(type, "default", agent);
    }

    /**
     * 注册按需创建工厂，对应 AutoGen "目标 AgentID 不存在时运行时自动创建"的行为。
     * 多租户场景下 topic source 不同即得到不同实例，无需预先建好。
     */
    public void registerFactory(String type, Function<AgentId, AbstractAgent> factory) {
        factories.put(type, factory);
    }

    public void subscribe(String topicType, String agentType) {
        subscriptions.add(new Subscription(topicType, agentType));
    }

    /**
     * 广播到 topic。没有任何订阅时消息被静默丢弃并计数——这是 AutoGen 的既有语义，
     * 也是排查"agent 没反应"时最该先看的地方。
     */
    public void publish(Topic topic, ChatMsg msg) {
        List<AgentId> targets = resolve(topic);
        if (targets.isEmpty()) {
            dropped++;
            return;
        }
        for (AgentId target : targets) {
            queue.add(new Delivery(target, msg));
        }
    }

    /** 直接消息（一对一）。 */
    public void send(AgentId target, ChatMsg msg) {
        queue.add(new Delivery(target, msg));
    }

    /** 把一条消息写入共享上下文（任务入口用）。 */
    public void inject(ChatMsg msg) {
        transcript.add(msg);
    }

    /**
     * 从快照恢复运行时状态，对应 AutoGen 的 {@code load_state()}。
     *
     * <p>恢复语义是**替换**而非追加：调用方应当用一个全新的 runtime 承接快照，
     * 避免"上一场会话残留 + 快照"叠出重复消息。</p>
     */
    public void restore(SessionState state) {
        transcript.clear();
        events.clear();
        transcript.addAll(state.messages());
        events.addAll(state.events());
        currentRound = state.round();
    }

    /**
     * 注册过程事件监听器（UI 打字机效果、日志采集都走这里）。
     *
     * <p>流式场景下这个回调会在模型客户端的 IO 线程被触发，实现方自行保证线程安全；
     * 监听器抛异常不影响主流程。</p>
     */
    public void onEvent(Consumer<AgentEvent> listener) {
        listeners.add(listener);
    }

    private void recordEvent(AgentEvent event) {
        events.add(event);
        for (Consumer<AgentEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException ignored) {
                // 监听器是旁路消费方，不能因为它挂掉拖垮对话
            }
        }
    }

    /** 立即派发一个 agent（AgentChat 团队驱动路径，不经过 topic）。 */
    public void dispatch(AgentId id, ChatMsg trigger) {
        AbstractAgent agent = resolve(id);
        agent.handle(trigger, new Ctx(id, agent.name()));
    }

    /** 查找 agent，不存在时按工厂创建（AutoGen 的按需实例化）。 */
    public AbstractAgent resolve(AgentId id) {
        AbstractAgent agent = agents.get(id);
        if (agent != null) {
            return agent;
        }
        Function<AgentId, AbstractAgent> factory = factories.get(id.type());
        if (factory == null) {
            throw new IllegalStateException("未注册的 agent: " + id);
        }
        AbstractAgent created = factory.apply(id);
        agents.put(id, created);
        return created;
    }

    public Optional<AbstractAgent> find(AgentId id) {
        return Optional.ofNullable(agents.get(id));
    }

    public int agentCount() {
        return agents.size();
    }

    /** 处理队列中的所有投递，返回处理条数。 */
    public int drain() {
        int handled = 0;
        while (!queue.isEmpty()) {
            Delivery delivery = queue.remove(0);
            transcript.add(delivery.msg());
            dispatch(delivery.target(), delivery.msg());
            handled++;
        }
        return handled;
    }

    public void beginRound(int round) {
        this.currentRound = round;
    }

    public ChatMsg lastMessage() {
        return transcript.isEmpty() ? null : transcript.get(transcript.size() - 1);
    }

    public List<ChatMsg> transcript() {
        return List.copyOf(transcript);
    }

    public List<AgentEvent> events() {
        return List.copyOf(events);
    }

    public int droppedMessages() {
        return dropped;
    }

    public List<Subscription> subscriptions() {
        return List.copyOf(subscriptions);
    }

    private List<AgentId> resolve(Topic topic) {
        List<AgentId> targets = new ArrayList<>();
        for (Subscription s : subscriptions) {
            if (s.topicType().equals(topic.type())) {
                AgentId id = new AgentId(s.agentType(), topic.source());
                if (!targets.contains(id)) {
                    targets.add(id);
                }
            }
        }
        return targets;
    }

    private record Delivery(AgentId target, ChatMsg msg) {
    }

    private final class Ctx implements AgentContext {

        private final AgentId self;
        private final String agentName;

        private Ctx(AgentId self, String agentName) {
            this.self = self;
            this.agentName = agentName;
        }

        @Override
        public AgentId self() {
            return self;
        }

        @Override
        public void send(AgentId target, ChatMsg msg) {
            AgentRuntime.this.send(target, msg);
        }

        @Override
        public void publish(Topic topic, ChatMsg msg) {
            AgentRuntime.this.publish(topic, msg);
        }

        @Override
        public void reply(ChatMsg msg) {
            transcript.add(msg);
        }

        @Override
        public void emit(AgentEvent event) {
            recordEvent(event);
        }

        @Override
        public List<ChatMsg> transcript() {
            return List.copyOf(transcript);
        }

        @Override
        public int round() {
            return currentRound;
        }

        @Override
        public String agentName() {
            return agentName;
        }
    }
}
