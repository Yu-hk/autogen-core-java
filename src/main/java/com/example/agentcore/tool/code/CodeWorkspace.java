package com.example.agentcore.tool.code;

import com.example.agentcore.support.AppConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 编码工具的工作区：若干"根"，每个根要么可写、要么只读。
 *
 * <p>最初这里只有一个写死的沙箱目录（{@code target/code-workspace}），外面再挂几个只读目录。
 * 实测下来这套权限模型只能干"从零写一个新工程"：真实需求几乎都是"改已有的仓库"，
 * 而只读目录改不动，就有个死结——模型只能把代码写进沙箱副本，用户还得手工合并回去。
 * 于是改成**由用户显式选择本地目录**（页面上选，人看到完整路径并确认，这就是授权），
 * 选中的目录直接成为可写根。</p>
 *
 * <p>两条边界仍然保留，因为它们是"模型不能自己扩大权限"的底线：</p>
 * <ol>
 *   <li>模型给的路径必须落在某个已授权的根内，{@code ../../} 与任意绝对路径都不行。</li>
 *   <li>系统目录（盘根、C:\Windows、/etc 等）不接受为工作区——防止手滑把整台机器交出去。</li>
 * </ol>
 *
 * <p>{@link #root()} 永远是第一个可写根（主根），工具里"默认从哪开始"都用它，
 * 这样选了 abrspro2 之后 {@code list_files} 不用带参数就能列出项目结构。</p>
 */
public final class CodeWorkspace {

    /** 一个被授权的根目录。writable=false 时只能读。 */
    public record Root(Path path, boolean writable) {

        public Root {
            path = path.toAbsolutePath().normalize();
        }

        @Override
        public String toString() {
            return path.toString().replace('\\', '/') + (writable ? "" : " (只读)");
        }
    }

    private final Path root;
    private final List<Root> roots;
    private final List<Path> readOnlyRoots;

    public CodeWorkspace(Path root) {
        this(root, List.of(), List.of());
    }

    /** 兼容旧调用：主根可写 + 一批只读目录。 */
    public CodeWorkspace(Path root, List<Path> readOnlyRoots) {
        this(root, readOnlyRoots, List.of());
    }

    /**
     * @param root          主根（必可写）
     * @param readOnlyRoots 额外只读根
     * @param writableRoots 额外可写根（用户额外选的目录）
     */
    public CodeWorkspace(Path root, List<Path> readOnlyRoots, List<Path> writableRoots) {
        List<Root> all = new ArrayList<>();
        Path primary = root.toAbsolutePath().normalize();
        all.add(new Root(primary, true));
        addAll(all, writableRoots, true);
        addAll(all, readOnlyRoots, false);
        // 主根固定排在第一位；后面的按加入顺序去重（同一目录既是只读又是可写时按可写算）
        this.roots = List.copyOf(all);
        this.root = primary;

        List<Path> ro = new ArrayList<>();
        for (Root r : all) {
            if (!r.writable()) {
                ro.add(r.path());
            }
        }
        this.readOnlyRoots = List.copyOf(ro);
    }

    private static void addAll(List<Root> target, List<Path> paths, boolean writable) {
        if (paths == null) {
            return;
        }
        for (Path p : paths) {
            if (p == null) {
                continue;
            }
            Path abs = p.toAbsolutePath().normalize();
            if (abs.equals(target.get(0).path())) {
                continue;
            }
            // 已经被登记过的目录不再重复：先登可写，后来的只读不覆盖
            boolean seen = false;
            for (Root existing : target) {
                if (existing.path().equals(abs)) {
                    seen = true;
                    break;
                }
            }
            if (!seen) {
                target.add(new Root(abs, writable));
            }
        }
    }

    /**
     * 按配置构造（服务端与提示词共用这一个入口，避免两处对不上）。
     *
     * <p>没有"用户选择"时用配置里的默认工作目录，这样命令行/测试行为不变。</p>
     */
    public static CodeWorkspace fromConfig() {
        return new CodeWorkspace(AppConfig.get().codeWorkspace(),
                AppConfig.get().codeReadOnlyDirs(),
                AppConfig.get().codeWritableDirs());
    }

    /**
     * 用户选了目录之后的工作区：选中的目录当主根（可写），配置里的只读目录继续挂载。
     *
     * <p>用户没选（{@code chosen} 为空）时退回 {@link #fromConfig()}。</p>
     */
    public static CodeWorkspace forUserSelection(Path chosen) {
        if (chosen == null) {
            return fromConfig();
        }
        return new CodeWorkspace(chosen, AppConfig.get().codeReadOnlyDirs(), AppConfig.get().codeWritableDirs());
    }

    /** 主根（第一个可写根）。工具里"不带参数时从哪开始"都用它。 */
    public Path root() {
        return root;
    }

    public List<Root> roots() {
        return roots;
    }

    public List<Path> readOnlyRoots() {
        return readOnlyRoots;
    }

    public List<Path> writableRoots() {
        List<Path> out = new ArrayList<>();
        for (Root r : roots) {
            if (r.writable()) {
                out.add(r.path());
            }
        }
        return List.copyOf(out);
    }

    /** 这个目录能不能写：用于"写入被拒"时给出准确原因（只读根 vs 完全越界）。 */
    public boolean writable(Path path) {
        Path abs = path.toAbsolutePath().normalize();
        for (Root r : roots) {
            if (r.writable() && abs.startsWith(r.path())) {
                return true;
            }
        }
        return false;
    }

    /** 确保主根存在（模型第一次跑命令时通常还不存在）。 */
    public Path ensureRoot() throws IOException {
        Files.createDirectories(root);
        return root;
    }

    /**
     * 解析一个**可写**路径：只能落在某个可写根里。
     *
     * <p>绝对路径接受，但必须命中已授权的可写根——否则模型随手写
     * {@code C:/Users/xxx/.ssh/id_rsa} 就能覆盖任意文件。</p>
     *
     * <p>异常信息会原样回灌给模型，所以文案要说人话：告诉它"哪里越界了、正确写法是什么"，
     * 而不是只丢一个 AccessDenied。</p>
     */
    public Path resolve(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("path 不能为空；应写相对工作目录的路径，例如 src/Main.java");
        }
        Path candidate = locate(raw);
        for (Root r : roots) {
            if (r.writable() && candidate.startsWith(r.path())) {
                return verifyNoSymlinkEscape(candidate, r.path(), raw);
            }
        }
        // 落在只读根里：别只说"越界"，模型会以为路径写错了，然后换个写法继续撞
        for (Root r : roots) {
            if (candidate.startsWith(r.path())) {
                throw new IllegalArgumentException("路径只读：" + raw + " 在只读目录 " + r.path()
                        + " 内，只能读不能写；要改就先复制到工作目录 " + root + " 再改"
                        + "（或在页面上把这个目录选为工作目录）");
            }
        }
        throw new IllegalArgumentException("路径越界：" + raw + " 不在任何已授权目录内"
                + "（当前可写：" + writableRoots() + "）；相对路径按主根解析，绝对路径必须是上述目录之一");
    }

    /**
     * 解析一个**只读**路径：可写根与只读根都放行。
     *
     * <p>绝对路径只在命中已授权根时才接受。</p>
     */
    public Path resolveRead(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("path 不能为空；应写相对工作目录的路径，或已授权目录下的绝对路径");
        }
        Path candidate = locate(raw);
        for (Root r : roots) {
            if (candidate.startsWith(r.path())) {
                return verifyNoSymlinkEscape(candidate, r.path(), raw);
            }
        }
        throw new IllegalArgumentException("路径越界：" + raw + " 不在任何已授权目录内"
                + "（当前可读：" + describe() + "）；相对路径按主根 " + root + " 解析"
                + "，要读外部目录请在页面上把它选为工作目录");
    }

    /** 相对路径按主根解析，绝对路径原样规范化。 */
    private Path locate(String raw) {
        String cleaned = raw.trim().replace('\\', '/');
        Path given = Path.of(cleaned);
        return (given.isAbsolute() ? given : root.resolve(cleaned)).normalize();
    }

    private Path verifyNoSymlinkEscape(Path candidate, Path allowedRoot, String raw) {
        // 已存在的路径再查一次软链接：normalize 只能挡住字面上的 ..
        if (Files.exists(candidate)) {
            try {
                Path realRoot = allowedRoot.toRealPath();
                Path real = candidate.toRealPath();
                if (!real.startsWith(realRoot)) {
                    throw new IllegalArgumentException("路径越界：" + raw + " 通过软链接指向了允许范围之外");
                }
            } catch (IOException e) {
                throw new IllegalStateException("解析路径失败：" + raw + "（" + e.getMessage() + "）", e);
            }
        }
        return candidate;
    }

    /** 相对主根的展示，统一用正斜杠，避免 Windows 反斜杠被模型误当成转义。 */
    public String relative(Path path) {
        return root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    /**
     * 展示用路径：主根内给相对路径，其他根内给绝对路径（模型要拿它再传回来）。
     *
     * <p>不能直接用 {@link #relative}——别的根不在主根下，relativize 会抛异常。</p>
     */
    public String display(Path path) {
        Path abs = path.toAbsolutePath().normalize();
        if (abs.startsWith(root)) {
            String rel = root.relativize(abs).toString().replace('\\', '/');
            return rel.isEmpty() ? "." : rel;
        }
        return abs.toString().replace('\\', '/');
    }

    /** 给人/给提示词看的一行描述。 */
    public String describe() {
        StringBuilder sb = new StringBuilder("主工作目录（可写）：").append(root);
        for (Root r : roots) {
            if (r.path().equals(root)) {
                continue;
            }
            sb.append("；").append(r.writable() ? "额外可写：" : "只读：").append(r.path());
        }
        return sb.toString();
    }

    /**
     * 拒绝把系统目录当工作区。
     *
     * <p>用户选目录是显式授权，但"选到 C:\ 或 C:\Windows"基本都是手滑或误解，
     * 拦下来比事后追悔便宜。只挡这几类，不做全面白名单——否则真实项目路径
     * 动不动就要加例外，用户会绕过这道检查。</p>
     */
    /**
     * 路径是否落在某个允许根之下（含根本身）。
     *
     * <p>Windows 下 {@link Path#startsWith(Path)} 按 name 逐段比较且忽略大小写，
     * 所以 {@code d:/work/x} 能正确命中 {@code D:/work}，不需要额外做大小写归一。</p>
     */
    public static boolean underRoot(Path path, Path root) {
        if (path == null || root == null) {
            return false;
        }
        return path.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize());
    }

    /** 路径是否落在任意一个允许根之下。 */
    public static boolean underAnyRoot(Path path, List<Path> roots) {
        if (roots == null) {
            return false;
        }
        for (Path root : roots) {
            if (underRoot(path, root)) {
                return true;
            }
        }
        return false;
    }

    public static String rejectReason(Path path) {
        if (path == null) {
            return "路径为空";
        }
        Path abs = path.toAbsolutePath().normalize();
        if (abs.getParent() == null) {
            return "不能把盘符根目录当工作区：" + abs;
        }
        String p = abs.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home", "").replace('\\', '/').toLowerCase(Locale.ROOT);
        if (p.equals(home)) {
            return "不能把用户主目录本身当工作区（会把桌面、文档、.ssh 全交出去）：" + abs
                    + "；请选具体项目目录";
        }
        if (!Files.isDirectory(abs)) {
            return "不是目录或不存在：" + abs;
        }
        String[] blocked = Platform.current() == Platform.WINDOWS
                ? new String[]{"/windows", "/program files", "/program files (x86)", "/programdata",
                "/system volume information", "/$recycle.bin", "/perflogs", "/windows.old"}
                : new String[]{"/etc", "/usr", "/bin", "/sbin", "/lib", "/boot", "/dev", "/proc", "/sys", "/var"};
        for (String b : blocked) {
            if (p.equals(b) || p.startsWith(b + "/")) {
                return "不能把系统目录当工作区：" + abs;
            }
        }
        return null;
    }
}
