package com.example.agentcore.tool.code;

import com.example.agentcore.agent.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 编码工具的接线测试：不调模型，只验证"工具真的能改磁盘、真的能跑命令、越界真的会被拦住"。
 *
 * <p>为什么这些断言值得逐条钉死：这套工具是模型唯一的动手通道，任何一个静默坏掉，
 * 模型都会退化成"给你一段代码，你自己试"——而且它自己不会知道失败了。文件类断言全走
 * {@link ToolRegistry#invoke}，连 JSON 参数按名映射那段也一起验了（参数名靠 {@code -parameters}
 * 编译选项保留，掉了这里立刻红）。</p>
 */
class CodeToolsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode call(ToolRegistry registry, String tool, String argsJson) {
        String raw = registry.invoke(tool, argsJson);
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new AssertionError("工具 " + tool + " 返回的不是 JSON：" + raw, e);
        }
    }

    private static JsonNode call(ToolRegistry registry, String tool, Object... keyValues) {
        var node = MAPPER.createObjectNode();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            node.put(String.valueOf(keyValues[i]), String.valueOf(keyValues[i + 1]));
        }
        return call(registry, tool, node.toString());
    }

    private static ToolRegistry registry(Path root) {
        return registry(root, List.of());
    }

    private static ToolRegistry registry(Path root, List<Path> readOnlyRoots) {
        return ToolRegistry.of(CodeTools.all(new CodeWorkspace(root, readOnlyRoots), 120,
                com.example.agentcore.tool.db.DbTool.fromConfig()));
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).asText("");
    }

    /**
     * 参数级失败（锚点没对上、sha256 过期）必须是 retryable 的 JSON，不能是 ERROR——
     * ERROR 会进熔断计数，两次就把工具禁掉。
     */
    private static String retryReason(String raw) {
        try {
            JsonNode node = MAPPER.readTree(raw);
            assertTrue(node.path("retryable").asBoolean(), "应是可重试失败：" + raw);
            assertFalse(node.path("ok").asBoolean(), raw);
            return node.path("reason").asText();
        } catch (AssertionError e) {
            throw e;
        } catch (Exception e) {
            throw new AssertionError("不是合法 JSON（可能是 ERROR 前缀）：" + raw, e);
        }
    }

    // ---------- 注册与契约 ----------

    @Test
    @DisplayName("11 个工具全部注册，名字是模型看到的那套")
    void allToolsAreRegistered() {
        List<String> names = new ArrayList<>();
        registry(Path.of("target/code-workspace-test"))
                .specifications().forEach(s -> names.add(s.name()));
        assertEquals(11, names.size(), names.toString());
        // 反射返回顺序不保证，按集合比：这 11 个名字一个都不能少、一个都不能多
        assertEquals(java.util.Set.of("list_files", "read_file", "read_dir", "write_file", "edit_file",
                "search_code", "run_command",
                "db_connections", "db_schema", "db_query", "db_execute"), java.util.Set.copyOf(names));
    }

    // ---------- 文件 ----------

    @Test
    @DisplayName("写进去再读出来，内容和行号都对得上")
    void writeThenRead(@TempDir Path root) throws IOException {
        ToolRegistry registry = registry(root);

        JsonNode written = call(registry, "write_file",
                "path", "src/main/java/demo/Hello.java",
                "content", "class Hello {\n    String hi() { return \"你好\"; }\n}\n");
        assertTrue(written.path("ok").asBoolean());
        assertEquals("created", text(written, "action"));

        // 父目录是自动建的：模型不会先 mkdir 再写文件
        assertTrue(Files.isRegularFile(root.resolve("src/main/java/demo/Hello.java")));

        JsonNode read = call(registry, "read_file", "path", "src/main/java/demo/Hello.java");
        assertTrue(read.path("ok").asBoolean());
        assertEquals(3, read.path("totalLines").asInt(), "末尾换行不算一行");
        assertTrue(text(read, "content").contains("1| class Hello {"), text(read, "content"));
        assertTrue(text(read, "content").contains("\"你好\""), "中文不能被编码搞坏：" + text(read, "content"));
        assertFalse(read.path("truncated").asBoolean());
    }

    @Test
    @DisplayName("读长文件可以按区间取，不把整个文件塞进上下文")
    void readSlice(@TempDir Path root) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 50; i++) {
            sb.append("line").append(i).append('\n');
        }
        Files.writeString(root.resolve("big.txt"), sb.toString(), StandardCharsets.UTF_8);

        JsonNode read = call(registry(root), "read_file",
                "path", "big.txt", "startLine", 10, "maxLines", 5);
        String content = text(read, "content");
        assertTrue(content.contains("  10| line10"), content);
        assertTrue(content.contains("  14| line14"), content);
        assertFalse(content.contains("line15"), "只该读 5 行：" + content);
        assertTrue(read.path("truncated").asBoolean());
        assertEquals(50, read.path("totalLines").asInt());
    }

    @Test
    @DisplayName("批量读目录：一次拿到一组文件的内容，glob 只挑要的，二进制跳过")
    void readDirReadsBatch(@TempDir Path root) throws IOException {
        Path demo = root.resolve("src/main/java/demo");
        Files.createDirectories(demo);
        Files.writeString(demo.resolve("A.java"), "class A {\n    int x = 1;\n}\n");
        Files.writeString(demo.resolve("B.java"), "class B {}\n");
        Files.writeString(demo.resolve("notes.md"), "# 说明\n");
        Files.writeString(demo.resolve("logo.png"), "PNG\0binary");
        // 构建产物连候选都不该进：否则 glob 一放宽，预算全被 .class 吃掉
        Files.createDirectories(root.resolve("target/classes"));
        Files.writeString(root.resolve("target/classes/A.class"), "binary");

        JsonNode all = call(registry(root), "read_dir", "dir", "src/main/java/demo");
        assertEquals(3, all.path("count").asInt(), all.toString());
        assertEquals(1, all.path("skippedTotal").asInt(), "png 是二进制，应跳过：" + all);
        assertTrue(text(all.path("skipped").get(0), "reason").contains("二进制"), all.toString());

        JsonNode a = null;
        for (JsonNode f : all.path("files")) {
            if ("src/main/java/demo/A.java".equals(text(f, "path"))) {
                a = f;
            }
        }
        assertTrue(a != null, "没读到 A.java：" + all);
        // 带行号回来，模型才能直接用它抄 edit_file 的锚点
        assertTrue(text(a, "content").contains("1| class A {"), text(a, "content"));
        assertFalse(a.path("truncated").asBoolean());

        JsonNode onlyJava = call(registry(root), "read_dir", "dir", "src", "glob", "*.java");
        assertEquals(2, onlyJava.path("count").asInt(), onlyJava.toString());

        JsonNode none = call(registry(root), "read_dir", "dir", ".", "glob", "pom.xml");
        assertTrue(none.path("ok").asBoolean(), "0 个文件不是工具失败");
        assertEquals(0, none.path("count").asInt());
    }

    @Test
    @DisplayName("批量读有预算：maxFiles 卡文件数、maxLinesPerFile 卡行数，且说清楚还差多少")
    void readDirRespectsLimits(@TempDir Path root) throws IOException {
        for (int i = 0; i < 5; i++) {
            Files.writeString(root.resolve("f" + i + ".txt"), "a\nb\nc\nd\ne\n");
        }
        JsonNode one = call(registry(root), "read_dir", "dir", ".", "maxFiles", 1);
        assertEquals(1, one.path("count").asInt());
        assertEquals(4, one.path("notRead").asInt(), one.toString());
        assertTrue(text(one, "hint").contains("还有 4 个"), text(one, "hint"));

        JsonNode cut = call(registry(root), "read_dir",
                "dir", ".", "glob", "f0.txt", "maxLinesPerFile", 2);
        assertEquals(1, cut.path("count").asInt(), cut.toString());
        JsonNode file = cut.path("files").get(0);
        assertEquals(2, file.path("lines").asInt());
        assertEquals(5, file.path("totalLines").asInt());
        assertTrue(file.path("truncated").asBoolean());
        assertEquals("maxLines", text(file, "cutBy"));
    }

    @Test
    @DisplayName("改文件：先要 sha256、唯一锚点替换成功、旧哈希/多锚点/无锚点都明确报错")
    void editFileGuard(@TempDir Path root) throws IOException {
        Path file = root.resolve("Cache.java");
        Files.writeString(file, "class Cache {\n    int size = 0;\n    int size2 = 0;\n}\n",
                StandardCharsets.UTF_8);
        ToolRegistry registry = registry(root);

        // 不带 sha256 直接拒绝，并且必须告诉模型下一步该干什么（否则它会换个写法继续撞）
        String noShaReason = retryReason(registry.invoke("edit_file",
                "{\"path\":\"Cache.java\",\"oldText\":\"int size\",\"newText\":\"int s\"}"));
        assertTrue(noShaReason.contains("expected_sha256"), noShaReason);
        assertTrue(noShaReason.contains("read_file"), noShaReason);

        String sha = text(call(registry, "read_file", "path", "Cache.java"), "sha256");
        assertFalse(sha.isBlank(), "read_file 必须回传 sha256，否则 edit_file 根本用不了");

        JsonNode ok = call(registry, "edit_file",
                "path", "Cache.java",
                "oldText", "    int size = 0;\n    int size2 = 0;\n",
                "newText", "    int size = 0;\n    int cap = 16;\n    int size2 = 0;\n",
                "expectedSha256", sha);
        assertTrue(ok.path("ok").asBoolean());
        assertEquals(1, ok.path("replaced").asInt());
        assertTrue(Files.readString(file).contains("int cap = 16;"));
        // 旧内容必须真的被替换掉，而不是追加：原来的两行换成三行
        assertEquals(6, Files.readString(file).split("\n", -1).length);
        // 改完回传新哈希，下一次改可以直接用，不用再读一遍
        String sha2 = text(ok, "sha256");
        assertFalse(sha2.isBlank());
        assertFalse(sha2.equals(sha), "内容变了哈希必须变");

        // 拿读之前的旧哈希去改：文件已经被改过，必须拦下——这就是写保护的意义
        String staleReason = retryReason(registry.invoke("edit_file", "{\"path\":\"Cache.java\","
                + "\"oldText\":\"int cap = 16;\",\"newText\":\"int cap = 32;\","
                + "\"expectedSha256\":\"" + sha + "\"}"));
        assertTrue(staleReason.contains("被改过"), staleReason);

        // 锚点出现多次：不能瞎改一处，必须让模型补上下文
        String ambiguousReason = retryReason(registry.invoke("edit_file", "{\"path\":\"Cache.java\","
                + "\"oldText\":\"int size\",\"newText\":\"int s\","
                + "\"expectedSha256\":\"" + sha2 + "\"}"));
        assertTrue(ambiguousReason.contains("多次"), ambiguousReason);

        // 锚点根本不存在：最常见的原因是模型凭记忆写锚点，或把行号抄进去了
        // （上面两次都改失败，文件没变，所以 sha2 仍然有效）
        String missingReason = retryReason(registry.invoke("edit_file", "{\"path\":\"Cache.java\","
                + "\"oldText\":\"    // 不存在的注释\\n\",\"newText\":\"x\","
                + "\"expectedSha256\":\"" + sha2 + "\"}"));
        assertTrue(missingReason.contains("找不到"), missingReason);
    }

    @Test
    @DisplayName("CRLF 文件：模型用 \\n 拼的 old_text 也要能匹配上，写回保持 CRLF")
    void editFileToleratesCrlf(@TempDir Path root) throws IOException {
        Path file = root.resolve("Win.java");
        // 模拟 Windows 下 checkout 出来的文件
        Files.writeString(file, "class Win {\r\n    int a = 1;\r\n}\r\n", StandardCharsets.UTF_8);
        ToolRegistry registry = registry(root);

        String sha = text(call(registry, "read_file", "path", "Win.java"), "sha256");
        JsonNode ok = call(registry, "edit_file",
                "path", "Win.java",
                "oldText", "    int a = 1;",
                "newText", "    int a = 2;\n    int b = 3;",
                "expectedSha256", sha);
        assertTrue(ok.path("ok").asBoolean(), ok.toString());

        String after = Files.readString(file);
        assertTrue(after.contains("int a = 2;"), after);
        assertFalse(after.replace("\r\n", "").contains("\n"),
                "写回后不能混进裸 LF，否则 git diff 会整段变色：" + after.replace("\r", "\\r"));
    }

    @Test
    @DisplayName("around：知道方法名就能一次拿到整个方法体，不用按行号猜")
    void readFileAroundSymbol(@TempDir Path root) throws IOException {
        StringBuilder sb = new StringBuilder("class Calc {\n");
        for (int i = 0; i < 120; i++) {
            sb.append("    int pad").append(i).append(" = ").append(i).append(";\n");
        }
        sb.append("""
                    int average(int a, int b) {
                        // 注释里的 } 不该影响配对
                        return (a + b) / 2;
                    }

                    int other() {
                        return 1;
                    }
                }
                """);
        Files.writeString(root.resolve("Calc.java"), sb.toString(), StandardCharsets.UTF_8);

        JsonNode node = call(registry(root), "read_file", "path", "Calc.java", "around", "average");
        assertTrue(node.path("ok").asBoolean(), node.toString());
        assertEquals("average", text(node, "symbol"));
        String body = text(node, "content");
        assertTrue(body.contains("int average(int a, int b)"), body);
        assertTrue(body.contains("return (a + b) / 2;"), body);
        assertFalse(body.contains("int other()"), "方法范围不能连下一个方法一起吞进来：" + body);

        // 找不到符号：报错要说清楚"只写名字"和"先搜一下"
        String missReason = retryReason(registry(root).invoke("read_file",
                "{\"path\":\"Calc.java\",\"around\":\"average(int,int)\"}"));
        assertTrue(missReason.contains("找不到符号"), missReason);
    }

    @Test
    @DisplayName("around 遇到同名重载：两处一并给出，且按符号读不再谎报 truncated")
    void readFileAroundOverloads(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("Q.java"), """
                class Q {
                    double ratio(long n) {
                        return n / 100.0;
                    }

                    int pad1 = 1;
                    int pad2 = 2;

                    double ratio(long n, long base) {
                        return base == 0 ? 0 : n / (double) base;
                    }

                    long tail() {
                        return 1L;
                    }
                }
                """, StandardCharsets.UTF_8);

        JsonNode node = call(registry(root), "read_file", "path", "Q.java", "around", "ratio");
        assertEquals(2, node.path("symbolMatches").asInt(), node.toString());
        // 两个重载（2-4 行与 9-11 行）应该一次都给出来，中间的 pad 字段顺带被包含
        assertEquals(2, node.path("from").asInt(), node.toString());
        assertEquals(11, node.path("to").asInt(), node.toString());
        String body = text(node, "content");
        assertTrue(body.contains("return n / 100.0;"), body);
        assertTrue(body.contains("n / (double) base"), body);
        assertFalse(body.contains("return 1L;"), "不该把后面的方法一起吞进来：" + body);
        // 文件还有 14 行以后的内容，但那是模型没要的部分，不算"被截断"
        assertFalse(node.path("truncated").asBoolean(),
                "按符号读不该因为文件后面还有行就报截断：" + node);
    }

    @Test
    @DisplayName("search_code 分页：offset 接着翻，不靠换关键词绕上限")
    void searchCodePaging(@TempDir Path root) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 5; i++) {
            sb.append("void f").append(i).append("() {}\n");
        }
        Files.writeString(root.resolve("A.java"), sb.toString(), StandardCharsets.UTF_8);
        ToolRegistry registry = registry(root);

        JsonNode page1 = call(registry, "search_code", "pattern", "void f", "offset", 0);
        assertEquals(5, page1.path("count").asInt(), page1.toString());

        JsonNode page2 = call(registry, "search_code", "pattern", "void f", "offset", 3);
        assertEquals(2, page2.path("count").asInt(), page2.toString());
        assertEquals(4, page2.path("matches").get(0).path("line").asInt(), page2.toString());

        JsonNode beyond = call(registry, "search_code", "pattern", "void f", "offset", 99);
        assertEquals(0, beyond.path("count").asInt(), "翻过头是 0 条，不是报错");
    }

    @Test
    @DisplayName("search_code 的 dir 也可以是单个文件：模型常常已经知道在哪")
    void searchCodeAcceptsSingleFile(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/A.java"), "class A { void hit() {} }\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("src/B.java"), "class B { void hit() {} }\n", StandardCharsets.UTF_8);
        ToolRegistry registry = registry(root);

        JsonNode scoped = call(registry, "search_code", "pattern", "hit",
                "dir", "src/A.java", "offset", 0);
        assertEquals(1, scoped.path("count").asInt(),
                "传文件就只搜这个文件（实测里模型就是这么传的）：" + scoped);
        assertEquals("src/A.java", scoped.path("matches").get(0).path("path").asText(), scoped.toString());

        // 路径不存在时给的是可重试的参数错，而不是"这个工具坏了"
        JsonNode bad = call(registry, "search_code", "pattern", "hit",
                "dir", "src/Nope.java", "offset", 0);
        assertTrue(bad.path("retryable").asBoolean(), "不存在的路径应可重试：" + bad);
        assertTrue(bad.path("reason").asText().contains("目录"),
                "理由要说明 dir 该传什么：" + bad);
    }

    @Test
    @DisplayName("列目录跳过 target/.git，不把构建产物当代码给模型看")
    void listSkipsBuildOutput(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("src"));
        Files.createDirectories(root.resolve("target/classes"));
        Files.writeString(root.resolve("src/App.java"), "class App {}\n");
        Files.writeString(root.resolve("target/classes/App.class"), "binary");

        JsonNode listed = call(registry(root), "list_files", "{\"dir\":\".\",\"depth\":3}");
        List<String> paths = new ArrayList<>();
        listed.path("entries").forEach(e -> paths.add(text(e, "path")));

        assertTrue(paths.contains("src/App.java"), paths.toString());
        assertFalse(paths.stream().anyMatch(p -> p.startsWith("target")), "target 应被跳过：" + paths);
    }

    /**
     * 默认只列一层。
     *
     * <p>起因是实测：915 个文件的工程在根目录递归列，前 200 条全被一个目录占满，
     * 模型看不到结构，又原样调了一次，被重复调用拦截后白等一轮（内网纯 CPU 上一轮几分钟）。
     * 默认浅、按需加深，单次结果从 20KB 降到几百字节。</p>
     */
    @Test
    @DisplayName("list_files 默认只列一层，并提示怎么往下看")
    void listDefaultsToSingleLevel(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("src/main/java"));
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Files.writeString(root.resolve("src/main/java/App.java"), "class App {}\n");

        JsonNode shallow = call(registry(root), "list_files", "dir", ".");
        List<String> paths = new ArrayList<>();
        shallow.path("entries").forEach(e -> paths.add(text(e, "path")));

        assertEquals(1, shallow.path("depth").asInt(), shallow.toString());
        assertTrue(paths.contains("pom.xml"), paths.toString());
        assertTrue(paths.contains("src"), "顶层目录本身要在：" + paths);
        assertFalse(paths.contains("src/main/java/App.java"), "默认不该钻进去：" + paths);
        assertTrue(text(shallow, "hint").contains("depth"), "要告诉模型怎么加深：" + shallow);

        JsonNode deeper = call(registry(root), "list_files", "{\"dir\":\".\",\"depth\":4}");
        List<String> deeperPaths = new ArrayList<>();
        deeper.path("entries").forEach(e -> deeperPaths.add(text(e, "path")));
        assertTrue(deeperPaths.contains("src/main/java/App.java"), deeperPaths.toString());
    }

    @Test
    @DisplayName("搜代码：正则命中、命中 0 条不算工具失败、正则写坏退化成纯文本")
    void searchCode(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/A.java"), "class A {\n    void put(int k) {}\n}\n");
        Files.writeString(root.resolve("src/B.md"), "# 文档\n");

        ToolRegistry registry = registry(root);

        JsonNode hit = call(registry, "search_code", "pattern", "void\\s+\\w+\\(", "dir", ".", "fileGlob", "*.java");
        assertEquals(1, hit.path("count").asInt());
        assertEquals("src/A.java", text(hit.path("matches").get(0), "path"));
        assertEquals(2, hit.path("matches").get(0).path("line").asInt());

        JsonNode none = call(registry, "search_code", "pattern", "不存在的词", "dir", ".");
        assertTrue(none.path("ok").asBoolean(), "没搜到是正常结果，不该当失败");
        assertEquals(0, none.path("count").asInt());

        // "(unclosed" 是坏正则；退化成字面量匹配而不是抛错
        JsonNode broken = call(registry, "search_code", "pattern", "(unclosed", "dir", ".");
        assertTrue(broken.path("literal").asBoolean(), broken.toString());
        assertTrue(broken.path("ok").asBoolean());
    }

    // ---------- 越界 ----------

    @Test
    @DisplayName("路径穿越被拦住，报错里说清楚正确写法")
    void pathEscapeIsRejected(@TempDir Path root) {
        ToolRegistry registry = registry(root);

        String escape = registry.invoke("write_file",
                "{\"path\":\"../evil.txt\",\"content\":\"x\"}");
        assertTrue(escape.startsWith("ERROR"), escape);
        assertTrue(escape.contains("越界"), escape);

        String absolute = registry.invoke("read_file", "{\"path\":\"C:/Windows/win.ini\"}");
        assertTrue(absolute.startsWith("ERROR"), absolute);
    }

    // ---------- 只读目录 ----------

    @Test
    @DisplayName("只读目录：绝对路径读得到，写一个字都写不进去")
    void readOnlyRootsAreReadableNotWritable(@TempDir Path root, @TempDir Path external) throws IOException {
        Files.writeString(external.resolve("Config.java"), "class Config {}\n");
        ToolRegistry registry = registry(root, List.of(external));

        JsonNode read = call(registry, "read_file", "path", external.resolve("Config.java").toString());
        assertTrue(read.path("ok").asBoolean(), read.toString());
        assertTrue(text(read, "content").contains("class Config {}"), text(read, "content"));

        JsonNode dir = call(registry, "read_dir", "dir", external.toString());
        assertEquals(1, dir.path("count").asInt(), dir.toString());

        String write = registry.invoke("write_file", MAPPER.createObjectNode()
                .put("path", external.resolve("Config.java").toString())
                .put("content", "hacked").toString());
        assertTrue(write.startsWith("ERROR"), "只读目录必须写不进去：" + write);
        assertTrue(write.contains("只读"), "拒绝理由要说清是只读，而不是笼统的越界：" + write);
        assertTrue(write.contains("复制到工作目录"), write);
        assertEquals("class Config {}\n", Files.readString(external.resolve("Config.java")),
                "被拒的写入不能有一点副作用");
    }

    @Test
    @DisplayName("只读目录来自配置：fromConfig 认 tool.code.read-only-dirs")
    void readOnlyRootsComeFromConfig(@TempDir Path external) {
        String key = "tool.code.read-only-dirs";
        String old = System.getProperty(key);
        try {
            System.setProperty(key, external.toString());
            assertEquals(List.of(external.toAbsolutePath().normalize()),
                    CodeWorkspace.fromConfig().readOnlyRoots(),
                    "配置了却没进 readOnlyRoots，说明 fromConfig 没接上");
        } finally {
            if (old == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, old);
            }
        }
    }

    @Test
    @DisplayName("没登记的目录读不到：默认空配置就是什么外部目录都别想读")
    void unregisteredDirIsNotReadable(@TempDir Path root, @TempDir Path other) throws IOException {
        Files.writeString(other.resolve("secret.txt"), "别读我\n");
        ToolRegistry registry = registry(root, List.of());

        String raw = registry.invoke("read_file",
                MAPPER.createObjectNode().put("path", other.resolve("secret.txt").toString()).toString());
        assertTrue(raw.startsWith("ERROR"), raw);
        assertTrue(raw.contains("越界"), raw);
    }

    // ---------- 命令 ----------

    @Test
    @DisplayName("跑命令：真的执行、拿到退出码与输出")
    void runCommandExecutes(@TempDir Path root) {
        JsonNode version = call(registry(root), "run_command", "command", "java -version");
        assertTrue(version.path("ok").asBoolean(), version.toString());
        assertEquals(0, version.path("exitCode").asInt());
        // java -version 走 stderr，正好验证两条流都被收回来了
        assertTrue(text(version, "stderr").toLowerCase().contains("version"), version.toString());
    }

    @Test
    @DisplayName("编译失败是正常结果（ok=false 但不是 ERROR），模型据此去改代码")
    void compileFailureIsAResultNotAnError(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("Bad.java"), "class Bad { void f() { int x = ; } }\n");
        ToolRegistry registry = registry(root);

        String raw = registry.invoke("run_command",
                "{\"command\":\"javac -encoding UTF-8 -d out Bad.java\"}");
        assertFalse(raw.startsWith("ERROR"), "编译失败不能算工具故障，否则改几次就被熔断：" + raw);
        JsonNode result;
        try {
            result = MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new AssertionError(raw, e);
        }
        assertFalse(result.path("ok").asBoolean());
        assertTrue(result.path("exitCode").asInt() != 0);
        assertTrue(text(result, "note").contains("失败"), result.toString());
    }

    @Test
    @DisplayName("完整闭环：写 → 编译 → 运行 → 拿到 PASS")
    void fullLoopCompileAndRun(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/Demo.java"), """
                public class Demo {
                    public static void main(String[] args) {
                        System.out.println("PASS");
                    }
                }
                """);
        ToolRegistry registry = registry(root);

        JsonNode compiled = call(registry, "run_command",
                "command", "javac -encoding UTF-8 -d out src/Demo.java");
        assertTrue(compiled.path("ok").asBoolean(), compiled.toString());

        JsonNode ran = call(registry, "run_command", "command", "java -cp out Demo");
        assertTrue(ran.path("ok").asBoolean(), ran.toString());
        assertTrue(text(ran, "stdout").contains("PASS"), ran.toString());
    }

    @Test
    @DisplayName("白名单外的程序与破坏性命令都被拒绝，且不算工具故障")
    void commandAllowlist(@TempDir Path root) {
        ToolRegistry registry = registry(root);

        String curl = registry.invoke("run_command", "{\"command\":\"curl http://example.com\"}");
        assertFalse(curl.startsWith("ERROR"), "拒绝应作为结果返回，不该熔断工具：" + curl);
        assertTrue(curl.contains("\"blocked\":true"), curl);
        assertTrue(curl.contains("javac"), "拒绝时要把可用命令告诉模型：" + curl);

        String rm = registry.invoke("run_command", "{\"command\":\"rm -rf /\"}");
        assertTrue(rm.contains("\"blocked\":true"), rm);

        String push = registry.invoke("run_command", "{\"command\":\"git push origin main\"}");
        assertTrue(push.contains("\"blocked\":true"), push);
    }

    @Test
    @DisplayName("没有父目录时也不会因为 cwd 不存在而炸掉")
    void worksBeforeWorkspaceExists(@TempDir Path root) {
        Path notYet = root.resolve("nested/workspace");
        assertFalse(Files.exists(notYet));

        JsonNode listed = call(registry(notYet), "list_files", "dir", ".");
        assertTrue(listed.path("ok").asBoolean(), listed.toString());
        assertTrue(Files.isDirectory(notYet), "首次调用应自动建出工作目录");
    }
}
