package com.example.agentcore.tool.code;

import com.example.agentcore.support.AppConfig;
import com.example.agentcore.support.Json;
import com.example.agentcore.support.RetryableToolError;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文件类编码工具：看目录、读文件、写文件、改文件、搜代码。
 *
 * <p>五个工具对应模型写代码时的五件事，少一个都跑不通：</p>
 * <ul>
 *   <li>不看目录就写，会往一个已经有 {@code src/main/java} 的工程里凭空造目录；</li>
 *   <li>不读原文就改，{@code edit_file} 的锚点必然对不上；</li>
 *   <li>只有 {@code write_file} 没有 {@code edit_file}，改一行要重写整个文件，
 *       长文件必被截断（模型输出有上限），越改越错；</li>
 *   <li>没有 {@code search_code}，模型只能靠"猜文件名"逐个去读，十几次工具调用全耗在这上面。</li>
 * </ul>
 *
 * <p>所有返回值都是 JSON。失败分两种：<b>参数/路径错误</b>抛异常（{@code ToolRegistry} 包成
 * {@code ERROR: ...}，会触发熔断计数）；<b>业务上没命中</b>（比如搜索 0 条）返回
 * {@code ok=true, count=0}——那不是工具的错，模型该自己换关键词。</p>
 */
public class CodeFilesTool implements com.example.agentcore.agent.MutatingTool {

    /** 单次最多读多少行：再大就会把上下文窗口吃掉，而模型真正要看的部分通常很小。 */
    private static final int MAX_READ_LINES = 800;
    private static final int DEFAULT_READ_LINES = 200;

    /** 列目录与搜索的上限，防止指着工程根目录一次吐几百 KB。 */
    private static final int MAX_LIST_ENTRIES = 200;

    /**
     * list_files 默认只列一层。
     *
     * <p>实测内网纯 CPU 跑 qwen2.5:7b，模型每处理 1 万 token 的 prompt 要几分钟；
     * 一次性递归吐 200 条（约 20KB）既看不懂结构，又让后面每次模型调用都要重新嚼一遍。
     * 一层一层走，单次结果只有几百字节，模型反而看得清。</p>
     */
    private static final int DEFAULT_LIST_DEPTH = 1;
    /** 层数按「相对起点的第几层」算：直接子项是第 1 层，最多看到第 5 层。 */
    private static final int MAX_LIST_DEPTH = 5;
    private static final int MAX_SEARCH_MATCHES = 60;

    /** 读文件大小上限：超过就当二进制/数据文件，不给读。 */
    private static final long MAX_READ_BYTES = 2L * 1024 * 1024;

    /** read_dir 的默认与上限：一次读整个目录，预算比单文件更紧，否则一调就把上下文吃光。 */
    private static final int DEFAULT_DIR_FILES = 20;
    private static final int MAX_DIR_FILES = 50;
    private static final int DEFAULT_DIR_FILE_LINES = 120;
    private static final int MAX_DIR_FILE_LINES = 400;
    private static final int MAX_DIR_CHARS = 60_000;

    /** 收集候选文件的上限：目录再大也只 walk 到这里，避免指向根目录时白跑一遍全盘。 */
    private static final int MAX_DIR_CANDIDATES = 600;

    /** 生成物与依赖目录：列出来只是噪音，还会把 200 条配额吃光。 */
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".svn", ".idea", ".vscode", ".mvn", ".gradle",
            "target", "build", "out", "dist", "node_modules", "__pycache__",
            ".venv", "venv", "vendor");

    private final CodeWorkspace workspace;
    /**
     * 是否要求"写已有文件必须带 sha256"。
     *
     * <p>做成构造参数而不是工具内部读全局配置：这样测试能单独验证"带保护"和"不带保护"
     * 两种行为，生产侧由 {@link CodeTools} 把配置值传进来。</p>
     */
    private final boolean writeProtect;

    public CodeFilesTool(CodeWorkspace workspace) {
        this(workspace, true);
    }

    public CodeFilesTool(CodeWorkspace workspace, boolean writeProtect) {
        this.workspace = workspace;
        this.writeProtect = writeProtect;
    }

    /**
     * 声明哪两个工具会改磁盘。
     *
     * <p>没有这一句，模型"改完代码再用同样的命令编译一次"会被 {@code ChatAgent} 当成
     * 重复调用拦掉——参数真的一模一样，但文件已经变了，结果完全不同。实测这会让模型
     * 去改命令加假参数来绕过，白烧 6 次调用。</p>
     */
    @Override
    public Set<String> mutatingTools() {
        return Set.of("write_file", "edit_file");
    }

    // ---------- 看 ----------

    @Tool(name = "list_files",
            value = "列出工作目录里的文件与子目录（默认只列当前这一层，自动跳过 .git/target/node_modules 等）。"
                    + "动手前先用它看清工程结构；dir 传 \".\" 或留空表示工作目录根；"
                    + "depth 控制列几层（默认 1，最多 4），想看模块内部传 2。")
    public String listFiles(
            @P(description = "要列出的子目录，相对工作目录；传 \".\" 或留空表示根目录") String dir,
            @P(description = "列几层：默认 1（只看直接子项），最多 4；看模块内部结构时传 2") Integer depth) {

        Path start;
        try {
            workspace.ensureRoot();
            start = (dir == null || dir.isBlank() || ".".equals(dir.trim()))
                    ? workspace.root()
                    : workspace.resolveRead(dir);
        } catch (IOException e) {
            throw new IllegalStateException("创建工作目录失败：" + e.getMessage(), e);
        }
        if (!Files.isDirectory(start)) {
            throw new IllegalArgumentException("不是目录：" + dir + "；先用 list_files 看一眼有什么");
        }

        int levels = clamp(depth == null ? DEFAULT_LIST_DEPTH : depth, 1, MAX_LIST_DEPTH);

        List<Object> entries = new ArrayList<>();
        boolean truncated = false;
        // 广度优先，而不是 Files.walkFileTree 的深度优先：截断时也得先给浅层结构。
        // 实测 demo-project（915 个文件）在根目录深度优先走，前 200 条全耗在 .workbuddy 一个目录里，
        // 模型看到的是一堆散文件、等于没看到工程结构，于是它又用同样的参数调了一次——
        // 结果被重复调用拦截，白等一轮，这一轮在内网纯 CPU 上要几分钟。
        Deque<Pending> queue = new ArrayDeque<>();
        queue.add(new Pending(start, 0));
        while (!queue.isEmpty() && !truncated) {
            Pending current = queue.poll();
            List<Path> children;
            try (var stream = Files.list(current.dir())) {
                children = stream.sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
            } catch (IOException e) {
                // 单个目录没权限就跳过，不能因为一棵子树读不了就把整个工程结构吞掉
                continue;
            }
            for (Path child : children) {
                boolean directory = Files.isDirectory(child);
                if (directory && SKIP_DIRS.contains(child.getFileName().toString())) {
                    continue;
                }
                if (entries.size() >= MAX_LIST_ENTRIES) {
                    truncated = true;
                    break;
                }
                entries.add(entry(child, directory ? "dir" : "file", directory ? 0 : sizeOf(child)));
                if (directory && current.level() + 1 < levels) {
                    queue.add(new Pending(child, current.level() + 1));
                }
            }
        }

        entries.sort(Comparator.comparing(o -> String.valueOf(((Map<?, ?>) o).get("path"))));

        Map<String, Object> out = Json.obj(
                "ok", true,
                "root", workspace.root().toString(),
                "dir", workspace.display(start),
                "depth", levels,
                "count", entries.size(),
                "truncated", truncated,
                "entries", entries);
        if (truncated) {
            out.put("hint", "只列了前 " + MAX_LIST_ENTRIES + " 条（已按浅层优先）。"
                    + "想看某个子目录就传 dir 指定它，例如 dir=\"src/main/java\"；参数不同不会被判为重复调用。");
        } else if (levels < MAX_LIST_DEPTH && entries.stream()
                .anyMatch(e -> "dir".equals(((Map<?, ?>) e).get("type")))) {
            out.put("hint", "只列了 " + levels + " 层。要更深就传 depth="
                    + Math.min(levels + 1, MAX_LIST_DEPTH) + "，或直接用 dir 指定子目录。");
        }
        return Json.of(out);
    }

    /** 广度优先队列里的待列目录：level 是它相对起点的层数（起点 0）。 */
    private record Pending(Path dir, int level) {
    }

    /** 读文件大小失败不影响列表本身，给 0 即可。 */
    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0L;
        }
    }

    private Map<String, Object> entry(Path path, String type, long size) {
        Map<String, Object> item = Json.obj("path", workspace.display(path), "type", type);
        if ("file".equals(type)) {
            item.put("size", size);
        }
        return item;
    }

    @Tool(name = "read_file",
            value = "读一个文本文件，返回带行号的内容（行号只用于定位，写回时不要带上行号）。"
                    + "默认从第 1 行读 200 行；文件长就先读头部，再按需要读指定区间。"
                    + "知道方法名/类名时用 around 直接定位：会返回该符号的完整方法体（含大括号配对），"
                    + "比按行号猜区间准得多。返回值里的 sha256 是改这个文件前必须带给 edit_file 的凭据。")
    public String readFile(
            @P(description = "文件路径，相对工作目录，例如 src/main/java/demo/LruCache.java；也接受已授权目录的绝对路径") String path,
            @P(description = "起始行号，从 1 开始；不传默认 1。给了 around 时忽略") Integer startLine,
            @P(description = "最多读多少行；不传默认 200，上限 800。给了 around 时忽略") Integer maxLines,
            @P(description = "方法名或类名，例如 calculateAverage、Tb23ProgramServiceImpl；"
                    + "只写名字，不要写括号。命中后返回整个方法体，找不到时用 search_code 确认名字") String around) {

        Path file = workspace.resolveRead(path);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("文件不存在：" + path + "；先用 list_files 确认路径");
        }
        long size;
        byte[] raw;
        try {
            size = Files.size(file);
            if (size > MAX_READ_BYTES) {
                throw new IllegalArgumentException("文件太大（" + size + " 字节），超过 2MB 不给读；"
                        + "用 search_code 定位要看的片段");
            }
            raw = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new IllegalStateException("读取文件信息失败：" + e.getMessage(), e);
        }

        List<String> lines;
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(raw))
                    .toString();
            lines = splitLines(text);
        } catch (java.nio.charset.CharacterCodingException e) {
            throw new IllegalArgumentException("不是 UTF-8 文本（可能是二进制或 GBK 文件）：" + path);
        }

        String sha = sha256(raw);
        if (lines.isEmpty()) {
            return Json.of(Json.obj("ok", true, "path", path, "lines", 0, "sha256", sha,
                    "content", "(空文件)", "truncated", false));
        }

        int from;
        int to;
        boolean bySymbol = false;
        String symbol = null;
        int symbolCount = 0;
        boolean cutByLimit = false;

        if (around != null && !around.isBlank()) {
            String name = around.trim().replaceAll("[()\\s]", "");
            List<int[]> ranges = symbolRanges(lines, name);
            if (ranges.isEmpty()) {
                throw new RetryableToolError("在 " + path + " 里找不到符号 " + name
                        + "（只写名字，别带括号/参数类型）。先用 search_code 搜 \"" + name
                        + "\" 确认它在这个文件里，或者改用 startLine+maxLines 按行读");
            }
            symbolCount = ranges.size();
            from = ranges.get(0)[0];
            to = ranges.get(ranges.size() - 1)[1];
            // 重载常常相隔十几行：一并给出去，比让模型再猜一次"到底是哪个重载"便宜
            if (to - from + 1 > MAX_READ_LINES) {
                to = from + MAX_READ_LINES - 1;
                cutByLimit = true;
            }
            bySymbol = true;
            symbol = name;
        } else {
            from = Math.max(1, startLine == null ? 1 : startLine);
            int want = Math.max(1, Math.min(MAX_READ_LINES, maxLines == null ? DEFAULT_READ_LINES : maxLines));
            if (from > lines.size()) {
                throw new IllegalArgumentException("起始行 " + from + " 超过文件总行数 " + lines.size()
                        + "；文件只有 " + lines.size() + " 行");
            }
            to = Math.min(lines.size(), from - 1 + want);
        }

        StringBuilder sb = new StringBuilder();
        for (int i = from; i <= to; i++) {
            sb.append(String.format("%4d| %s%n", i, lines.get(i - 1)));
        }

        // 按符号读时"后面还有行"不算截断：模型要的是这个方法，文件剩下的部分它没要
        boolean truncated = cutByLimit || (!bySymbol && to < lines.size());
        Map<String, Object> out = Json.obj(
                "ok", true,
                "path", path,
                "totalLines", lines.size(),
                "from", from,
                "to", to,
                "truncated", truncated,
                "sha256", sha,
                "content", sb.toString());
        if (bySymbol) {
            out.put("symbol", symbol);
            out.put("symbolRange", from + "-" + to);
            out.put("symbolMatches", symbolCount);
        }
        return Json.of(out);
    }

    /**
     * 按符号名找它的行范围：类/接口/枚举 → 整个类型体；方法 → 整个方法体。
     *
     * <p>实测里最费轮次的不是"读文件"，是"读 4119 行文件里的某一段"：模型按行号猜区间，
     * 猜偏了就得再读一次，两次调用换一段上下文。给个名字直接拿整个方法体，一次就对。</p>
     *
     * <p>实现上不追求精确的 Java 语法分析（那要上编译器前端），只做两件事：
     * 找到声明行、按大括号配对到收尾。注释和字符串里的括号先剔掉，避免配对错位。</p>
     */
    private static List<int[]> symbolRanges(List<String> lines, String name) {
        // 先找类型声明（class/interface/enum/record）：整块给出去
        Pattern typeDecl = Pattern.compile("\\b(?:class|interface|enum|record)\\s+"
                + Pattern.quote(name) + "\\b");
        // 再找方法声明：名字后面紧跟左括号
        Pattern methodDecl = Pattern.compile("(?:^|[^\\w$.])" + Pattern.quote(name) + "\\s*\\(");

        List<int[]> out = new ArrayList<>();
        int i = 0;
        while (i < lines.size()) {
            String code = stripNoise(lines.get(i));
            if (code.isBlank()) {
                i++;
                continue;
            }
            boolean isType = typeDecl.matcher(code).find();
            boolean isMethod = !isType && methodDecl.matcher(code).find()
                    && !isCallOrKeyword(code.trim(), name);
            if (!isType && !isMethod) {
                i++;
                continue;
            }
            int[] range = braceRange(lines, i);
            if (range == null) {
                i++;
                continue;
            }
            out.add(range);
            // 直接跳到这个符号结尾：不然方法体里对自身的递归调用会被当成又一个声明
            i = Math.max(i + 1, range[1]);
        }
        return out;
    }

    /**
     * 从声明行开始配对大括号，返回到收尾那一行。
     *
     * <p>声明后没有大括号（接口方法、抽象方法、字段）：给前后各 4 行足够看上下文。</p>
     */
    private static int[] braceRange(List<String> lines, int declLine) {
        for (int i = declLine; i < lines.size(); i++) {
            String code = stripNoise(lines.get(i));
            int idx = code.indexOf('{');
            if (idx >= 0) {
                int depth = 0;
                for (int j = i; j < lines.size(); j++) {
                    String body = stripNoise(lines.get(j));
                    for (int k = (j == i ? idx : 0); k < body.length(); k++) {
                        char c = body.charAt(k);
                        if (c == '{') {
                            depth++;
                        } else if (c == '}') {
                            depth--;
                            if (depth == 0) {
                                return new int[]{declLine + 1, j + 1};
                            }
                        }
                    }
                }
                return null;
            }
            // 还没碰到 { 就先遇到分号：声明到此结束（接口方法、字段声明）
            if (code.contains(";")) {
                return new int[]{Math.max(1, declLine - 3), Math.min(lines.size(), i + 1 + 3)};
            }
        }
        return null;
    }

    /**
     * 这一行是不是"调用"或者控制语句，而不是声明。
     *
     * <p>不加这一层，{@code around} 会被文件里第一处 {@code return average(a, b);} 抢走，
     * 返回一段莫名其妙的行区间——模型还以为自己拿到了方法体。</p>
     */
    private static boolean isCallOrKeyword(String line, String name) {
        String[] keywords = {"return ", "if ", "for ", "while ", "switch ", "catch ", "else",
                "throw ", "new ", "assert ", "case ", "synchronized (", "*", "/*", "@", "//"};
        for (String kw : keywords) {
            if (line.startsWith(kw)) {
                return true;
            }
        }
        // 纯调用：name(...) / x.name(...) / this.name(...)，前面没有返回类型
        return line.matches("^(?:(?:this|super)\\.|[\\w$]+\\s*\\.\\s*)?"
                + Pattern.quote(name) + "\\s*\\(.*");
    }

    /** 去掉行内注释与字符串字面量，只留结构性字符，避免括号计数被干扰。 */
    private static String stripNoise(String line) {
        StringBuilder sb = new StringBuilder(line.length());
        boolean inStr = false;
        boolean inChar = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            char prev = i == 0 ? ' ' : line.charAt(i - 1);
            if (!inStr && !inChar && c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
                break;
            }
            if (!inChar && c == '"' && prev != '\\') {
                inStr = !inStr;
                sb.append(' ');
                continue;
            }
            if (!inStr && c == '\'' && prev != '\\') {
                inChar = !inChar;
                sb.append(' ');
                continue;
            }
            sb.append(inStr || inChar ? ' ' : c);
        }
        return sb.toString();
    }

    // ---------- 批量读 ----------

    @Tool(name = "read_dir",
            value = "把一个目录下的文本文件一次读完：返回每个文件的路径与带行号的内容，不用逐个 read_file。"
                    + "支持 glob 过滤（*.java、**/*.ts、pom.xml），自动跳过二进制、超大文件与 target/node_modules 等构建目录。"
                    + "想看整个模块、抄一份配置、读一组小文件时用它；只想看文件名用 list_files。")
    public String readDir(
            @P(description = "目录路径，相对工作目录；也接受只读目录下的绝对路径。传 \".\" 或留空表示工作目录根") String dir,
            @P(description = "只读匹配这个通配符的文件，例如 *.java、**/*.ts、pom.xml；留空表示所有文本文件") String glob,
            @P(description = "最多读几个文件；不传默认 20，上限 50") Integer maxFiles,
            @P(description = "每个文件最多读多少行；不传默认 120，上限 400") Integer maxLinesPerFile) {

        Path start;
        try {
            workspace.ensureRoot();
            start = (dir == null || dir.isBlank() || ".".equals(dir.trim()))
                    ? workspace.root()
                    : workspace.resolveRead(dir);
        } catch (IOException e) {
            throw new IllegalStateException("创建工作目录失败：" + e.getMessage(), e);
        }
        if (!Files.isDirectory(start)) {
            throw new IllegalArgumentException("不是目录：" + dir
                    + "；读单个文件用 read_file，看有哪些文件用 list_files");
        }

        int fileLimit = clamp(maxFiles == null ? DEFAULT_DIR_FILES : maxFiles, 1, MAX_DIR_FILES);
        int lineLimit = clamp(maxLinesPerFile == null ? DEFAULT_DIR_FILE_LINES : maxLinesPerFile, 1, MAX_DIR_FILE_LINES);
        java.util.function.Predicate<Path> accept = accept(start, glob);
        List<Path> candidates = collect(start, accept);

        List<Object> files = Json.arr();
        List<Object> skipped = Json.arr();
        int[] budget = {MAX_DIR_CHARS};
        boolean budgetOut = false;
        int skippedTotal = 0;

        for (Path f : candidates) {
            if (files.size() >= fileLimit || budgetOut) {
                break;
            }
            long size;
            try {
                size = Files.size(f);
            } catch (IOException e) {
                skippedTotal++;
                addSkipped(skipped, workspace.display(f), "读不到文件信息：" + e.getMessage());
                continue;
            }
            if (size > MAX_READ_BYTES) {
                skippedTotal++;
                addSkipped(skipped, workspace.display(f), "太大（" + size + " 字节 > 2MB），用 read_file 读指定行区间");
                continue;
            }
            byte[] raw;
            try {
                raw = Files.readAllBytes(f);
            } catch (IOException e) {
                skippedTotal++;
                addSkipped(skipped, workspace.display(f), "读取失败：" + e.getMessage());
                continue;
            }
            if (looksBinary(raw)) {
                skippedTotal++;
                addSkipped(skipped, workspace.display(f), "看起来是二进制文件");
                continue;
            }
            String text;
            try {
                text = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(raw))
                        .toString();
            } catch (java.nio.charset.CharacterCodingException e) {
                skippedTotal++;
                addSkipped(skipped, workspace.display(f), "不是 UTF-8 文本");
                continue;
            }

            List<String> lines = splitLines(text);
            StringBuilder sb = new StringBuilder();
            int shown = 0;
            boolean outOfBudget = false;
            for (int i = 0; i < lines.size() && shown < lineLimit; i++) {
                String rendered = String.format("%4d| %s%n", i + 1, lines.get(i));
                if (rendered.length() > budget[0]) {
                    outOfBudget = true;
                    budgetOut = true;
                    break;
                }
                sb.append(rendered);
                budget[0] -= rendered.length();
                shown++;
            }
            files.add(Json.obj(
                    "path", workspace.display(f),
                    "bytes", size,
                    "lines", shown,
                    "totalLines", lines.size(),
                    "truncated", shown < lines.size(),
                    "cutBy", shown >= lines.size() ? "none" : (outOfBudget ? "budget" : "maxLines"),
                    "content", sb.toString()));
        }

        int notRead = Math.max(0, candidates.size() - files.size() - skippedTotal);
        return Json.of(Json.obj(
                "ok", true,
                "root", workspace.root().toString(),
                "dir", workspace.display(start),
                "glob", glob == null || glob.isBlank() ? "*" : glob.trim(),
                "count", files.size(),
                "candidates", candidates.size(),
                "notRead", notRead,
                "skippedTotal", skippedTotal,
                "truncated", notRead > 0 || skippedTotal > 0,
                "budgetExhausted", budgetOut,
                "hint", notRead > 0
                        ? "还有 " + notRead + " 个匹配文件没读：用 glob 缩小范围、换子目录，或调大 maxFiles"
                        : "",
                "files", files,
                "skipped", skipped));
    }

    private static void addSkipped(List<Object> skipped, String path, String reason) {
        // 跳过的名单只是提示，不必全量回灌：30 条足够说明问题，多了纯粹吃上下文
        if (skipped.size() < 30) {
            skipped.add(Json.obj("path", path, "reason", reason));
        }
    }

    /** 通配符语义：带斜杠按目录相对路径匹配，不带斜杠只比文件名（{@code *.java} 才符合直觉）。 */
    private java.util.function.Predicate<Path> accept(Path start, String glob) {
        if (glob == null || glob.isBlank()) {
            return p -> true;
        }
        String g = glob.trim().replace('\\', '/');
        PathMatcher matcher;
        try {
            matcher = start.getFileSystem().getPathMatcher("glob:" + g);
        } catch (java.util.regex.PatternSyntaxException e) {
            throw new IllegalArgumentException("glob 写法不对：" + glob
                    + "；常用形式是 *.java、*.ts、**/*.java、pom.xml");
        }
        boolean fullPath = g.contains("/");
        return p -> fullPath ? matcher.matches(start.relativize(p)) : matcher.matches(p.getFileName());
    }

    /**
     * 收集候选文件，浅的先排。
     *
     * <p>排序不是美观问题：读整个模块时 {@code pom.xml}/{@code README} 这种入口文件在浅层，
     * 按字母序排它们可能排在一堆 {@code src/main/java/...} 后面，预算一满就正好被挤掉。</p>
     */
    private List<Path> collect(Path start, java.util.function.Predicate<Path> accept) {
        List<Path> found = new ArrayList<>();
        try {
            Files.walkFileTree(start, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                    if (!d.equals(start) && SKIP_DIRS.contains(d.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) {
                    if (accept.test(f)) {
                        found.add(f);
                    }
                    return found.size() >= MAX_DIR_CANDIDATES
                            ? FileVisitResult.TERMINATE
                            : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path f, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("遍历目录失败：" + e.getMessage(), e);
        }
        found.sort(Comparator
                .comparingInt((Path p) -> start.relativize(p).getNameCount())
                .thenComparing(p -> p.toString().toLowerCase(java.util.Locale.ROOT)));
        return found;
    }

    private static boolean looksBinary(byte[] raw) {
        int probe = Math.min(raw.length, 8192);
        for (int i = 0; i < probe; i++) {
            if (raw[i] == 0) {
                return true;
            }
        }
        return false;
    }

    /** 按行切分，去掉行尾 \\r 与末尾那个由结尾换行产生的空行（与 Files.readAllLines 对齐）。 */
    private static List<String> splitLines(String text) {
        if (text.isEmpty()) {
            return List.of();
        }
        String[] parts = text.split("\n", -1);
        List<String> lines = new ArrayList<>(parts.length);
        for (String part : parts) {
            lines.add(part.endsWith("\r") ? part.substring(0, part.length() - 1) : part);
        }
        if (!text.isEmpty() && text.endsWith("\n") && !lines.isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String sha256(byte[] raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 写完之后把 git 视角的改动回给模型。
     *
     * <p>模型看不到文件系统以外的东西，"改完了"对它来说只是一次返回 ok。给了 diff 统计，
     * 它才有机会发现"我以为改了一行，其实删了 300 行"这种事故。工作目录不是 git 仓库时
     * 返回空 Map，不报错——不是每个目录都归 git 管。</p>
     */
    private Map<String, Object> gitState(Path file) {
        try {
            Path dir = Files.isDirectory(file) ? file : file.getParent();
            if (dir == null) {
                return Map.of();
            }
            String top = git(dir.toFile(), "rev-parse", "--show-toplevel").trim();
            if (top.isEmpty()) {
                return Map.of();
            }
            Path toplevel = Path.of(top).toAbsolutePath().normalize();
            String rel = toplevel.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');

            Map<String, Object> info = Json.obj();
            info.put("repo", toplevel.toString().replace('\\', '/'));
            info.put("file", rel);

            // -C 必须是仓库根：传文件所在目录会让 "-- <rel>" 被当成相对该目录的路径，
            // 于是 git 去找 src/main/java/abrs/util/src/main/java/...，回一句
            // "could not open directory" 还带着 exit 0，看起来像成功
            String status = git(toplevel.toFile(), "status", "--porcelain", "--", rel).trim();
            if (!status.isEmpty()) {
                info.put("status", status.split("\n")[0].trim());
            }
            String numstat = git(toplevel.toFile(), "diff", "--numstat", "--", rel).trim();
            if (!numstat.isEmpty()) {
                String[] parts = numstat.split("\n")[0].split("\t");
                if (parts.length >= 2) {
                    info.put("added", parts[0]);
                    info.put("deleted", parts[1]);
                }
            }
            return info;
        } catch (Exception e) {
            // git 不在 PATH、目录不属于任何仓库、命令超时：都不该让写入失败
            return Map.of();
        }
    }

    private String git(java.io.File dir, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("-C");
        cmd.add(dir.getAbsolutePath());
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        try {
            Process p = pb.start();
            String out;
            try (var in = p.getInputStream()) {
                out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "";
            }
            if (p.exitValue() != 0) {
                // rev-parse 在非仓库里返回非 0，属于正常情况
                return "";
            }
            // stderr 合并进来了（不合并会因缓冲区满把 git 卡住），而 Windows 上
            // autocrlf 必打 "warning: LF will be replaced by CRLF"，混在 numstat 的
            // 表格前面会把 "1\t1\tA.java" 顶到第二行，解析出空值
            StringBuilder clean = new StringBuilder();
            for (String line : out.split("\n")) {
                if (line.startsWith("warning:") || line.contains("could not open directory")) {
                    continue;
                }
                clean.append(line).append('\n');
            }
            return clean.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** 写前校验：文件当前内容必须等于模型读到的那个版本。 */
    private void assertUnchanged(Path file, String expectedSha256, String path) {
        if (!writeProtect) {
            return;
        }
        if (expectedSha256 == null || expectedSha256.isBlank()) {
            if (!Files.exists(file)) {
                return;
            }
            throw new RetryableToolError("改已有文件必须带 expected_sha256：" + path
                    + " 的当前版本还没确认过。先 read_file \"" + path
                    + "\"，把返回的 sha256 原样带回来再改。"
                    + "（这是为了不让你的改动覆盖别人期间的修改）");
        }
        String actual;
        try {
            actual = sha256(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new IllegalStateException("读取校验哈希失败：" + e.getMessage(), e);
        }
        if (!actual.equalsIgnoreCase(expectedSha256.trim())) {
            throw new RetryableToolError("文件在你读完之后被改过（sha256 不一致）：" + path
                    + "。别按旧内容覆盖，重新 read_file 拿新的 sha256 和最新内容再改。");
        }
    }

    // ---------- 写 ----------

    @Tool(name = "write_file",
            value = "写一个文件（新建或整体覆盖），自动创建父目录，UTF-8 编码。"
                    + "覆盖已存在的文件必须带 expected_sha256（先 read_file 拿），否则会拒绝——"
                    + "这条规矩是为了不让整体重写把别人的改动吞掉。"
                    + "改已有文件的局部内容请用 edit_file，不要整体重写——会长到被截断。")
    public String writeFile(
            @P(description = "文件路径，相对工作目录，例如 src/Main.java；也接受已授权目录的绝对路径") String path,
            @P(description = "完整文件内容；这是要落盘的原文，不要带行号、不要带 markdown 代码块围栏") String content,
            @P(description = "覆盖已有文件时必填：read_file 返回的 sha256；新建文件不用填") String expectedSha256) {

        Path file = workspace.resolve(path);
        if (content == null) {
            throw new IllegalArgumentException("content 不能为空；空文件请显式传空字符串");
        }
        boolean existed = Files.exists(file);
        if (existed) {
            assertUnchanged(file, expectedSha256, path);
        }
        String before = null;
        if (existed) {
            try {
                before = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                // 读不出来（编码/权限）就当没有旧内容，交给写操作自己报错
                before = null;
            }
        }
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("写文件失败：" + e.getMessage(), e);
        }

        long bytes;
        try {
            bytes = Files.size(file);
        } catch (IOException e) {
            bytes = content.getBytes(StandardCharsets.UTF_8).length;
        }
        Map<String, Object> out = Json.obj(
                "ok", true,
                "path", path,
                "action", existed ? "overwritten" : "created",
                "bytes", bytes,
                "lines", content.isEmpty() ? 0 : content.split("\n", -1).length,
                "sha256", sha256(content));
        if (existed && before != null) {
            out.put("linesBefore", before.split("\n", -1).length);
            out.put("linesAfter", content.split("\n", -1).length);
        }
        Map<String, Object> git = gitState(file);
        if (!git.isEmpty()) {
            out.put("git", git);
        }
        return Json.of(out);
    }

    @Tool(name = "edit_file",
            value = "局部替换文件内容：把 old_text 换成 new_text，old_text 必须在文件里唯一出现"
                    + "（多带几行上下文保证唯一）。只替换一处，返回替换后的行数变化。"
                    + "必须带 expected_sha256（read_file 返回的那个）——文件在你读之后被改过时会被拒绝。"
                    + "写回后返回 git 的增删行统计，自己核对改动量是否合理。")
    public String editFile(
            @P(description = "文件路径，相对工作目录；也接受已授权目录的绝对路径") String path,
            @P(description = "要被替换的原文片段，必须与文件内容逐字符一致（含缩进），且在文件里只出现一次") String oldText,
            @P(description = "替换成的新内容；传空字符串表示删除这段") String newText,
            @P(description = "read_file 返回的 sha256，必填；不填会被拒绝并提示怎么拿") String expectedSha256) {

        Path file = workspace.resolve(path);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("文件不存在：" + path + "；新文件请用 write_file");
        }
        if (oldText == null || oldText.isEmpty()) {
            throw new IllegalArgumentException("old_text 不能为空；要整体覆盖请用 write_file");
        }
        assertUnchanged(file, expectedSha256, path);

        String original;
        try {
            original = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取失败：" + e.getMessage(), e);
        }

        // 换行符对齐：Windows 项目里文件是 CRLF，而模型几乎总是用 \n 拼 old_text，
        // 逐字符 indexOf 必然找不到。实测这是 edit_file 失败的第一大原因。
        String needle = alignLineEndings(original, oldText);
        int first = original.indexOf(needle);
        if (first < 0) {
            throw new RetryableToolError("old_text 在 " + path + " 里找不到。请先 read_file 确认原文"
                    + "（缩进、空行、标点都要一致），不要把行号或省略号写进 old_text。"
                    + "要替换的片段开头是：" + preview(oldText));
        }
        int second = original.indexOf(needle, first + 1);
        if (second >= 0) {
            throw new RetryableToolError("old_text 在 " + path + " 里出现了多次，无法确定改哪一处。"
                    + "请多带几行上下文让它唯一。片段开头是：" + preview(oldText));
        }

        String replacement = newText == null ? "" : newText;
        // 换行风格跟**文件**走，不是跟锚点走：单行锚点替换成多行内容时，
        // 锚点里没有换行符，但插进去的内容必须是 CRLF
        if (original.contains("\r\n") && !replacement.contains("\r")) {
            replacement = replacement.replace("\n", "\r\n");
        }
        String updated = original.replace(needle, replacement);
        try {
            Files.writeString(file, updated, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("写回失败：" + e.getMessage(), e);
        }

        int linesBefore = original.split("\n", -1).length;
        int linesAfter = updated.split("\n", -1).length;
        Map<String, Object> out = Json.obj(
                "ok", true,
                "path", path,
                "replaced", 1,
                "linesBefore", linesBefore,
                "linesAfter", linesAfter,
                "sha256", sha256(updated));
        Map<String, Object> git = gitState(file);
        if (!git.isEmpty()) {
            out.put("git", git);
        }
        return Json.of(out);
    }

    // ---------- 搜 ----------

    @Tool(name = "search_code",
            value = "在工作目录里按正则搜索代码文本，返回命中的文件:行号:内容。"
                    + "不知道目标在哪个文件时用它，比逐个 read_file 快得多。"
                    + "一次最多回 60 条；命中更多时返回 nextOffset，带上下一次就能接着看后面的，"
                    + "不要靠改关键词来绕过上限（会漏）。")
    public String searchCode(
            @P(description = "要搜的文本，支持 Java 正则；正则写错时会退化成纯文本包含匹配") String pattern,
            @P(description = "搜索起点，相对工作目录；可以是目录，也可以是单个文件（已知在哪就传文件）；留空或 \".\" 表示根目录") String dir,
            @P(description = "只看匹配这个通配符的文件，例如 *.java、*.ts；留空表示所有文本文件") String fileGlob,
            @P(description = "跳过前多少条命中，用于分页；不传默认 0。用上次返回的 nextOffset") Integer offset) {

        if (pattern == null || pattern.isBlank()) {
            throw new IllegalArgumentException("pattern 不能为空");
        }
        Path start;
        try {
            workspace.ensureRoot();
            start = (dir == null || dir.isBlank() || ".".equals(dir.trim()))
                    ? workspace.root()
                    : workspace.resolveRead(dir);
        } catch (IOException e) {
            throw new IllegalStateException("创建工作目录失败：" + e.getMessage(), e);
        }
        // dir 允许指向单个文件：实测里模型常常已经知道"就在这个文件里"，顺手把文件路径
        // 传给了 dir。原来的实现直接回「不是目录」，它理解不到自己错在哪，就放弃了搜索。
        // walkFileTree 对文件起点只会回调一次 visitFile，所以放开这个限制不需要额外分支。
        if (!Files.isDirectory(start) && !Files.isRegularFile(start)) {
            throw new com.example.agentcore.support.RetryableToolError(
                    "dir 既不是目录也不是文件：" + dir
                            + "；dir 是搜索起点，应传目录（例如 src/main/java）或单个文件");
        }
        int skip = Math.max(0, offset == null ? 0 : offset);

        java.util.regex.Pattern compiled;
        boolean asLiteral = false;
        try {
            compiled = java.util.regex.Pattern.compile(pattern);
        } catch (java.util.regex.PatternSyntaxException e) {
            // 模型写的正则经常有语法错（比如把 ( 漏了配对）。这时退化成纯文本匹配，
            // 比直接报错好：它至少能搜到东西，而报错只会让它换个写法再试一次
            compiled = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(pattern));
            asLiteral = true;
        }
        final java.util.regex.Pattern regex = compiled;
        final boolean literal = asLiteral;
        PathMatcher matcher = (fileGlob == null || fileGlob.isBlank())
                ? null
                : start.getFileSystem().getPathMatcher("glob:" + fileGlob.trim());

        List<Object> matches = Json.arr();
        boolean[] truncated = {false};
        // seen 统计"命中过的总条数"，前 skip 条只数不收，这样分页不依赖缓存也不漏
        int[] seen = {0};
        try {
            Files.walkFileTree(start, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                    if (!d.equals(start) && SKIP_DIRS.contains(d.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) {
                    if (matcher != null && !matcher.matches(f.getFileName())) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (attrs.size() > MAX_READ_BYTES) {
                        return FileVisitResult.CONTINUE;
                    }
                    List<String> lines;
                    try {
                        byte[] raw = Files.readAllBytes(f);
                        // 跳过二进制：前 8KB 里有 \0 就当二进制
                        int probe = Math.min(raw.length, 8192);
                        for (int i = 0; i < probe; i++) {
                            if (raw[i] == 0) {
                                return FileVisitResult.CONTINUE;
                            }
                        }
                        lines = Files.readAllLines(f, StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        return FileVisitResult.CONTINUE;
                    }
                    for (int i = 0; i < lines.size(); i++) {
                        if (!regex.matcher(lines.get(i)).find()) {
                            continue;
                        }
                        if (seen[0]++ < skip) {
                            continue;
                        }
                        matches.add(Json.obj(
                                "path", workspace.display(f),
                                "line", i + 1,
                                "text", clip(lines.get(i).trim())));
                        if (matches.size() >= MAX_SEARCH_MATCHES) {
                            truncated[0] = true;
                            return FileVisitResult.TERMINATE;
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path f, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("搜索失败：" + e.getMessage(), e);
        }

        Map<String, Object> out = Json.obj(
                "ok", true,
                "pattern", pattern,
                "literal", literal,
                "offset", skip,
                "count", matches.size(),
                "truncated", truncated[0],
                "matches", matches);
        if (truncated[0]) {
            out.put("nextOffset", skip + matches.size());
            out.put("hint", "还有更多命中：用 offset=" + (skip + matches.size())
                    + " 接着看，或者收窄 pattern/dir 减少噪音");
        }
        return Json.of(out);
    }

    private static String preview(String text) {
        String oneLine = text.replace("\n", "\\n").replace("\r", "\\r");
        return clip(oneLine.length() > 80 ? oneLine.substring(0, 80) : oneLine);
    }

    /** 把待匹配片段的换行符调成文件实际的风格（CRLF 文件配 LF 片段时逐字符匹配必失败）。 */
    static String alignLineEndings(String original, String text) {
        if (text == null) {
            return null;
        }
        if (original.contains("\r\n") && !text.contains("\r")) {
            return text.replace("\n", "\r\n");
        }
        return text;
    }

    private static String clip(String text) {
        return text.length() <= 160 ? text : text.substring(0, 160) + "…";
    }
}
