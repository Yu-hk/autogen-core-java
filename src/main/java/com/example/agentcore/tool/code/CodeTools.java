package com.example.agentcore.tool.code;

import com.example.agentcore.support.AppConfig;
import com.example.agentcore.tool.db.DbTool;

import java.nio.file.Path;

/**
 * 编码工具的聚合入口：一次性构造全部工具，共用同一个工作目录。
 *
 * <table border="1">
 *   <caption>工具与它解决的问题</caption>
 *   <tr><th>工具</th><th>作用</th><th>没有它会怎样</th></tr>
 *   <tr><td>list_files</td><td>看目录结构</td><td>凭空造目录，覆盖已有文件</td></tr>
 *   <tr><td>read_file</td><td>读原文（带行号）</td><td>凭记忆改代码，改出来全是幻觉</td></tr>
 *   <tr><td>read_dir</td><td>批量读一个目录下的文件</td><td>读一个包要十几次 read_file，轮次全花在搬文件上</td></tr>
 *   <tr><td>write_file</td><td>新建/整体覆盖</td><td>写不出任何东西</td></tr>
 *   <tr><td>edit_file</td><td>局部精确替换</td><td>改一行要重写整个文件，长文件必被截断</td></tr>
 *   <tr><td>search_code</td><td>正则搜代码</td><td>靠猜文件名逐个读，十几次调用全耗在这</td></tr>
 *   <tr><td>run_command</td><td>编译/测试/运行</td><td>只会说"应该能跑"，没有验证能力</td></tr>
 *   <tr><td>db_connections</td><td>有哪些库可连</td><td>不知道往哪连，或猜一个连不上</td></tr>
 *   <tr><td>db_schema</td><td>表结构</td><td>列名靠猜，写完跑起来才发现对不上</td></tr>
 *   <tr><td>db_query</td><td>只读查询</td><td>SQL 只能"看着像对"，没法验证</td></tr>
 *   <tr><td>db_execute</td><td>建表/插数据</td><td>没法造测试数据，写不了端到端验证</td></tr>
 * </table>
 *
 * <p>前 7 个是"能闭环写代码"的最小集合；后 4 个解决"代码里那段 SQL 到底对不对"——
 * 数据访问代码的错有一半不在 Java 里，在 SQL 里，而 SQL 的错只有跑过才知道。</p>
 */
public final class CodeTools {

    private CodeTools() {
    }

    /** 按工作目录构造全套工具，直接丢给 {@code ChatAgent.Builder.tools(...)}。 */
    public static Object[] all(Path workspaceRoot, int commandTimeoutSeconds) {
        return all(new CodeWorkspace(workspaceRoot), commandTimeoutSeconds, DbTool.fromConfig());
    }

    /** 沙箱位置与工具工作目录必须一致：测试用临时目录时走这个重载。 */
    public static Object[] all(CodeWorkspace workspace, int commandTimeoutSeconds, DbTool dbTool) {
        return all(workspace, commandTimeoutSeconds, dbTool, AppConfig.get().codeWriteProtect());
    }

    /**
     * @param writeProtect 是否要求"写已有文件必须带 sha256"。测试里传 false 可以单独
     *                     验证工具本身的替换逻辑，不被哈希校验干扰
     */
    public static Object[] all(CodeWorkspace workspace, int commandTimeoutSeconds,
                               DbTool dbTool, boolean writeProtect) {
        return new Object[]{
                new CodeFilesTool(workspace, writeProtect),
                new CodeCommandTool(workspace, commandTimeoutSeconds),
                dbTool
        };
    }

    /** 工作目录由调用方给（含只读目录），数据库工具按配置构造。 */
    public static Object[] all(CodeWorkspace workspace, int commandTimeoutSeconds) {
        return all(workspace, commandTimeoutSeconds, DbTool.fromConfig());
    }

    /** 默认 120 秒命令超时，测试与临时调用用这个。 */
    public static Object[] all(Path workspaceRoot) {
        return all(workspaceRoot, 120);
    }

    /** 测试用：关掉写保护，只测文件读写/搜索本身。 */
    public static Object[] allWithoutWriteProtect(Path workspaceRoot) {
        return all(new CodeWorkspace(workspaceRoot), 120, DbTool.fromConfig(), false);
    }

    /** 只读文件工具名：看一眼项目/读一段代码/搜一个符号，这四个够了。 */
    public static final String[] READ_ONLY_NAMES = {
            "list_files", "read_dir", "read_file", "search_code"
    };

    /**
     * 只读子集：能看目录、读文件、搜代码，<b>没有</b>写文件、改文件、跑命令。
     *
     * <p>给"分析这个项目""看下这段代码"这类任务用。它和 {@link #all} 的区别不在实现，
     * 在<b>模型看见什么</b>：写工具挂上去，模型就会试着写——而分析任务不该改动任何文件。
     * 所以是裁剪 schema（{@link com.example.agentcore.agent.ToolRegistry#subset}），
     * 不是挂上去再拒绝。</p>
     */
    public static com.example.agentcore.agent.ToolRegistry readOnly(CodeWorkspace workspace) {
        return com.example.agentcore.agent.ToolRegistry
                .of(new CodeFilesTool(workspace, false))
                .subset(READ_ONLY_NAMES);
    }

    /** 同上，按目录构造。 */
    public static com.example.agentcore.agent.ToolRegistry readOnly(Path workspaceRoot) {
        return readOnly(new CodeWorkspace(workspaceRoot));
    }
}
