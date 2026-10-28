package com.example.agentcore.web;

import com.example.agentcore.messaging.ChatMsg;
import com.example.agentcore.runtime.SessionState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话落库的行为测试：写进去的能不能原样读出来，读不出来的会不会拖垮服务。
 *
 * <p>放在 {@code web} 包里是为了能直接摸到包内类型（{@link FileConversationStore} 不对包外公开）——
 * 这些行为（原子写、id 校验、损坏容错）在 HTTP 层面很难以"刚好触发"的方式验证。</p>
 */
class ConversationStoreTest {

    private static SessionState state(String... texts) {
        List<ChatMsg> messages = new java.util.ArrayList<>();
        for (int i = 0; i < texts.length; i++) {
            messages.add(new ChatMsg.Text(i % 2 == 0 ? "user" : "assistant", texts[i]));
        }
        return new SessionState(messages, List.of(), 0, "APPROVE", System.currentTimeMillis());
    }

    @Test
    void saveThenLoadKeepsMessagesAndMetadata(@TempDir Path dir) throws IOException {
        FileConversationStore store = new FileConversationStore(dir);

        Conversation original = Conversation.start("travel");
        original.remember(state("橘子洲的经纬度是多少", "位于长沙市岳麓区，坐标 112.961998,28.186938。"));
        store.save(original);

        Conversation loaded = store.load(original.id());
        assertNotNull(loaded, "刚写进去的对话必须能读回来");
        assertEquals(original.id(), loaded.id());
        assertEquals("travel", loaded.scenarioId(), "场景要跟着存，否则重启后追问会换一套角色");
        assertEquals(1, loaded.turns());
        assertEquals(2, loaded.messageCount());
        assertEquals("橘子洲的经纬度是多少",
                loaded.state().messages().get(0).text());
        assertEquals("user", loaded.state().messages().get(0).source());
    }

    /** 写完立刻启动"新进程"（新 store 实例）也要读得到——这是重启恢复的最小前提。 */
    @Test
    void newStoreInstanceSeesPreviousData(@TempDir Path dir) throws IOException {
        Conversation original = Conversation.start("general");
        original.remember(state("问题", "回答"));
        new FileConversationStore(dir).save(original);

        Conversation loaded = new FileConversationStore(dir).load(original.id());
        assertNotNull(loaded, "换一个 store 实例就等于换一个进程，数据必须在磁盘上");
        assertEquals(2, loaded.messageCount());
    }

    /**
     * id 是 URL 参数，必须当不可信输入处理。
     *
     * <p>{@code conversationId=../../agent.properties} 若不拦，等于给了一个读任意文件的口子；
     * 这里断言两个方向都堵住：读不出来，也写不出去。</p>
     */
    @Test
    void illegalIdIsRejectedOnBothPaths(@TempDir Path dir) throws IOException {
        FileConversationStore store = new FileConversationStore(dir);
        for (String bad : List.of("../../etc/passwd", "..", "abcd1234/../x", "ABCD1234X", "id with space")) {
            assertNull(store.load(bad), "非法 id 不该读到任何东西：" + bad);
            Conversation forged = Conversation.restore(bad, "general", 1, System.currentTimeMillis(),
                    state("x", "y"));
            boolean thrown = false;
            try {
                store.save(forged);
            } catch (IOException e) {
                thrown = true;
            }
            assertTrue(thrown, "非法 id 不该写得出去：" + bad);
        }
    }

    /** 一个被手工改坏的文件，只能让它自己失效，不能让启动失败。 */
    @Test
    void corruptFileIsTreatedAsMissing(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("deadbeef.json"), "{ 这不是 JSON");
        FileConversationStore store = new FileConversationStore(dir);
        assertNull(store.load("deadbeef"), "损坏的文件应返回 null 而不是抛异常");
        assertEquals(0, store.recent(10).size(), "损坏的文件不该出现在会话列表里");
    }

    /** 落盘必须原子：目录里不该留下写了一半的 .tmp，否则迟早会被当成正式文件。 */
    @Test
    void saveLeavesNoTemporaryFile(@TempDir Path dir) throws IOException {
        FileConversationStore store = new FileConversationStore(dir);
        Conversation conversation = Conversation.start("general");
        conversation.remember(state("a", "b"));
        store.save(conversation);
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(0, files.filter(p -> p.getFileName().toString().endsWith(".tmp")).count(),
                    "原子写完成后不应残留 .tmp");
        }
    }

    @Test
    void recentOrdersByLastActivity(@TempDir Path dir) throws IOException {
        FileConversationStore store = new FileConversationStore(dir);
        long now = System.currentTimeMillis();

        Conversation older = Conversation.restore("aaaaaaaa", "general", 1, now - 60_000, state("旧", "对话"));
        Conversation newer = Conversation.restore("bbbbbbbb", "travel", 1, now, state("新", "对话"));
        store.save(older);
        store.save(newer);

        List<Conversation> recent = store.recent(2);
        assertEquals(2, recent.size());
        assertEquals("bbbbbbbb", recent.get(0).id(), "最近活跃的应排在最前");
        assertEquals("aaaaaaaa", recent.get(1).id());
    }

    @Test
    void pruneDropsOnlyExpiredConversations(@TempDir Path dir) throws IOException {
        FileConversationStore store = new FileConversationStore(dir);
        long now = System.currentTimeMillis();

        Conversation stale = Conversation.restore("cccccccc", "general", 1, now - 10 * 86_400_000L,
                state("十天前", "聊过"));
        Conversation fresh = Conversation.restore("dddddddd", "general", 1, now, state("刚聊", "过"));
        store.save(stale);
        store.save(fresh);

        assertEquals(1, store.prune(now - 7 * 86_400_000L), "只该删掉超过 7 天的那一段");
        assertNull(store.load("cccccccc"));
        assertNotNull(store.load("dddddddd"));
    }

    /** 关掉持久化时同样是"读不到、也不报错"，便于演示机不落文件。 */
    @Test
    void noneStoreKeepsNothing() {
        ConversationStore store = ConversationStore.none();
        assertNull(store.load("abcdef01"));
        assertEquals(0, store.recent(5).size());
        assertFalse(store.describe().isEmpty());
    }
}
