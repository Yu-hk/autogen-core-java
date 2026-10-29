package com.example.agentcore.tool.code;

import com.example.agentcore.support.Json;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 跑命令：编译、跑测试、执行脚本。
 *
 * <p>"会写代码"和"代码能跑"之间差的就是这个工具。模型自己念一遍代码说"应该没问题"是
 * 最典型的幻觉——实测让它写一个 LRU 缓存，不跑的时候有两处编译错误，跑一次它就自己改对了。
 * 所以这个工具不是可选项，是编码能力的地基。</p>
 *
 * <p><b>为什么用白名单而不是黑名单：</b>能跑命令等于能删文件、能联网、能提权。黑名单永远漏
 * （{@code cmd /c rd /s /q}、{@code powershell -enc}、{@code %COMSPEC% /c}……），
 * 白名单漏了只是少一个工具，黑名单漏了是事故。这里只放编译/运行/版本控制这几类。</p>
 *
 * <p><b>Windows 与 Linux 分开处理：</b>见 {@link Platform}。简言之——cmd 里没有
 * {@code ls}/{@code cat}/{@code grep}，Linux 上也没有 {@code dir}/{@code type}；模型写错平台时
 * 直接告诉它本平台的等价写法，比让它撞一次墙再改便宜得多。白名单和命令行组装都跟着
 * <b>本次实际使用的 shell</b> 走（Windows 上装了 Git Bash 也能跑 POSIX 命令）。</p>
 *
 * <p><b>命令失败不算工具失败：</b>编译报错是正常工作流的一部分，必须原样交给模型去修。
 * 所以 {@code exitCode != 0} 返回 {@code ok=false} 而不是 {@code ERROR:}——后者会触发
 * {@code ChatAgent} 的熔断计数，改几次代码就把工具禁掉了。</p>
 */
public class CodeCommandTool {

    /**
     * 就算程序在白名单里，出现这些串也直接拒。
     *
     * <p>拦的是"用合法程序做破坏性的事"：{@code git push} 会把代码推到远端，
     * {@code git reset --hard} / {@code git clean -fd} 会清掉别人的工作区，
     * {@code rm -rf} 与 {@code rmdir /s} 是删库，{@code --no-verify} 是跳过钩子。
     * 这些操作在"让模型自己写代码"的场景里没有任何正当用途。</p>
     */
    private static final List<String> DENIED = List.of(
            "rm -rf", "rm -fr", "rm -r", "rmdir /s", "rd /s", "del /f", "del /s", "del /q",
            "format ", "mkfs", "shutdown", "reboot", "halt",
            "git push", "git reset --hard", "git clean -fd", "git checkout --", "git branch -d",
            "--no-verify", "--force", "-fpush",
            "sudo", "runas", "reg delete", "reg add", "sc stop", "sc delete",
            "chmod 777", "chown ",
            "curl |", "wget |", "| sh", "| bash", "powershell -enc",
            // 首词检查只看第一个词，所以"套一层解释器"是绕过它的标准手法：
            // find -exec / find -delete / xargs 都能把白名单里的程序拼成删除动作
            "find . -delete", "-delete", "-exec ", "xargs", "sed -i", "tee ",
            "remove-item", "start-process", "invoke-expression",
            "> /dev/sd", "/etc/passwd", ".ssh", "access_token", "api_key");

    /**
     * 上一条管不住的单文件删除：{@code rm a.txt}、{@code del a.txt}、{@code rm a.txt;}
     * 都不含上面那些带参数的长串。
     *
     * <p>用词边界正则而不是子串，否则 {@code perform }、{@code inform } 这类单词会被误伤。
     * 编码场景里删文件没有正当需求（编译产物换个输出目录即可），一律拒掉更省心。</p>
     */
    private static final Pattern DENIED_DELETE = Pattern.compile(
            "(^|[;&|(]\\s*)(rm|del|erase|rmdir|rd|unlink)\\s+\\S", Pattern.CASE_INSENSITIVE);

    private static final int MAX_STREAM_CHARS = 6000;
    private static final int MIN_TIMEOUT_SECONDS = 5;
    private static final int MAX_TIMEOUT_SECONDS = 300;
    /** 编译/打包类命令的默认超时：真实工程里 120 秒连一次全量编译都经常不够。 */
    private static final int SLOW_TIMEOUT_SECONDS = 300;

    private final CodeWorkspace workspace;
    private final int defaultTimeoutSeconds;
    private final Platform platform;

    public CodeCommandTool(CodeWorkspace workspace, int defaultTimeoutSeconds) {
        this(workspace, defaultTimeoutSeconds, Platform.current());
    }

    /** 指定平台的构造：单元测试用它把 Windows / Linux 两条分支都跑到。 */
    public CodeCommandTool(CodeWorkspace workspace, int defaultTimeoutSeconds, Platform platform) {
        this.workspace = workspace;
        this.defaultTimeoutSeconds = defaultTimeoutSeconds;
        this.platform = platform;
    }

    public Platform platform() {
        return platform;
    }

    @Tool(name = "run_command",
            value = "在工作目录里执行一条命令并返回退出码与输出。用来编译、跑测试、运行脚本（javac/java/mvn/node/python 等）。"
                    + "exitCode=0 才算成功；编译报错会原样返回，按报错改代码后再跑。"
                    + "命令语法跟着运行平台走：Windows 用 dir/type/findstr，Linux 用 ls/cat/grep，"
                    + "写错平台的命令会被拒绝并给出等价写法。"
                    + "只允许白名单内的程序，删除、推送、强制重置、提权一律拒绝。")
    public String runCommand(
            @P(description = "要执行的完整命令，例如 javac -encoding UTF-8 -d out src/Main.java 或 mvn -q test") String command,
            @P(description = "超时秒数；不传按命令自动（mvn/gradle/npm/node 默认 300 秒，其余 120 秒），上限 300") Integer timeoutSeconds,
            @P(description = "在哪个子目录执行，相对工作目录；留空表示工作目录根") String workdir,
            @P(description = "用哪个 shell：留空按平台自动（Windows=cmd，Linux=sh）；"
                    + "需要 POSIX 语法时填 bash（本机装了 Git Bash 才行），Windows 上也可填 powershell")
            String shell) {

        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("command 不能为空");
        }
        String cmd = command.trim();

        Platform.Shell chosen;
        try {
            chosen = Platform.Shell.resolve(platform, shell);
        } catch (IllegalArgumentException e) {
            // 这是"参数用错"，不是"命令被拒"：说清楚现在能用什么，让模型下一步就能走通
            return Json.of(Json.obj(
                    "ok", false,
                    "blocked", true,
                    "command", cmd,
                    "reason", e.getMessage(),
                    "platform", platform.label(),
                    "shell", platform.defaultShell().displayName(),
                    "hint", "不指定 shell 就用平台默认的 " + platform.defaultShell().displayName()));
        }

        String blocked = rejection(cmd, chosen);
        if (blocked != null) {
            Map<String, Object> result = Json.obj(
                    "ok", false,
                    "blocked", true,
                    "command", cmd,
                    "reason", blocked,
                    "platform", platform.label(),
                    "shell", chosen.displayName(),
                    "allowed", sortedAllowed(chosen));
            String suggestion = suggestion(cmd);
            if (suggestion != null) {
                result.put("suggestion", suggestion);
            }
            return Json.of(result);
        }

        Path cwd;
        try {
            workspace.ensureRoot();
            cwd = (workdir == null || workdir.isBlank() || ".".equals(workdir.trim()))
                    ? workspace.root()
                    : workspace.resolve(workdir);
        } catch (IOException e) {
            throw new IllegalStateException("创建工作目录失败：" + e.getMessage(), e);
        }
        if (!Files.isDirectory(cwd)) {
            throw new IllegalArgumentException("workdir 不存在：" + workdir + "；先 list_files 或先建目录");
        }

        int timeout = clamp(timeoutSeconds, cmd);
        long start = System.nanoTime();
        List<String> commandLine = chosen.commandLine(cmd);

        ProcessBuilder builder = new ProcessBuilder(commandLine).directory(cwd.toFile());
        builder.environment().put("PYTHONIOENCODING", "utf-8");
        builder.environment().put("PYTHONUTF8", "1");

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new IllegalStateException("启动命令失败（shell=" + chosen.displayName() + "）："
                    + e.getMessage(), e);
        }

        Capture out = new Capture(process.getInputStream());
        Capture err = new Capture(process.getErrorStream());
        Thread tOut = new Thread(out, "code-cmd-out");
        Thread tErr = new Thread(err, "code-cmd-err");
        tOut.setDaemon(true);
        tErr.setDaemon(true);
        tOut.start();
        tErr.start();

        boolean finished;
        try {
            finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            tOut.join(2_000);
            tErr.join(2_000);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("命令被中断", e);
        }

        long millis = (System.nanoTime() - start) / 1_000_000L;
        int exit = finished ? process.exitValue() : -1;
        String stdout = clip(out.text());
        String stderr = clip(err.text());

        Map<String, Object> result = Json.obj(
                "ok", finished && exit == 0,
                "command", cmd,
                "platform", platform.label(),
                "shell", chosen.displayName(),
                "executed", String.join(" ", commandLine),
                "cwd", workspace.display(cwd),
                "exitCode", exit,
                "timedOut", !finished,
                "durationMs", millis,
                "stdout", stdout.isEmpty() ? "(无输出)" : stdout,
                "stderr", stderr.isEmpty() ? "" : stderr);
        if (!finished) {
            result.put("note", "命令超过 " + timeout + " 秒被强制结束；可能需要更长时间，"
                    + "或它卡在等输入（交互式命令不要用，加 -B / --batch 之类的参数）");
        } else if (exit != 0) {
            result.put("note", "exitCode 非 0 表示失败。先看 stderr/stdout 里的第一条错误，"
                    + "改完代码再跑一次；不要直接向用户汇报成功");
        }
        return Json.of(result);
    }

    /** 返回 null 表示放行，否则是拒绝理由。 */
    private String rejection(String cmd, Platform.Shell shell) {
        String lower = cmd.toLowerCase(Locale.ROOT);
        for (String bad : DENIED) {
            if (lower.contains(bad)) {
                return "命令里含有被禁止的操作（" + bad.trim() + "）。这套工具只用于编译、测试、运行，"
                        + "不接受删除、推送、强制重置、提权类操作";
            }
        }
        if (DENIED_DELETE.matcher(cmd).find()) {
            return "不允许删除文件（rm/del/erase）。编译产物要清理就换个输出目录，"
                    + "或让工作目录重建；这套工具不提供删除能力";
        }
        String first = firstToken(cmd);
        if (first.isEmpty()) {
            return "无法识别要执行的程序";
        }
        String name = programName(first);
        if (shell.allowedPrograms().contains(name)) {
            return null;
        }
        // 平台差异单独说清楚：这是最高频的失败，别让它退化成一个笼统的"不允许"
        String suggestion = Platform.suggestionFor(platform, name);
        if (suggestion != null) {
            return "\"" + name + "\" 是另一个平台的命令，在当前环境（" + platform.label()
                    + " / " + shell.displayName() + "）里不可用。本平台的等价写法：" + suggestion;
        }
        return "不允许执行 \"" + name + "\"。可用的程序：" + String.join(", ", sortedAllowed(shell))
                + "。编译/运行/测试之外的活（下载、安装、改系统设置）请用别的方式";
    }

    /** 被拒时给一条能直接照抄的替代路径（能拼出来才给，拼不出来只留理由）。 */
    private String suggestion(String cmd) {
        String hint = Platform.suggestionFor(platform, programName(firstToken(cmd)));
        if (hint == null) {
            return null;
        }
        if (platform.isWindows() && Executables.available("bash")) {
            return hint + "；本机装了 bash，也可以保留原命令并传 shell=\"bash\"";
        }
        return hint;
    }

    /** 取第一个词并归一成程序名（去路径、去 .exe/.cmd 后缀）。 */
    private static String programName(String first) {
        String name = first.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.toLowerCase(Locale.ROOT);
        for (String suffix : new String[]{".exe", ".cmd", ".bat", ".ps1", ".com"}) {
            if (name.endsWith(suffix)) {
                name = name.substring(0, name.length() - suffix.length());
            }
        }
        return name;
    }

    /** 取第一个词；带引号时取引号内的内容。 */
    private static String firstToken(String cmd) {
        String trimmed = cmd.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (trimmed.charAt(0) == '"') {
            int end = trimmed.indexOf('"', 1);
            return end > 0 ? trimmed.substring(1, end) : trimmed.substring(1);
        }
        int space = trimmed.indexOf(' ');
        return space < 0 ? trimmed : trimmed.substring(0, space);
    }

    private static List<String> sortedAllowed(Platform.Shell shell) {
        List<String> list = new ArrayList<>(shell.allowedPrograms());
        list.sort(String::compareTo);
        return list;
    }

    /**
     * 超时按命令种类给默认值。
     *
     * <p>实测：demo-project 这种多模块工程 {@code mvn -o compile} 一次 61 秒，
     * 120 秒只够跑一次、跑不了"编译+测试"，而 {@code mvn test} 更久。模型又不会主动调大超时，
     * 它只会在超时后重试同一条命令。所以对长跑程序直接给更宽的默认值，短命令不受影响。</p>
     */
    private int clamp(Integer requested, String cmd) {
        int def = timeoutFor(cmd);
        int max = Math.max(MAX_TIMEOUT_SECONDS, def);
        int value = requested == null ? def : requested;
        return Math.max(MIN_TIMEOUT_SECONDS, Math.min(max, value));
    }

    private int timeoutFor(String cmd) {
        String name = programName(firstToken(cmd)).toLowerCase(Locale.ROOT);
        return switch (name) {
            case "mvn", "mvnw", "gradle", "gradlew", "npm", "yarn", "pnpm", "node", "dotnet", "go" ->
                    SLOW_TIMEOUT_SECONDS;
            default -> defaultTimeoutSeconds;
        };
    }

    /** 超长输出只留头尾：编译错误的头几条最有用，尾部常是"BUILD FAILURE"汇总。 */
    private static String clip(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.strip();
        if (trimmed.length() <= MAX_STREAM_CHARS) {
            return trimmed;
        }
        int head = (int) (MAX_STREAM_CHARS * 0.75);
        int tail = MAX_STREAM_CHARS - head;
        return trimmed.substring(0, head)
                + "\n…（中间省略 " + (trimmed.length() - MAX_STREAM_CHARS) + " 字符）…\n"
                + trimmed.substring(trimmed.length() - tail);
    }

    /**
     * 读一条流并解码。
     *
     * <p>为什么不在读取时直接按 UTF-8 解码：Windows 下 cmd 的子进程有可能吐 GBK（尤其
     * 没走 chcp 65001 的老工具），按 UTF-8 解会得到一片 {@code \uFFFD}。先收字节，
     * 出现替换字符再按 GBK 重解一次，比猜编码可靠。</p>
     */
    private static final class Capture implements Runnable {

        private final InputStream stream;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        Capture(InputStream stream) {
            this.stream = stream;
        }

        @Override
        public void run() {
            byte[] chunk = new byte[8192];
            int read;
            try {
                // 一直读到 EOF，但只保留前 256KB：既不会因为 pipe 写满把子进程卡死，也不会撑爆内存
                while ((read = stream.read(chunk)) > 0) {
                    if (buffer.size() < 256 * 1024) {
                        buffer.write(chunk, 0, read);
                    }
                }
            } catch (IOException ignored) {
                // 进程被强制结束时流会异常关闭，已读到的内容仍然有用
            }
        }

        String text() {
            byte[] raw = buffer.toByteArray();
            String utf8 = new String(raw, StandardCharsets.UTF_8);
            if (utf8.indexOf('\uFFFD') < 0) {
                return utf8;
            }
            try {
                return new String(raw, Charset.forName("GBK"));
            } catch (Exception e) {
                return utf8;
            }
        }
    }
}
