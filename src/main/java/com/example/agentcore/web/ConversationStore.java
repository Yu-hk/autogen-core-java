package com.example.agentcore.web;

import com.example.agentcore.support.AppConfig;

import java.util.List;

/**
 * 多轮对话的持久化出口。
 *
 * <p>为什么需要它：{@link Conversation} 原来只活在进程内存里，于是三件事都做不到——
 * 重启后接着聊、滚动发布（新旧两个进程各自持有一半上下文）、以及"会话被 LRU 淘汰后
 * 用户再问一句"。落盘之后，内存只是缓存，磁盘才是真相。</p>
 *
 * <p>刻意做成接口而不是直接在服务里写文件：部署形态不同，会话该放哪儿也不同。
 * 单机用文件；将来多副本要么挂共享卷，要么换成一个实现同一接口的 JDBC 版本，
 * 上层的 {@link AgentWebServer} 一行都不用改。</p>
 *
 * <p>约定：读失败（文件损坏、权限不够）返回 {@code null} 而不是抛异常——一段旧对话读不出来，
 * 最坏的结果是"当作新对话"，不该让整个服务起不来。</p>
 */
interface ConversationStore {

    /** 读一段对话；不存在或读不出来返回 {@code null}。 */
    Conversation load(String id);

    /** 覆盖写一段对话。实现方必须保证原子性：读到半截 JSON 比读不到更糟。 */
    void save(Conversation conversation) throws java.io.IOException;

    /** 按最后活跃时间倒序取最近若干段（用于进程启动时预热内存）。 */
    List<Conversation> recent(int limit);

    /** 清掉最后活跃时间早于 {@code cutoffMillis} 的会话，返回删除条数。 */
    int prune(long cutoffMillis);

    /** 给 {@code /api/health} 看的一句话描述，便于确认"到底存到哪个目录"。 */
    String describe();

    /** 关掉持久化：会话退回纯内存。单测和"演示机不想留文件"时用。 */
    static ConversationStore none() {
        return new ConversationStore() {
            @Override
            public Conversation load(String id) {
                return null;
            }

            @Override
            public void save(Conversation conversation) {
            }

            @Override
            public List<Conversation> recent(int limit) {
                return List.of();
            }

            @Override
            public int prune(long cutoffMillis) {
                return 0;
            }

            @Override
            public String describe() {
                return "内存（未持久化）";
            }
        };
    }

    /** 按配置选一个实现：{@code web.conversation-store=file|none}。 */
    static ConversationStore from(AppConfig app) {
        String kind = app.conversationStore().trim().toLowerCase(java.util.Locale.ROOT);
        if ("none".equals(kind) || "memory".equals(kind) || "off".equals(kind)) {
            return none();
        }
        return new FileConversationStore(app.dataDir().resolve("conversations"));
    }
}
