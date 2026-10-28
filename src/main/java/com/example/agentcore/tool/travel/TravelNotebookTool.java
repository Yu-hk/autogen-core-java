package com.example.agentcore.tool.travel;

import com.example.agentcore.support.AppConfig;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 信息保存工具。
 *
 * <p>对应 Python 版 {@code tools/save.py}。原版用 {@code response_format="content_and_artifact"}：
 * 给模型看"信息已保存"，真正的正文塞进 tool message 的 artifact 字段，由上层逻辑写进图状态——
 * 因为 LangGraph 的工具改不了 state。</p>
 *
 * <p>Java 版没有 artifact 通道，与其照抄一个"说了保存其实没保存"的空壳，
 * 不如真的落盘：内容追加到 {@code tool.notes-dir/travel-notes.md}。
 * 会话快照本身已经负责保存对话状态，这里保存的是"沉淀下来的行程素材"。</p>
 */
public class TravelNotebookTool {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Path dir;

    public TravelNotebookTool() {
        this(AppConfig.get().notesDir());
    }

    public TravelNotebookTool(Path dir) {
        this.dir = dir;
    }

    @Tool(name = "save_info_and_clear_history",
            value = "信息保存工具。保存当前已获取的有用信息，返回保存到的本地文件。")
    public String saveInfoAndClearHistory(
            @P(description = "需要保存的有用信息。主要是你调用的工具返回的信息中有用的部分，尽量详细。")
            String informationToSave) {

        if (informationToSave == null || informationToSave.isBlank()) {
            throw new IllegalArgumentException("informationToSave 不能为空");
        }

        String stamp = LocalDateTime.now().format(STAMP);
        String block = "\n## " + stamp + "\n\n" + informationToSave.trim() + "\n";

        try {
            Files.createDirectories(dir);
            Path file = dir.resolve("travel-notes.md");
            Files.writeString(file, block, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return JsonOut.of(JsonOut.object(
                    "ok", true,
                    "message", "信息已保存。",
                    "file", file.toAbsolutePath().toString(),
                    "chars", informationToSave.length()));
        } catch (IOException e) {
            throw new IllegalStateException("保存信息失败：" + e.getMessage(), e);
        }
    }
}
