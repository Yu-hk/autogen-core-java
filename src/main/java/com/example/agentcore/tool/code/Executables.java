package com.example.agentcore.tool.code;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 判断本机有没有某个可执行文件。
 *
 * <p>为什么要这么一个小东西：{@link Platform.Shell#resolve} 得在真正执行之前告诉模型
 * "bash 装了/没装"，否则给出去的只能是一条注定失败的命令。手写 PATH 探测而不是起一个
 * {@code where} / {@code which} 子进程——判断可用性这件事本身不该依赖 shell，
 * 也不该为一次检查花 100ms。</p>
 */
final class Executables {

    /** Windows 上 Git Bash 常见的落点：PATH 里没有时补查（Git for Windows 默认不把它加进 PATH）。 */
    private static final List<String> GIT_BASH_GUESSES = List.of(
            "C:/Program Files/Git/bin/bash.exe",
            "C:/Program Files/Git/usr/bin/bash.exe",
            "C:/Program Files (x86)/Git/bin/bash.exe",
            "C:/Program Files/Git/usr/bin/sh.exe");

    private Executables() {
    }

    static boolean available(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        boolean windows = Platform.current().isWindows();
        List<String> suffixes = new ArrayList<>();
        suffixes.add("");
        if (windows) {
            String pathext = System.getenv("PATHEXT");
            if (pathext == null || pathext.isBlank()) {
                pathext = ".COM;.EXE;.BAT;.CMD";
            }
            for (String ext : pathext.split(";")) {
                if (!ext.isBlank()) {
                    suffixes.add(ext.toLowerCase(Locale.ROOT));
                }
            }
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                if (dir.isBlank()) {
                    continue;
                }
                for (String suffix : suffixes) {
                    try {
                        if (Files.isRegularFile(Path.of(dir, name + suffix))) {
                            return true;
                        }
                    } catch (RuntimeException ignored) {
                        // 路径里有非法字符就当没这一项
                    }
                }
            }
        }
        if (windows) {
            for (String guess : GIT_BASH_GUESSES) {
                if (Files.isRegularFile(Path.of(guess))) {
                    return true;
                }
            }
        }
        return false;
    }
}
