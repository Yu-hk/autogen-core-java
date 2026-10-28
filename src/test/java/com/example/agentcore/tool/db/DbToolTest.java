package com.example.agentcore.tool.db;

import com.example.agentcore.agent.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据库工具的接线测试：不连外部库，用 H2 内存库把四条边界逐条钉住。
 *
 * <p>值得测的不是"SQL 能不能跑"（那是 H2 的事），而是<b>这套工具会不会被用成后门</b>：
 * 只读连接真的挡住了写吗？真实库在没开开关时能不能被 ALTER？H2 的 {@code RUNSCRIPT}
 * 能不能读机器上的文件？这些一旦漏了，整套白名单就白做了。</p>
 */
class DbToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String dbName() {
        return "dbtest_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static DbTool tool(String db, boolean allowWrite, int maxRows) {
        DbConnections conns = DbConnections.of(
                new DbConnections.Conn("sandbox", "jdbc:h2:mem:" + db + ";DB_CLOSE_DELAY=-1",
                        "sa", "", "org.h2.Driver", "测试沙箱", true),
                new DbConnections.Conn("prod", "jdbc:h2:mem:" + db + "_prod;DB_CLOSE_DELAY=-1",
                        "sa", "s3cret", "org.h2.Driver", "真实库（只读）", false));
        return new DbTool(conns, allowWrite, maxRows, 10);
    }

    private static ToolRegistry registry(DbTool tool) {
        return ToolRegistry.of(tool);
    }

    private static JsonNode call(ToolRegistry registry, String tool, String json) {
        String raw = registry.invoke(tool, json);
        assertFalse(raw.startsWith("ERROR"), "失败应该是结果，不该是工具故障：" + raw);
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new AssertionError(tool + " 返回的不是 JSON：" + raw, e);
        }
    }

    private static JsonNode ok(ToolRegistry registry, String tool, String json) {
        JsonNode node = call(registry, tool, json);
        assertTrue(node.path("ok").asBoolean(), node.toString());
        return node;
    }

    @Test
    @DisplayName("列连接：沙箱可写、真实库只读，密码不出现在返回值里")
    void connectionsHidePasswords() {
        ToolRegistry registry = registry(tool(dbName(), false, 50));

        JsonNode node = ok(registry, "db_connections", "{}");

        assertEquals(2, node.path("count").asInt(), node.toString());
        assertEquals("sandbox", node.path("connections").get(0).path("name").asText());
        assertTrue(node.path("connections").get(0).path("writable").asBoolean(), node.toString());
        assertFalse(node.path("connections").get(1).path("writable").asBoolean(), node.toString());
        // 密码只用来建连接，任何时候都不该出现在给模型看的内容里
        assertFalse(node.toString().contains("s3cret"), node.toString());
    }

    @Test
    @DisplayName("建表 → 插数据 → 看结构 → 查出来核对，这条链路要通")
    void schemaQueryAndExecuteWorkTogether() {
        ToolRegistry registry = registry(tool(dbName(), false, 50));

        JsonNode created = ok(registry, "db_execute",
                "{\"sql\":\"CREATE TABLE users(id INT PRIMARY KEY, name VARCHAR(50), city VARCHAR(50))\"}");
        assertEquals(1, created.path("statements").asInt(), created.toString());

        ok(registry, "db_execute",
                "{\"sql\":\"INSERT INTO users VALUES (1,'阿宝','长沙'),(2,'小明','深圳')\"}");

        JsonNode tables = ok(registry, "db_schema", "{}");
        assertEquals(1, tables.path("count").asInt(), tables.toString());
        assertEquals("USERS", tables.path("tables").get(0).path("name").asText());

        JsonNode columns = ok(registry, "db_schema", "{\"table\":\"users\"}");
        assertEquals(3, columns.path("columns").size(), columns.toString());
        String firstName = columns.path("columns").get(0).path("name").asText();
        assertEquals("ID", firstName);
        assertTrue(columns.path("columns").get(0).path("primaryKey").asBoolean(),
                "主键要标出来，否则模型不知道 join 该用哪个列：" + columns);

        JsonNode rows = ok(registry, "db_query", "{\"sql\":\"SELECT id, name FROM users ORDER BY id\"}");
        assertEquals(2, rows.path("rowCount").asInt(), rows.toString());
        assertEquals("阿宝", rows.path("rows").get(0).path("NAME").asText(), rows.toString());
        assertEquals("ID", rows.path("columns").get(0).asText(), rows.toString());
        assertFalse(rows.path("truncated").asBoolean(), rows.toString());
    }

    @Test
    @DisplayName("db_query 不放行写语句，也不放行一条调用里塞两条 SQL")
    void queryIsReadOnly() {
        ToolRegistry registry = registry(tool(dbName(), false, 50));

        JsonNode insert = call(registry, "db_query",
                "{\"sql\":\"INSERT INTO users VALUES (1,'x','y')\"}");
        assertFalse(insert.path("ok").asBoolean(), insert.toString());
        assertTrue(insert.path("error").asText().contains("只读"), insert.toString());
        assertTrue(insert.path("error").asText().contains("db_execute"),
                "拒绝时要告诉它该用哪个工具：" + insert);

        JsonNode two = call(registry, "db_query",
                "{\"sql\":\"SELECT 1; SELECT 2\"}");
        assertFalse(two.path("ok").asBoolean(), two.toString());
        assertTrue(two.path("error").asText().contains("一条"), two.toString());

        // 字符串字面量里的分号是数据，不是语句分隔符，不能被误判
        JsonNode literal = ok(registry, "db_query",
                "{\"sql\":\"SELECT 'a;b' AS v\"}");
        assertEquals("a;b", literal.path("rows").get(0).path("V").asText(), literal.toString());
    }

    @Test
    @DisplayName("真实库默认拒绝写；开了 allow-write 才放行")
    void realDatabaseIsReadOnlyUnlessAllowed() {
        String db = dbName();
        ToolRegistry locked = registry(tool(db, false, 50));
        JsonNode blocked = call(locked, "db_execute",
                "{\"connection\":\"prod\",\"sql\":\"CREATE TABLE t(id INT)\"}");
        assertFalse(blocked.path("ok").asBoolean(), blocked.toString());
        assertTrue(blocked.path("blocked").asBoolean(), blocked.toString());
        assertTrue(blocked.path("error").asText().contains("只读"), blocked.toString());

        ToolRegistry unlocked = registry(tool(db, true, 50));
        JsonNode allowed = ok(unlocked, "db_execute",
                "{\"connection\":\"prod\",\"sql\":\"CREATE TABLE t(id INT)\"}");
        assertEquals(1, allowed.path("statements").asInt(), allowed.toString());
    }

    @Test
    @DisplayName("H2 的文件后门必须挡掉：RUNSCRIPT / ATTACH / CSVREAD / CREATE ALIAS")
    void h2FileAndCodeEscapeHatchesAreBlocked() {
        ToolRegistry registry = registry(tool(dbName(), true, 50));

        String[] attacks = {
                "{\"sql\":\"RUNSCRIPT FROM 'C:/Windows/win.ini'\"}",
                "{\"sql\":\"ATTACH DATABASE 'C:/tmp/x' AS x\"}",
                "{\"sql\":\"SELECT * FROM CSVREAD('C:/tmp/a.csv')\"}",
                "{\"sql\":\"CREATE ALIAS EXEC AS $$ void run() {} $$\"}"
        };
        for (String attack : attacks) {
            JsonNode node = call(registry, "db_execute", attack);
            assertFalse(node.path("ok").asBoolean(), "这条没挡住：" + attack + " → " + node);
            assertTrue(node.path("blocked").asBoolean(), node.toString());
        }

        // 连查询通道也要挡，别指望模型只从 execute 走
        JsonNode query = call(registry, "db_query",
                "{\"sql\":\"SELECT * FROM CSVREAD('C:/tmp/a.csv')\"}");
        assertFalse(query.path("ok").asBoolean(), query.toString());
    }

    @Test
    @DisplayName("行数超上限要截断并说明，不许让模型以为表里就这么多")
    void rowLimitIsExplicit() {
        ToolRegistry registry = registry(tool(dbName(), false, 2));
        ok(registry, "db_execute",
                "{\"sql\":\"CREATE TABLE nums(n INT); INSERT INTO nums VALUES (1),(2),(3),(4),(5)\"}");

        JsonNode rows = ok(registry, "db_query", "{\"sql\":\"SELECT n FROM nums ORDER BY n\"}");
        assertEquals(2, rows.path("rowCount").asInt(), rows.toString());
        assertTrue(rows.path("truncated").asBoolean(), rows.toString());
        assertTrue(rows.path("note").asText().contains("截断"), rows.toString());
    }

    @Test
    @DisplayName("表名写错时把真实表清单一起返回，省掉一次来回")
    void unknownTableListsWhatExists() {
        ToolRegistry registry = registry(tool(dbName(), false, 50));
        ok(registry, "db_execute", "{\"sql\":\"CREATE TABLE orders(id INT)\"}");

        JsonNode node = call(registry, "db_schema", "{\"table\":\"order\"}");
        assertFalse(node.path("ok").asBoolean(), node.toString());
        assertEquals(1, node.path("tables").size(), node.toString());
        assertEquals("PUBLIC.ORDERS", node.path("tables").get(0).asText(), node.toString());
    }

    @Test
    @DisplayName("连接名写错时不猜，直接列出全部可用连接")
    void unknownConnectionListsNames() {
        ToolRegistry registry = registry(tool(dbName(), false, 50));

        JsonNode node = call(registry, "db_query",
                "{\"connection\":\"mysql-local\",\"sql\":\"SELECT 1\"}");
        assertFalse(node.path("ok").asBoolean(), node.toString());
        assertTrue(node.path("error").asText().contains("sandbox"), node.toString());
        assertTrue(node.path("error").asText().contains("prod"), node.toString());
    }
}
