package com.example.agentcore.tool.code;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 平台与 shell 的组装规则。
 *
 * <p>这些断言为什么不依赖"测试跑在哪台机器上"：平台是参数，不是环境。
 * 拿 {@code Platform.WINDOWS} 去问"ls 能不能跑"，结论在 Linux 上跑测试也必须一样——
 * 否则这段逻辑只在开发机上对，一部署就翻。</p>
 */
class PlatformTest {

    @Test
    @DisplayName("当前平台能认出来，且默认 shell 与平台匹配")
    void currentPlatformHasASensibleDefaultShell() {
        Platform platform = Platform.current();
        assertNotNull(platform);
        Platform.Shell shell = platform.defaultShell();
        assertNotNull(shell);
        if (platform.isWindows()) {
            assertEquals(Platform.Shell.CMD, shell, "Windows 默认应该是 cmd");
        } else {
            assertTrue(shell == Platform.Shell.SH || shell == Platform.Shell.BASH,
                    "类 Unix 默认应该是 sh 或 bash，实际 " + shell);
        }
    }

    @Test
    @DisplayName("命令行按 shell 组装：cmd 先切 UTF-8 代码页，bash 走登录 shell")
    void commandsAreWrappedPerShell() {
        assertEquals(List.of("cmd", "/c", "chcp 65001>nul & javac A.java"),
                Platform.Shell.CMD.commandLine("javac A.java"));
        assertEquals(List.of("sh", "-c", "javac A.java"),
                Platform.Shell.SH.commandLine("javac A.java"));
        assertEquals(List.of("bash", "-lc", "javac A.java"),
                Platform.Shell.BASH.commandLine("javac A.java"));
        List<String> powershell = Platform.Shell.POWERSHELL.commandLine("javac A.java");
        assertEquals("powershell", powershell.get(0));
        assertTrue(powershell.contains("-NoProfile"), powershell.toString());
        assertTrue(powershell.contains("-Command"), powershell.toString());
    }

    @Test
    @DisplayName("白名单跟着 shell 走：cmd 没有 ls，sh 没有 dir，powershell 两边都有")
    void allowlistFollowsTheShellNotThePlatform() {
        assertFalse(Platform.Shell.CMD.allowedPrograms().contains("ls"),
                "cmd 里没有 ls，放了就等于让模型撞必死的命令");
        assertTrue(Platform.Shell.CMD.allowedPrograms().contains("dir"));
        assertTrue(Platform.Shell.CMD.allowedPrograms().contains("findstr"));

        assertTrue(Platform.Shell.SH.allowedPrograms().contains("ls"));
        assertTrue(Platform.Shell.BASH.allowedPrograms().contains("grep"));
        assertFalse(Platform.Shell.SH.allowedPrograms().contains("dir"));
        assertFalse(Platform.Shell.SH.allowedPrograms().contains("findstr"));

        // PowerShell 把 ls/cat/dir/type 全做成了别名，两边都该放行
        assertTrue(Platform.Shell.POWERSHELL.allowedPrograms().contains("ls"));
        assertTrue(Platform.Shell.POWERSHELL.allowedPrograms().contains("dir"));

        // 通用程序在任何 shell 下都在
        for (Platform.Shell shell : Platform.Shell.values()) {
            assertTrue(shell.allowedPrograms().contains("mvn"), shell + " 少了 mvn");
            assertTrue(shell.allowedPrograms().contains("javac"), shell + " 少了 javac");
        }
    }

    @Test
    @DisplayName("另一个平台的命令要翻译成等价写法，而不是只说'不允许'")
    void crossPlatformCommandsGetATranslation() {
        assertTrue(Platform.suggestionFor(Platform.WINDOWS, "ls").contains("dir"));
        assertTrue(Platform.suggestionFor(Platform.WINDOWS, "cat").contains("type"));
        assertTrue(Platform.suggestionFor(Platform.WINDOWS, "grep").contains("findstr"));
        assertTrue(Platform.suggestionFor(Platform.WINDOWS, "which").contains("where"));

        assertTrue(Platform.suggestionFor(Platform.LINUX, "dir").contains("ls"));
        assertTrue(Platform.suggestionFor(Platform.LINUX, "type").contains("cat"));
        assertTrue(Platform.suggestionFor(Platform.LINUX, "findstr").contains("grep"));
        assertTrue(Platform.suggestionFor(Platform.LINUX, "where").contains("which"));

        // 不相干的名字不该硬凑一个翻译
        assertNull(Platform.suggestionFor(Platform.WINDOWS, "mvn"));
    }

    @Test
    @DisplayName("不能指定平台没有的 shell：Linux 上没有 cmd")
    void cannotAskForAShellThePlatformDoesNotHave() {
        assertThrows(IllegalArgumentException.class,
                () -> Platform.Shell.resolve(Platform.LINUX, "cmd"));
        assertThrows(IllegalArgumentException.class,
                () -> Platform.Shell.resolve(Platform.LINUX, "powershell"));
        assertThrows(IllegalArgumentException.class,
                () -> Platform.Shell.resolve(Platform.WINDOWS, "随便写个名字"));
        // auto 永远能解析
        assertEquals(Platform.WINDOWS.defaultShell(), Platform.Shell.resolve(Platform.WINDOWS, "auto"));
        assertEquals(Platform.LINUX.defaultShell(), Platform.Shell.resolve(Platform.LINUX, ""));
    }

    @Test
    @DisplayName("提示词里写清了本平台的命令对照，模型不用靠常识猜")
    void promptHintMentionsTheRightCommands() {
        String windows = Platform.WINDOWS.promptHint();
        assertTrue(windows.contains("Windows"), windows);
        assertTrue(windows.contains("dir"), windows);
        assertTrue(windows.contains("findstr"), windows);
        assertTrue(windows.contains("ls"), "要明确告诉它 cmd 里没有 ls：" + windows);

        String linux = Platform.LINUX.promptHint();
        assertTrue(linux.contains("ls"), linux);
        assertTrue(linux.contains("grep"), linux);
        assertTrue(linux.contains("没有 dir"), linux);
    }
}
