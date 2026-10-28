package com.example.agentcore.web;

import com.example.agentcore.messaging.ChatMsg;
import com.example.agentcore.runtime.SessionState;
import com.example.agentcore.support.MessageCodec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 把每段对话存成 {@code <id>.json} 一个文件。
 *
 * <p>选文件而不是数据库，理由和这个项目其它地方一致：会话是"一段几十 KB、写少读少"的数据，
 * 引入一个数据库只为了存它，换来的是部署时多一个必须备份、必须连得上的组件。
 * 一个目录 + JSON 就能满足"重启不丢、滚动发布接得上"，而且出问题时用记事本就能看。</p>
 *
 * <p>三项刻意的处理：</p>
 * <ul>
 *   <li><b>原子写</b>：先写同目录的 {@code .tmp} 再 rename。直接覆盖写时，进程若在写到一半崩掉，
 *       留下的是半截 JSON——下次启动读它会失败，等于把整段对话弄丢了。</li>
 *   <li><b>id 白名单</b>：文件名来自 URL 参数，只接受 {@code 8 位小写十六进制}。
 *       不做这层校验，{@code conversationId=../../agent.properties} 就是任意文件读取。</li>
 *   <li><b>损坏不抛</b>：读不出来的文件返回 {@code null}（当新对话）。服务启动时不该
 *       因为一个手工改坏的旧文件而拒绝启动。</li>
 * </ul>
 *
 * <p>多副本时别用它：本地磁盘各存一份，滚动发布会出现"A 副本聊的上下文 B 副本看不见"。
 * 那时换一个 {@link ConversationStore} 的共享实现即可，上层不用改。</p>
 */
final class FileConversationStore implements ConversationStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int FORMAT_VERSION = 1;
    /** 与 {@code Conversation.start()} 生成的 id 形态一致：UUID 前 8 位。 */
    private static final Pattern ID = Pattern.compile("[0-9a-f]{8}");
    private static final String SUFFIX = ".json";
    private static final String TMP_SUFFIX = ".json.tmp";

    private final Path dir;

    FileConversationStore(Path dir) {
        this.dir = dir.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.dir);
        } catch (IOException e) {
            // 目录建不出来（只读卷、权限不对）不该让服务起不来：降级成"这次不持久化"，
            // 但必须留一行明确的日志，否则用户会以为落库生效了。
            System.out.println("[会话] 无法创建目录 " + this.dir + "：" + e.getMessage()
                    + "；本进程内会话不会落盘。");
        }
    }

    @Override
    public Conversation load(String id) {
        Path file = fileOf(id);
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
            if (root.path("version").asInt(0) > FORMAT_VERSION) {
                System.out.println("[会话] 跳过 " + file.getFileName() + "：格式版本 "
                        + root.path("version").asInt() + " 高于当前支持的 " + FORMAT_VERSION);
                return null;
            }
            List<ChatMsg> messages = new ArrayList<>();
            for (JsonNode node : root.path("messages")) {
                messages.add(MessageCodec.read(node));
            }
            String scenario = root.path("scenario").asText("");
            SessionState state = messages.isEmpty()
                    ? null
                    : new SessionState(messages, List.of(), 0, root.path("stopReason").asText(""),
                    root.path("lastActiveAt").asLong(0L));
            return Conversation.restore(root.path("id").asText(id), scenario.isEmpty() ? null : scenario,
                    root.path("turns").asInt(0), root.path("lastActiveAt").asLong(0L), state);
        } catch (IOException | RuntimeException e) {
            System.out.println("[会话] 读取 " + file.getFileName() + " 失败，按新对话处理："
                    + e.getMessage());
            return null;
        }
    }

    @Override
    public synchronized void save(Conversation conversation) throws IOException {
        if (fileOf(conversation.id()) == null) {
            throw new IOException("会话 id 不合法: " + conversation.id());
        }
        SessionState state = conversation.state();
        ObjectNode root = MAPPER.createObjectNode();
        root.put("version", FORMAT_VERSION);
        root.put("id", conversation.id());
        root.put("scenario", conversation.scenarioId() == null ? "" : conversation.scenarioId());
        root.put("turns", conversation.turns());
        root.put("lastActiveAt", conversation.lastActiveAt());
        root.put("messageCount", state == null ? 0 : state.messages().size());
        root.put("stopReason", state == null ? "" : state.lastStopReason());
        ArrayNode messages = root.putArray("messages");
        if (state != null) {
            for (ChatMsg msg : state.messages()) {
                messages.add(MessageCodec.write(msg));
            }
        }

        Files.createDirectories(dir);
        Path target = dir.resolve(conversation.id() + SUFFIX);
        Path tmp = dir.resolve(conversation.id() + TMP_SUFFIX);
        byte[] bytes = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root)
                .getBytes(StandardCharsets.UTF_8);
        Files.write(tmp, bytes);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // 某些网络文件系统不支持原子 rename，退一步用普通替换：至少不会丢文件
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public List<Conversation> recent(int limit) {
        List<Path> files = listFiles();
        List<Conversation> result = new ArrayList<>();
        // 文件名是随机的，顺序只能靠最后活跃时间；先按 mtime 粗排，再加载确认
        files.sort(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed());
        for (Path file : files) {
            if (result.size() >= Math.max(1, limit)) {
                break;
            }
            Conversation conversation = load(stripSuffix(file));
            if (conversation != null) {
                result.add(conversation);
            }
        }
        result.sort(Comparator.comparingLong(Conversation::lastActiveAt).reversed());
        return result;
    }

    @Override
    public synchronized int prune(long cutoffMillis) {
        int removed = 0;
        for (Path file : listFiles()) {
            Conversation conversation = load(stripSuffix(file));
            long activeAt = conversation != null
                    ? conversation.lastActiveAt()
                    : file.toFile().lastModified();
            if (activeAt >= cutoffMillis) {
                continue;
            }
            try {
                Files.deleteIfExists(file);
                removed++;
            } catch (IOException e) {
                System.out.println("[会话] 清理 " + file.getFileName() + " 失败：" + e.getMessage());
            }
        }
        return removed;
    }

    @Override
    public String describe() {
        return dir.toString().replace('\\', '/');
    }

    /** id → 文件；id 不合法返回 null（不抛异常，调用方据此当"没有"）。 */
    private Path fileOf(String id) {
        if (id == null) {
            return null;
        }
        String normalized = id.trim().toLowerCase(Locale.ROOT);
        if (!ID.matcher(normalized).matches()) {
            return null;
        }
        return dir.resolve(normalized + SUFFIX);
    }

    private List<Path> listFiles() {
        List<Path> files = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return files;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*" + SUFFIX)) {
            for (Path path : stream) {
                if (Files.isRegularFile(path)) {
                    files.add(path);
                }
            }
        } catch (IOException e) {
            System.out.println("[会话] 列目录失败 " + dir + "：" + e.getMessage());
        }
        return files;
    }

    private static String stripSuffix(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(SUFFIX) ? name.substring(0, name.length() - SUFFIX.length()) : name;
    }
}
