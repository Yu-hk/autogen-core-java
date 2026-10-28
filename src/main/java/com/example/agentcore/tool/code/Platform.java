package com.example.agentcore.tool.code;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 运行环境：这套命令工具到底跑在 Windows 还是 Linux 上，用哪个 shell。
 *
 * <p>为什么要把这件事单独抽出来：同一个模型在 Windows 和 Linux 上写出来的命令是两套语法。
 * 它天然倾向写 Linux 风格（{@code ls -l}、{@code cat}、{@code grep -rn}），而这些在
 * {@code cmd} 里一条都跑不通；反过来 {@code dir}、{@code type}、{@code findstr} 到了
 * Linux 上同样不存在。之前只有一句"Windows 下 shell 是 cmd"，模型仍然会写 {@code ls}，
 * 撞一次墙才改——每次都是白白烧掉一轮工具调用。</p>
 *
 * <p>所以这里做三件事：</p>
 * <ol>
 *   <li><b>认平台</b>：{@link #current()} 从 {@code os.name} 判断，可被
 *       {@code -Dtool.code.platform=linux} 覆盖（只在调试/演示时用）；</li>
 *   <li><b>选 shell</b>：Windows 默认 {@code cmd}，类 Unix 默认 {@code sh}；还允许模型
 *       在单次调用里显式指定 {@code powershell} / {@code bash}（装了什么才能用什么，见
 *       {@link Shell#resolve}）；</li>
 *   <li><b>翻译错用</b>：拿另一个平台的命令来时，不是丢一句"不允许"，而是直接给出本平台的
 *       等价写法（{@code ls} → {@code dir /b}），模型下一轮就不用试错了。</li>
 * </ol>
 *
 * <p>白名单跟着 <b>shell</b> 走而不是跟着平台走：PowerShell 里 {@code ls}/{@code cat}/
 * {@code type} 都是别名，能跑；而 cmd 里只有 {@code dir}/{@code type}。</p>
 */
public enum Platform {

    WINDOWS("Windows", "cmd"),
    LINUX("Linux", "sh"),
    MAC("macOS", "bash"),
    OTHER("未知平台", "sh");

    private final String label;
    private final String defaultShellName;

    Platform(String label, String defaultShellName) {
        this.label = label;
        this.defaultShellName = defaultShellName;
    }

    /** 当前进程所在平台。 */
    public static Platform current() {
        String override = System.getProperty("tool.code.platform");
        if (override != null && !override.isBlank()) {
            return parse(override);
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return WINDOWS;
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return MAC;
        }
        if (os.contains("nux") || os.contains("nix") || os.contains("aix")) {
            return LINUX;
        }
        return OTHER;
    }

    public static Platform parse(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "windows", "win", "win32", "win64" -> WINDOWS;
            case "linux" -> LINUX;
            case "mac", "macos", "darwin", "osx" -> MAC;
            default -> OTHER;
        };
    }

    public String label() {
        return label;
    }

    public boolean isWindows() {
        return this == WINDOWS;
    }

    /** 类 Unix 用 POSIX 风格命令。 */
    public boolean isUnixLike() {
        return this == LINUX || this == MAC || this == OTHER;
    }

    public Shell defaultShell() {
        return switch (defaultShellName) {
            case "cmd" -> Shell.CMD;
            case "bash" -> Executables.available("bash") ? Shell.BASH : Shell.SH;
            default -> Shell.SH;
        };
    }

    /**
     * 给系统提示词用的一段话。
     *
     * <p>要具体到"哪些命令能用、哪些不能用"，不要只说"你在 Windows 上"——
     * 模型对平台名有常识，缺的是"我现在这双手能抓什么"。</p>
     */
    public String promptHint() {
        Shell shell = defaultShell();
        return switch (this) {
            case WINDOWS -> """
                    - 当前平台是 **Windows**，默认 shell 是 cmd（可用 shell 参数切到 powershell，
                      装了 Git Bash 时也能切 bash）。
                    - cmd 里没有 ls / cat / grep / which / rm：看目录用 `dir /b`，看文件用 `type`，
                      搜内容用 `findstr /s /n /c:"文本" *.java`，找程序用 `where`。
                    - cmd 会自己处理 `&&`、`>`、`|`；但 **通配符 `*` 不会被 javac 展开**，
                      要编译多个文件就逐个列出（`javac a.java b.java`）或改用 `mvn`。
                    - 路径用 `\\` 或 `/` 都行，用 `/` 更省事（Java 工具都认）；中文输出已切到 UTF-8。
                    - 需要 shell 脚本语法（`$(...)`、`for f in *.java`）时，传 `shell="bash"`；
                      若返回"bash 不可用"就退回 cmd 写法，别反复试。
                    """;
            case LINUX, MAC, OTHER -> """
                    - 当前平台是 **%s**，默认 shell 是 %s。
                    - 用 POSIX 命令：`ls -l`、`cat`、`grep -rn`、`which`、`head -n 40`；
                      没有 dir / type / findstr / where。
                    - 通配符 `*` 由 shell 展开，`javac src/*.java` 可以直接用。
                    - `&&`、`|`、`$VAR`、`$(...)` 都可用。
                    - 改文件只走 edit_file / write_file：`sed -i`、`xargs`、`find -delete` 会被拒，
                      那不是"少一个工具"，是刻意的边界。
                    """.formatted(label, shell.name().toLowerCase(Locale.ROOT));
        };
    }

    /**
     * 在别的平台的命令与本平台写法之间做映射。
     *
     * <p>只管最高频的那几个：模型写错时，一次提示胜过它自己试三次。</p>
     */
    static String suggestionFor(Platform platform, String program) {
        Map<String, String> windows = Map.ofEntries(
                Map.entry("ls", "dir /b（只看名字）或 dir（带大小和时间）"),
                Map.entry("cat", "type 文件名"),
                Map.entry("head", "more 文件名（或 powershell 下 Get-Content -TotalCount 20）"),
                Map.entry("tail", "powershell 下 Get-Content -Tail 20"),
                Map.entry("grep", "findstr /s /n /c:\"文本\" *"),
                Map.entry("which", "where 程序名"),
                Map.entry("pwd", "cd（不带参数就打印当前目录）"),
                Map.entry("wc", "find /c /v \"\" 文件名（统计行数）"),
                Map.entry("cp", "copy 源 目标"),
                Map.entry("mv", "move 源 目标"),
                Map.entry("bash", "cmd 里没有 bash；装了 Git Bash 时传 shell=\"bash\""));
        Map<String, String> unix = Map.ofEntries(
                Map.entry("dir", "ls -l（或 ls -1 只看名字）"),
                Map.entry("type", "cat 文件名"),
                Map.entry("where", "which 程序名（或 command -v）"),
                Map.entry("findstr", "grep -rn \"文本\" ."),
                Map.entry("copy", "cp 源 目标"),
                Map.entry("move", "mv 源 目标"),
                Map.entry("cls", "clear"),
                Map.entry("powershell", "bash 或 sh；本平台没有 PowerShell"));
        Map<String, String> table = platform.isWindows() ? windows : unix;
        return table.get(program.toLowerCase(Locale.ROOT));
    }

    /**
     * 一次命令用哪个 shell。
     *
     * <p>模型可以显式指定，但只能指定本机真的装了的——否则就是"换了个写法继续失败"。</p>
     */
    public enum Shell {

        CMD("cmd", true),
        POWERSHELL("powershell", true),
        BASH("bash", false),
        SH("sh", false);

        private final String executable;
        private final boolean windowsOnly;

        Shell(String executable, boolean windowsOnly) {
            this.executable = executable;
            this.windowsOnly = windowsOnly;
        }

        public String executable() {
            return executable;
        }

        /** 是否 Windows 专有（cmd / powershell）。 */
        public boolean windowsOnly() {
            return windowsOnly;
        }

        /**
         * 解析模型传来的 shell 参数。
         *
         * @param requested {@code null}/空/{@code auto} 表示按平台默认
         * @throws IllegalArgumentException 指定的 shell 在当前平台/本机不可用
         */
        public static Shell resolve(Platform platform, String requested) {
            String value = requested == null ? "" : requested.trim().toLowerCase(Locale.ROOT);
            if (value.isEmpty() || "auto".equals(value) || "default".equals(value)) {
                return platform.defaultShell();
            }
            Shell wanted = switch (value) {
                case "cmd", "cmd.exe" -> CMD;
                case "powershell", "pwsh", "ps" -> POWERSHELL;
                case "bash", "git-bash", "sh" -> "sh".equals(value) ? SH : BASH;
                default -> throw new IllegalArgumentException("不认识的 shell：\"" + requested
                        + "\"；可用值：auto / cmd / powershell / bash / sh");
            };
            if (wanted.windowsOnly && !platform.isWindows()) {
                throw new IllegalArgumentException("当前平台是 " + platform.label() + "，没有 "
                        + wanted.executable + "；用 auto 或 bash/sh");
            }
            if (!Executables.available(wanted.executable)) {
                throw new IllegalArgumentException("本机找不到 " + wanted.executable
                        + "，不能用它执行命令；改用 auto（" + platform.defaultShell().executable + "），"
                        + "或者先确认这个程序在 PATH 里");
            }
            return wanted;
        }

        /** 组装成 ProcessBuilder 要的命令行。 */
        public List<String> commandLine(String command) {
            return switch (this) {
                // chcp 65001：cmd 默认 GBK，javac/mvn 的中文报错会变乱码，先切 UTF-8 代码页
                case CMD -> List.of("cmd", "/c", "chcp 65001>nul & " + command);
                // -NoProfile：别让用户的 profile 往输出里插横幅；Bypass：本机脚本策略不挡路
                case POWERSHELL -> List.of("powershell", "-NoProfile", "-NonInteractive",
                        "-ExecutionPolicy", "Bypass", "-Command", command);
                // -lc：登录 shell，把用户 PATH 带上（nvm/homebrew 装的 node 才找得到）
                case BASH -> List.of("bash", "-lc", command);
                case SH -> List.of("sh", "-c", command);
            };
        }

        /**
         * 这个 shell 下允许执行的程序。
         *
         * <p>跟着 shell 走，不跟着平台走：PowerShell 把 {@code ls}/{@code cat}/{@code type}
         * 都做成了别名，能在 Windows 上跑；cmd 没有。</p>
         */
        public Set<String> allowedPrograms() {
            Set<String> set = new LinkedHashSet<>(COMMON);
            switch (this) {
                case CMD -> set.addAll(WINDOWS_ONLY);
                case POWERSHELL -> {
                    set.addAll(WINDOWS_ONLY);
                    set.addAll(UNIX_ONLY);
                }
                case BASH, SH -> set.addAll(UNIX_ONLY);
            }
            return set;
        }

        public String displayName() {
            return executable;
        }
    }

    /**
     * 跨平台通用的程序：构建、运行时、版本控制。
     *
     * <p>这些在 Windows 和 Linux 上都有同名可执行文件（Python 例外，见 {@code python3}）。</p>
     */
    private static final Set<String> COMMON = new LinkedHashSet<>(List.of(
            // 构建与运行
            "mvn", "mvnw", "mvnw.cmd", "gradle", "gradlew", "javac", "java", "jar", "javap", "jlink",
            "node", "npm", "npx", "pnpm", "yarn", "tsc", "tsx", "deno", "bun",
            "python", "python3", "py", "pip", "pip3", "pytest", "uv", "poetry",
            "go", "cargo", "rustc", "dotnet", "gcc", "g++", "clang", "make", "cmake", "kotlinc",
            // 通用查看
            "echo", "cd", "tree", "more",
            // 版本控制（只读 + 本地提交）
            "git"));

    /**
     * 只有 cmd / PowerShell 有。
     *
     * <p>刻意不放 {@code wmic}、{@code set}、{@code attrib}：{@code wmic process call create}
     * 等于任意起进程，{@code set} 能把 PATH 改掉，{@code attrib} 能改隐藏/只读属性——
     * 它们都超出"编译、运行、看一眼"的范围。白名单的边界不是"能跑就行"，是"用得上"。</p>
     */
    private static final Set<String> WINDOWS_ONLY = new LinkedHashSet<>(List.of(
            "dir", "type", "where", "findstr", "cls", "copy", "move"));

    /**
     * 只有 POSIX shell 有。
     *
     * <p>{@code xargs} / {@code sed -i} / {@code find -delete} / {@code tee} 都不放：
     * 前两个能把白名单里的程序拼成删除动作（{@code ls | xargs rm} —— 首词是 xargs，
     * 白名单只检查首词，挡不住），后两个能改文件。文件改动一律走 {@code edit_file}，
     * 那条路有锚点校验，也有"改动后版本号"能让重复调用的判定失效。</p>
     */
    private static final Set<String> UNIX_ONLY = new LinkedHashSet<>(List.of(
            "ls", "cat", "head", "tail", "grep", "rg", "wc", "pwd", "which", "find",
            "du", "stat", "sort", "uniq", "cut",
            "printenv", "env", "uname", "date", "diff", "file", "realpath", "basename", "dirname"));

    /** 给"可用程序"列表用：合并当前平台两套命令，免得提示词里塞一堆用不上的。 */
    public Set<String> visiblePrograms() {
        Set<String> set = new LinkedHashSet<>(COMMON);
        if (isWindows()) {
            set.addAll(WINDOWS_ONLY);
            // Windows 上如果装了 Git Bash，unix 那套也算"可能可用"，但只在 bash 下允许，
            // 所以这里不并进去——避免模型以为 cmd 里能跑 ls。
        } else {
            set.addAll(UNIX_ONLY);
        }
        return set;
    }

}
