package com.example.agentcore.tool.db;

import com.example.agentcore.support.AppConfig;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * 数据库连接的登记处：内置一个可写沙箱，外加 {@code agent.properties} 里配的若干真实库。
 *
 * <p>为什么要有"沙箱"这一档：让 agent 连生产库是危险的，而"不能连库"又没法验证 SQL 写得对不对。
 * 所以默认给一个 H2 文件库，落在工作目录里（{@code <workspace>/.db/sandbox.mv.db}）——
 * 模型可以随便建表插数据，跑挂了也只是删掉一个文件；同时它是 <b>文件模式 + AUTO_SERVER</b>，
 * 意味着模型在 {@code run_command} 里起的 Java 进程也能连同一个库，
 * "用代码连数据库验证"这件事才真正闭环。</p>
 *
 * <p>真实库靠配置显式登记，名字由 {@code tool.db.connections} 列出，没登记的名字一律连不上——
 * 也就是模型<b>不能</b>自己拼一个 jdbc url 去连任意地址。</p>
 */
public final class DbConnections {

    /** 内置沙箱的连接名。 */
    public static final String SANDBOX = "sandbox";

    private static final String DEFAULT_SANDBOX_URL =
            "jdbc:h2:file:%s;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1";

    /** URL 里可能带密码，展示前先抹掉。 */
    private static final Pattern SECRET_IN_URL = Pattern.compile(
            "(?i)(password|pwd|user)\\s*=\\s*[^;&]*");

    private final Map<String, Conn> connections;

    private DbConnections(Map<String, Conn> connections) {
        this.connections = connections;
    }

    /**
     * 一条连接。
     *
     * @param name     工具里用的名字，例如 {@code sandbox} / {@code mysql-local}
     * @param url      JDBC URL（展示时会脱敏）
     * @param user     用户名
     * @param password 密码；只用于建立连接，永远不出现在工具返回值里
     * @param driver   驱动类名；留空则按 URL 推断
     * @param note     给模型看的说明（这个库是干什么的、有哪些表）
     * @param sandbox  是否是内置沙箱（可写、可随便造表）
     */
    public record Conn(String name, String url, String user, String password,
                       String driver, String note, boolean sandbox) {

        public String kind() {
            return DbConnections.kindOf(url);
        }

        public String effectiveDriver() {
            return driver == null || driver.isBlank() ? DbConnections.driverOf(url) : driver;
        }

        /** 打码后的 URL：日志、返回值、报错里都只出现这一版。 */
        public String safeUrl() {
            return SECRET_IN_URL.matcher(url).replaceAll("$1=***");
        }

        public String safeLabel() {
            return name + "（" + kind() + (sandbox ? "，内置沙箱" : "") + "）";
        }
    }

    /** 从配置读连接；配置里没有就只给沙箱。 */
    public static DbConnections fromConfig() {
        return fromConfig(AppConfig.get());
    }

    public static DbConnections fromConfig(AppConfig config) {
        Map<String, Conn> map = new LinkedHashMap<>();

        Path workspace = config.codeWorkspace().toAbsolutePath().normalize();
        String sandboxUrl = config.get("tool.db.sandbox-url",
                String.format(DEFAULT_SANDBOX_URL, workspace.resolve(".db/sandbox").toString().replace('\\', '/')));
        map.put(SANDBOX, new Conn(SANDBOX, sandboxUrl,
                config.get("tool.db.sandbox-user", "sa"),
                config.get("tool.db.sandbox-password", ""),
                "org.h2.Driver",
                "内置 H2 文件沙箱，可随便建表/插数据；数据文件在 " + workspace.resolve(".db") + "，"
                        + "删掉就重来。run_command 里起的 Java 进程也能连它（AUTO_SERVER）",
                true));

        String list = config.get("tool.db.connections", "");
        for (String raw : list.split(",")) {
            String name = raw.trim();
            if (name.isEmpty() || SANDBOX.equalsIgnoreCase(name)) {
                continue;
            }
            String prefix = "tool.db." + name + ".";
            String url = config.get(prefix + "url", "");
            if (url.isBlank()) {
                // 登记了名字却没给 url：跳过而不是让整个场景起不来
                continue;
            }
            map.put(name, new Conn(name, url,
                    config.get(prefix + "username", ""),
                    config.get(prefix + "password", ""),
                    config.get(prefix + "driver", ""),
                    config.get(prefix + "note", ""),
                    false));
        }
        return new DbConnections(map);
    }

    /** 测试用：直接给一组连接。 */
    public static DbConnections of(Conn... conns) {
        Map<String, Conn> map = new LinkedHashMap<>();
        for (Conn conn : conns) {
            map.put(conn.name(), conn);
        }
        return new DbConnections(map);
    }

    public List<Conn> all() {
        return new ArrayList<>(connections.values());
    }

    public boolean isEmpty() {
        return connections.isEmpty();
    }

    public Conn get(String name) {
        return connections.get(name);
    }

    /**
     * 取连接配置，名字为空时用沙箱。
     *
     * <p>报错里列出全部可用名字——模型猜名字是常见浪费，一次列清楚就地解决。</p>
     */
    public Conn require(String name) {
        String key = name == null || name.isBlank() ? SANDBOX : name.trim();
        Conn conn = connections.get(key);
        if (conn == null) {
            throw new IllegalArgumentException("没有名为 \"" + key + "\" 的数据库连接；"
                    + "可用的连接：" + String.join("、", connections.keySet())
                    + "。先用 db_connections 看一眼每个库是干什么的");
        }
        return conn;
    }

    /**
     * 建立连接。
     *
     * <p>每次都新建、用完就关：这套工具的调用频率（一次任务十几次）远达不到需要连接池的量级，
     * 而连接池在"模型把连接弄成半死状态"时的排查成本要高一整个数量级。</p>
     */
    public Connection open(Conn conn, boolean readOnly) throws SQLException {
        String driver = conn.effectiveDriver();
        if (!driver.isBlank()) {
            try {
                Class.forName(driver);
            } catch (ClassNotFoundException e) {
                throw new SQLException("找不到 JDBC 驱动 " + driver + "（" + conn.kind() + "）。"
                        + "把对应驱动 jar 放进 classpath 或 target/lib 再重启；"
                        + "内置的 H2 与已声明的 mysql/postgresql 不需要额外安装", e);
            }
        }
        Properties props = new Properties();
        if (conn.user() != null && !conn.user().isBlank()) {
            props.setProperty("user", conn.user());
        }
        if (conn.password() != null && !conn.password().isEmpty()) {
            props.setProperty("password", conn.password());
        }
        Connection connection = DriverManager.getConnection(conn.url(), props);
        if (readOnly) {
            try {
                connection.setReadOnly(true);
            } catch (SQLException ignored) {
                // 个别驱动不支持只读提示，靠语句检查兜底
            }
        }
        return connection;
    }

    /** 从 URL 判断库类型，用于展示和选驱动。 */
    static String kindOf(String url) {
        String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("jdbc:")) {
            return "未知";
        }
        String rest = lower.substring(5);
        int colon = rest.indexOf(':');
        String product = colon > 0 ? rest.substring(0, colon) : rest;
        return switch (product) {
            case "h2" -> "H2";
            case "mysql", "mariadb" -> "MySQL";
            case "postgresql" -> "PostgreSQL";
            case "dm" -> "达梦";
            case "sqlserver" -> "SQL Server";
            case "oracle" -> "Oracle";
            case "sqlite" -> "SQLite";
            default -> product;
        };
    }

    /** 常见库的驱动类名；不在表里就得在配置里显式写 driver。 */
    static String driverOf(String url) {
        return switch (kindOf(url)) {
            case "H2" -> "org.h2.Driver";
            case "MySQL" -> "com.mysql.cj.jdbc.Driver";
            case "PostgreSQL" -> "org.postgresql.Driver";
            case "达梦" -> "dm.jdbc.driver.DmDriver";
            case "SQL Server" -> "com.microsoft.sqlserver.jdbc.SQLServerDriver";
            case "Oracle" -> "oracle.jdbc.OracleDriver";
            case "SQLite" -> "org.sqlite.JDBC";
            default -> "";
        };
    }
}
