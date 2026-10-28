package com.example.agentcore.tool.db;

import com.example.agentcore.support.AppConfig;
import com.example.agentcore.support.Json;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 数据库工具：连库、看表结构、跑查询、执行建表/插入。
 *
 * <p>为什么编码 agent 需要它：写数据访问代码时，"SQL 对不对"是唯一说不清的部分——
 * 模型可以凭想象写一个 {@code GROUP BY}，编译过得去，跑起来才发现列不存在。有了这四个工具，
 * 它能在写代码之前先把表结构查清楚、把 SQL 真的跑一遍，再落盘成 DAO。
 * 实测这条链路把"SQL 列名靠猜"的返工直接消掉了。</p>
 *
 * <p>四条边界，缺一条就不敢给这个能力：</p>
 * <ol>
 *   <li><b>只能连登记过的库</b>：模型给的是连接名，不是 jdbc url，拼不出任意地址；</li>
 *   <li><b>写操作分档</b>：内置沙箱随便写（它就是用来造的），真实库要 {@code tool.db.allow-write=true}；</li>
 *   <li><b>查询只读</b>：{@code db_query} 只放行 SELECT/WITH/SHOW/DESC/EXPLAIN，且只开一条语句；</li>
 *   <li><b>H2 后门单独拦</b>：{@code RUNSCRIPT}、{@code ATTACH}、{@code CSVREAD}、{@code CREATE ALIAS}
 *       这类 SQL 能读写本地文件甚至执行 Java 代码，等于绕过整套白名单，必须点名禁掉。</li>
 * </ol>
 *
 * <p>失败一律返回 {@code ok=false} 的 JSON 而不是抛 {@code ERROR}：连不上库、表名写错、
 * SQL 语法错都是模型该自己修的正常反馈，走熔断会让它改两次就把工具禁掉。</p>
 */
public class DbTool {

    /** 只读语句：这些都返回结果集，且不改数据。 */
    private static final Pattern READ_ONLY = Pattern.compile(
            "^\\s*(select|with|show|desc|describe|explain|values|table)\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * 连接建立了也要拦的语句。
     *
     * <p>前几条是跨库的危险操作（删库、建用户、写文件、读文件）；后面几条是 H2 专有的
     * "SQL 里调 Java"能力——{@code CREATE ALIAS ... AS $$ ... $$} 能执行任意 Java 代码，
     * {@code RUNSCRIPT FROM 'file'} 能读机器上任意文件，{@code ATTACH} 能挂载任意库文件。
     * 沙箱再沙箱，这几条也必须在 SQL 层挡住。</p>
     */
    private static final List<String> DENIED_SQL = List.of(
            "drop database", "drop schema", "shutdown", "create user", "alter user", "drop user",
            "grant ", "revoke ", "set password", "into outfile", "into dumpfile", "load data",
            "load_file(", "xp_cmdshell", "sp_configure", "openrowset", "opendatasource",
            "create alias", "runscript", "attach database", "attach ", "link_schema",
            "csvread", "csvwrite", "create trigger", "create extension", "pg_read_file",
            "lo_import", "lo_export", "copy (select");

    private static final Set<String> SYSTEM_SCHEMAS = Set.of(
            "information_schema", "pg_catalog", "pg_toast", "sys", "mysql", "performance_schema",
            "sysobjects", "sysindexes", "systables", "syscolumns", "sysusers");

    private static final int MAX_TABLES = 200;
    private static final int MAX_VALUE_CHARS = 300;
    private static final int MIN_ROWS = 1;
    private static final int MAX_ROWS = 200;

    private final DbConnections connections;
    private final boolean allowWrite;
    private final int maxRows;
    private final int timeoutSeconds;

    public DbTool(DbConnections connections, boolean allowWrite, int maxRows, int timeoutSeconds) {
        this.connections = connections;
        this.allowWrite = allowWrite;
        this.maxRows = clampRows(maxRows);
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
    }

    public static DbTool fromConfig() {
        return fromConfig(AppConfig.get());
    }

    public static DbTool fromConfig(AppConfig config) {
        return new DbTool(
                DbConnections.fromConfig(config),
                config.getBoolean("tool.db.allow-write", false),
                (int) config.getLong("tool.db.max-rows", 50),
                (int) config.getLong("tool.db.query-timeout-seconds", 20));
    }

    // ---------------------------------------------------------------- 工具

    @Tool(name = "db_connections",
            value = "列出可以连的数据库。connection 参数只能填这里列出来的名字（留空=内置沙箱）。"
                    + "写数据前先看一眼 writable：真实库默认只读，只有内置沙箱允许随便建表插数据。")
    public String listConnections() {
        List<Object> items = Json.arr();
        for (DbConnections.Conn conn : connections.all()) {
            items.add(Json.obj(
                    "name", conn.name(),
                    "kind", conn.kind(),
                    "url", conn.safeUrl(),
                    "sandbox", conn.sandbox(),
                    "writable", isWritable(conn),
                    "note", conn.note() == null ? "" : conn.note()));
        }
        return Json.of(Json.obj(
                "ok", true,
                "count", items.size(),
                "connections", items,
                "allowWriteConfigured", allowWrite,
                "hint", "沙箱（sandbox）是本地 H2 文件库，建表随便造；真实库只在开了 "
                        + "tool.db.allow-write 之后才允许写"));
    }

    @Tool(name = "db_schema",
            value = "看库里的表结构。不给 table 就列出所有表；给了 table 就返回它每个列的名字、类型、"
                    + "是否可空、主键、默认值。写 SQL 之前先查一次，别猜列名。")
    public String schema(
            @P(description = "连接名，取自 db_connections；留空=内置沙箱") String connection,
            @P(description = "表名，可带 schema 前缀（如 PUBLIC.ORDERS）；留空=只列表清单") String table) {

        DbConnections.Conn conn;
        try {
            conn = connections.require(connection);
        } catch (IllegalArgumentException e) {
            return Json.of(Json.obj("ok", false, "error", e.getMessage()));
        }

        long start = System.nanoTime();
        try (Connection c = connections.open(conn, true)) {
            if (table == null || table.isBlank()) {
                List<Object> tables = Json.arr();
                List<TableRef> refs = tables(c);
                for (TableRef ref : refs.subList(0, Math.min(refs.size(), MAX_TABLES))) {
                    tables.add(Json.obj(
                            "name", ref.name(),
                            "schema", ref.schema(),
                            "type", ref.type()));
                }
                Map<String, Object> result = Json.obj(
                        "ok", true,
                        "connection", conn.name(),
                        "kind", conn.kind(),
                        "count", refs.size(),
                        "tables", tables,
                        "durationMs", millis(start));
                if (refs.isEmpty()) {
                    result.put("note", "库里还没有表。建表用 db_execute，例如 "
                            + "CREATE TABLE users(id INT PRIMARY KEY, name VARCHAR(50))");
                }
                if (refs.size() > MAX_TABLES) {
                    result.put("truncated", true);
                }
                return Json.of(result);
            }

            TableRef ref = findTable(c, table);
            if (ref == null) {
                return Json.of(Json.obj(
                        "ok", false,
                        "connection", conn.name(),
                        "error", "没有名为 " + table + " 的表",
                        "tables", tableNames(tables(c)),
                        "hint", "不带 table 参数再调一次 db_schema 可以看到完整清单"));
            }

            List<Object> columns = Json.arr();
            List<String> primaryKeys = primaryKeys(c, ref);
            try (ResultSet rs = c.getMetaData().getColumns(ref.catalog(), ref.schema(), ref.name(), "%")) {
                while (rs.next()) {
                    String column = rs.getString("COLUMN_NAME");
                    columns.add(Json.obj(
                            "name", column,
                            "type", typeName(rs),
                            "nullable", rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                            "primaryKey", primaryKeys.contains(column),
                            "default", rs.getString("COLUMN_DEF"),
                            "position", rs.getInt("ORDINAL_POSITION")));
                }
            }
            return Json.of(Json.obj(
                    "ok", true,
                    "connection", conn.name(),
                    "kind", conn.kind(),
                    "table", ref.name(),
                    "schema", ref.schema(),
                    "columns", columns,
                    "durationMs", millis(start)));
        } catch (SQLException e) {
            return failure(conn, e);
        }
    }

    @Tool(name = "db_query",
            value = "执行一条只读查询（SELECT / WITH / SHOW / DESC / EXPLAIN），返回列名和行。"
                    + "用来验证 SQL 写得对不对、看看表里到底有什么数据。"
                    + "只能一条语句；大表请自己加 WHERE / LIMIT，返回行数有上限。")
    public String query(
            @P(description = "连接名，取自 db_connections；留空=内置沙箱") String connection,
            @P(description = "要执行的查询语句，例如 SELECT * FROM users LIMIT 10") String sql,
            @P(description = "最多返回多少行，默认取配置值，上限 200") Integer maxRows) {

        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("sql 不能为空");
        }
        String statement = sql.trim();

        DbConnections.Conn conn;
        try {
            conn = connections.require(connection);
        } catch (IllegalArgumentException e) {
            return Json.of(Json.obj("ok", false, "error", e.getMessage()));
        }

        String single = statementWithoutTrailingSemicolon(statement);
        if (!READ_ONLY.matcher(single).find()) {
            return Json.of(Json.obj(
                    "ok", false,
                    "connection", conn.name(),
                    "error", "db_query 只接受只读语句（SELECT / WITH / SHOW / DESC / EXPLAIN）；"
                            + "建表、插入、更新、删除请用 db_execute",
                    "sql", statement));
        }
        if (containsStatementSeparator(single)) {
            return Json.of(Json.obj(
                    "ok", false,
                    "connection", conn.name(),
                    "error", "db_query 一次只能跑一条语句（检测到语句中间还有分号）；请拆成多次调用",
                    "sql", statement));
        }
        String denied = deniedSql(single);
        if (denied != null) {
            return Json.of(Json.obj(
                    "ok", false, "blocked", true, "connection", conn.name(),
                    "error", denied, "sql", statement));
        }

        int limit = clampRows(maxRows == null ? this.maxRows : maxRows);
        long start = System.nanoTime();
        try (Connection c = connections.open(conn, true);
             Statement st = c.createStatement()) {
            st.setQueryTimeout(timeoutSeconds);
            st.setMaxRows(limit + 1);
            try (ResultSet rs = st.executeQuery(single)) {
                ResultSetMetaData meta = rs.getMetaData();
                int columns = meta.getColumnCount();
                List<Object> names = Json.arr();
                List<Object> types = Json.arr();
                for (int i = 1; i <= columns; i++) {
                    names.add(meta.getColumnLabel(i));
                    types.add(meta.getColumnTypeName(i));
                }
                List<Object> rows = Json.arr();
                boolean truncated = false;
                while (rs.next()) {
                    if (rows.size() >= limit) {
                        truncated = true;
                        break;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= columns; i++) {
                        row.put(String.valueOf(names.get(i - 1)), value(rs, i));
                    }
                    rows.add(row);
                }
                Map<String, Object> result = Json.obj(
                        "ok", true,
                        "connection", conn.name(),
                        "kind", conn.kind(),
                        "columns", names,
                        "columnTypes", types,
                        "rowCount", rows.size(),
                        "truncated", truncated,
                        "rows", rows,
                        "sql", single,
                        "durationMs", millis(start));
                if (truncated) {
                    result.put("note", "结果超过 " + limit + " 行被截断；加 WHERE / LIMIT 或调大 maxRows（上限 "
                            + MAX_ROWS + "），不要靠看前面几行就下结论");
                }
                return Json.of(result);
            }
        } catch (SQLException e) {
            return failure(conn, e);
        }
    }

    @Tool(name = "db_execute",
            value = "执行建表、插入、更新、删除等写语句，返回影响行数。内置沙箱永远可写；"
                    + "真实库需要配置 tool.db.allow-write=true 才放行。"
                    + "删库、建用户、授权、读写本地文件（H2 的 RUNSCRIPT/ATTACH/CSVREAD/CREATE ALIAS）一律拒绝。")
    public String execute(
            @P(description = "连接名，取自 db_connections；留空=内置沙箱") String connection,
            @P(description = "要执行的 SQL，例如 CREATE TABLE users(id INT PRIMARY KEY, name VARCHAR(50))") String sql) {

        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("sql 不能为空");
        }
        String statement = statementWithoutTrailingSemicolon(sql.trim());

        DbConnections.Conn conn;
        try {
            conn = connections.require(connection);
        } catch (IllegalArgumentException e) {
            return Json.of(Json.obj("ok", false, "error", e.getMessage()));
        }
        if (!isWritable(conn)) {
            return Json.of(Json.obj(
                    "ok", false,
                    "blocked", true,
                    "connection", conn.name(),
                    "error", "这个连接是只读的（真实库默认只读，防误伤）。"
                            + "要练手/建表测试请用 sandbox；确实需要写真实库，"
                            + "在配置里设 tool.db.allow-write=true 再重启服务",
                    "sql", statement));
        }
        String denied = deniedSql(statement);
        if (denied != null) {
            return Json.of(Json.obj(
                    "ok", false, "blocked", true, "connection", conn.name(),
                    "error", denied, "sql", statement));
        }

        long start = System.nanoTime();
        try (Connection c = connections.open(conn, false);
             Statement st = c.createStatement()) {
            st.setQueryTimeout(timeoutSeconds);
            List<Object> results = Json.arr();
            boolean hasResultSet = st.execute(statement);
            int index = 0;
            while (true) {
                if (hasResultSet) {
                    try (ResultSet rs = st.getResultSet()) {
                        ResultSetMetaData meta = rs.getMetaData();
                        int rows = 0;
                        while (rs.next() && rows <= MAX_ROWS) {
                            rows++;
                        }
                        results.add(Json.obj(
                                "statement", index,
                                "kind", "resultSet",
                                "rowCount", rows,
                                "columns", meta.getColumnCount()));
                    }
                } else {
                    int affected = st.getUpdateCount();
                    if (affected == -1) {
                        break;
                    }
                    results.add(Json.obj("statement", index, "kind", "update", "affectedRows", affected));
                }
                index++;
                hasResultSet = st.getMoreResults();
            }
            return Json.of(Json.obj(
                    "ok", true,
                    "connection", conn.name(),
                    "kind", conn.kind(),
                    "results", results,
                    "statements", results.size(),
                    "sql", statement,
                    "durationMs", millis(start),
                    "hint", "想核对结果就接着用 db_query 查一次；不要只看 affectedRows 就宣布成功"));
        } catch (SQLException e) {
            return failure(conn, e);
        }
    }

    // ---------------------------------------------------------------- 内部

    private boolean isWritable(DbConnections.Conn conn) {
        return conn.sandbox() || allowWrite;
    }

    private static int clampRows(Integer rows) {
        if (rows == null) {
            return 50;
        }
        return Math.max(MIN_ROWS, Math.min(MAX_ROWS, rows));
    }

    /** 危险语句检查：命中返回理由，否则 null。 */
    private static String deniedSql(String sql) {
        String lower = sql.toLowerCase(Locale.ROOT);
        for (String bad : DENIED_SQL) {
            if (lower.contains(bad)) {
                return "SQL 里有被禁止的操作（" + bad.trim() + "）。这套工具只做建表/查询/增删改，"
                        + "不做删库、建用户、授权、读写文件、执行外部代码";
            }
        }
        return null;
    }

    /**
     * 去掉结尾的分号后，是否还剩下"语句级分号"。
     *
     * <p>不能简单 {@code contains(";")}：{@code WHERE name = 'a;b'} 里的分号是数据。
     * 所以要一路扫，遇到引号就跳过整段字符串（单双引号各处理，反斜杠转义也照顾到）。</p>
     */
    private static boolean containsStatementSeparator(String sql) {
        char quote = 0;
        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            if (quote != 0) {
                if (ch == '\\' && i + 1 < sql.length()) {
                    i++;
                } else if (ch == quote) {
                    quote = 0;
                }
                continue;
            }
            if (ch == '\'' || ch == '"' || ch == '`') {
                quote = ch;
            } else if (ch == ';') {
                // 后面还有非空白内容才算第二条语句
                if (!sql.substring(i + 1).isBlank()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String statementWithoutTrailingSemicolon(String sql) {
        String trimmed = sql.trim();
        while (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    /** 统一失败返回：把"是配置问题还是 SQL 问题"分清楚，模型才知道下一步该改什么。 */
    private static String failure(DbConnections.Conn conn, SQLException e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String hint;
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("connection refused") || lower.contains("communications link failure")
                || lower.contains("could not connect") || lower.contains("unknownhost")
                || lower.contains("连接被拒绝")) {
            hint = "连不上 " + conn.safeLabel() + "：库没起、端口不对或地址不通。"
                    + "先用 sandbox 验证 SQL，别在这里反复重试";
        } else if (lower.contains("access denied") || lower.contains("authentication")
                || lower.contains("invalid username") || lower.contains("密码")) {
            hint = "认证失败：配置里的用户名/密码与 " + conn.safeUrl() + " 不匹配，"
                    + "这是配置问题，重试没有用";
        } else if (lower.contains("table") || lower.contains("column") || lower.contains("syntax")) {
            hint = "SQL 本身有问题：先用 db_schema 看真实的表名/列名，再改 SQL";
        } else {
            hint = "先看 error 原文；表名/列名/语法错多半能用 db_schema 自己对出来";
        }
        return Json.of(Json.obj(
                "ok", false,
                "connection", conn.name(),
                "kind", conn.kind(),
                "url", conn.safeUrl(),
                "error", message,
                "errorType", e.getClass().getSimpleName(),
                "hint", hint));
    }

    private record TableRef(String catalog, String schema, String name, String type) {
    }

    /**
     * 列出所有用户表（滤掉系统 schema）。
     *
     * <p>先用当前 catalog 查一次，查不到再放开：H2 的 catalog 是库名，大小写对不上时
     * 一次都返回不了；MySQL 则必须带 catalog，否则会把整台实例所有库的表都倒出来。
     * 两种都可能，所以两级都试。</p>
     */
    private static List<TableRef> tables(Connection c) throws SQLException {
        List<TableRef> refs = collectTables(c, c.getCatalog());
        if (refs.isEmpty()) {
            refs = collectTables(c, null);
        }
        return refs;
    }

    private static List<TableRef> collectTables(Connection c, String catalog) throws SQLException {
        List<TableRef> refs = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        try (ResultSet rs = c.getMetaData().getTables(catalog, null, "%", new String[]{"TABLE", "VIEW"})) {
            while (rs.next()) {
                String schema = rs.getString("TABLE_SCHEM");
                String name = rs.getString("TABLE_NAME");
                if (name == null || (schema != null && SYSTEM_SCHEMAS.contains(schema.toLowerCase(Locale.ROOT)))) {
                    continue;
                }
                String key = (schema == null ? "" : schema) + "." + name;
                if (!seen.add(key)) {
                    continue;
                }
                refs.add(new TableRef(rs.getString("TABLE_CAT"), schema, name, rs.getString("TABLE_TYPE")));
            }
        }
        return refs;
    }

    private static List<String> tableNames(List<TableRef> refs) {
        List<String> names = new ArrayList<>();
        for (TableRef ref : refs.subList(0, Math.min(refs.size(), MAX_TABLES))) {
            names.add(ref.schema() == null ? ref.name() : ref.schema() + "." + ref.name());
        }
        return names;
    }

    /** 表名支持 {@code ORDERS} / {@code PUBLIC.ORDERS}（大小写不敏感）。 */
    private static TableRef findTable(Connection c, String requested) throws SQLException {
        String wanted = requested.trim();
        String schemaPart = null;
        String namePart = wanted;
        int dot = wanted.lastIndexOf('.');
        if (dot > 0) {
            schemaPart = wanted.substring(0, dot);
            namePart = wanted.substring(dot + 1);
        }
        for (TableRef ref : tables(c)) {
            boolean nameMatches = ref.name().equalsIgnoreCase(namePart);
            boolean schemaMatches = schemaPart == null
                    || (ref.schema() != null && ref.schema().equalsIgnoreCase(schemaPart));
            if (nameMatches && schemaMatches) {
                return ref;
            }
        }
        return null;
    }

    private static List<String> primaryKeys(Connection c, TableRef ref) throws SQLException {
        List<String> keys = new ArrayList<>();
        try (ResultSet rs = c.getMetaData().getPrimaryKeys(ref.catalog(), ref.schema(), ref.name())) {
            while (rs.next()) {
                keys.add(rs.getString("COLUMN_NAME"));
            }
        }
        return keys;
    }

    private static String typeName(ResultSet rs) throws SQLException {
        String type = rs.getString("TYPE_NAME");
        int size = rs.getInt("COLUMN_SIZE");
        int digits = rs.getInt("DECIMAL_DIGITS");
        if (type == null) {
            return "未知";
        }
        String upper = type.toUpperCase(Locale.ROOT);
        if (upper.contains("CHAR") && size > 0) {
            return type + "(" + size + ")";
        }
        if ((upper.contains("DECIMAL") || upper.contains("NUMERIC")) && size > 0) {
            return type + "(" + size + "," + digits + ")";
        }
        return type;
    }

    /** 单元格取值：null 保留成 null（不要变成字符串 "null"，模型会当真值）。 */
    private static Object value(ResultSet rs, int index) throws SQLException {
        Object raw = rs.getObject(index);
        if (raw == null) {
            return null;
        }
        if (raw instanceof byte[] bytes) {
            return "0x" + hex(bytes);
        }
        if (raw instanceof Clob clob) {
            int length = (int) Math.min(clob.length(), MAX_VALUE_CHARS);
            return length == 0 ? "" : clob.getSubString(1, length);
        }
        if (raw instanceof java.sql.Blob blob) {
            return "blob(" + blob.length() + " 字节)";
        }
        String text = String.valueOf(raw);
        return text.length() > MAX_VALUE_CHARS ? text.substring(0, MAX_VALUE_CHARS) + "…" : text;
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(bytes.length, 16); i++) {
            sb.append(String.format("%02x", bytes[i]));
        }
        return sb + (bytes.length > 16 ? "…(" + bytes.length + " 字节)" : "");
    }

    private static long millis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
