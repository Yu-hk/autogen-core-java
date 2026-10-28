package com.example.agentcore.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 按天切分的 JSONL 日志文件（一行一条 JSON）。
 *
 * <p>为什么不是可读的纯文本：这些日志的用途是"按 traceId 把一次请求的所有记录捞出来"，
 * 一行一条 JSON 才能直接喂给 {@code jq} / Loki / 任何采集器，也不用为正则解析跟中文
 * 混排、换行转义反复较劲。人要看的时候，控制台那份仍然是可读格式。</p>
 *
 * <p><b>每条都 flush。</b>进程被 OOM kill 或容器被重调度时，缓冲区里那些日志恰好是
 * 最想知道"死之前发生了什么"的部分。这个服务的日志量是每次请求一行，flush 的代价可以忽略。</p>
 *
 * <p>写失败（磁盘满、目录只读）只打一行警告然后把这个 writer 标成坏的，后续直接丢弃——
 * 日志系统不该成为业务失败的原因。</p>
 */
public final class JsonlLog {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path dir;
    private final String prefix;
    private BufferedWriter writer;
    private LocalDate day;
    private boolean broken;

    public JsonlLog(Path dir, String prefix) {
        this.dir = dir;
        this.prefix = prefix;
    }

    public Path dir() {
        return dir;
    }

    /** 追加一条；时间戳由这里统一补，调用方不用各自去取。 */
    public synchronized void write(ObjectNode node) {
        if (broken) {
            return;
        }
        node.put("ts", Instant.now().toString());
        try {
            ensureWriter();
            writer.write(MAPPER.writeValueAsString(node));
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            broken = true;
            System.out.println("[日志] 写入 " + dir + " 失败，后续结构化日志将被丢弃：" + e.getMessage());
        }
    }

    public synchronized void close() {
        try {
            if (writer != null) {
                writer.close();
            }
        } catch (IOException ignored) {
            // 关不掉不影响退出
        } finally {
            writer = null;
        }
    }

    private void ensureWriter() throws IOException {
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        if (writer != null && today.equals(day)) {
            return;
        }
        if (writer != null) {
            writer.close();
        }
        Files.createDirectories(dir);
        Path file = dir.resolve(prefix + "-" + today + ".jsonl");
        writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        day = today;
    }
}
