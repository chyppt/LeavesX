package org.leavesx.leavesx.diagnostics;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

/** 从真实写盘入口检查故障证据，避免只有堆栈却无法区分 JVM 和内存状态。 */
@Normal
class StallLogTest {
    @Test
    void persistedStallIdentifiesProcessMemoryAndSharedComputePool() throws Exception {
        // Gradle 将测试工作目录隔离在临时目录，不读写服务器的正式日志。
        final Path file = Path.of("logs", "leavesx-stalls.log");
        final int previousLength = Files.exists(file) ? Math.toIntExact(Files.size(file)) : 0;
        final var thread = ManagementFactory.getThreadMXBean().getThreadInfo(Thread.currentThread().threadId(), 8);

        StallLog.record(122L, TimeUnit.SECONDS.toNanos(4L), thread);
        assertEquals(previousLength, Files.exists(file) ? Files.size(file) : 0L);
        StallLog.record(123L, TimeUnit.SECONDS.toNanos(6L), thread);

        final byte[] bytes = Files.readAllBytes(file);
        final String text = new String(bytes, previousLength, bytes.length - previousLength, StandardCharsets.UTF_8);
        assertAll(
            () -> assertTrue(text.contains("JVM PID=" + ProcessHandle.current().pid()), text),
            () -> assertTrue(text.contains("启动时间="), text),
            () -> assertTrue(text.contains("堆 MiB：已用="), text),
            () -> assertTrue(text.contains("共享计算池：活跃="), text),
            () -> assertTrue(text.contains("已完成等待"), text),
            () -> assertTrue(text.contains("近 60 秒 GC 通知"), text),
            () -> assertTrue(text.contains("堆栈仅代表采样位置"), text)
        );
        StallLog.record(124L, TimeUnit.SECONDS.toNanos(7L), thread);
        assertEquals(bytes.length, Files.size(file), "连续采样应限频，不能反复写盘");
    }

    @Test
    void missingSystemSampleIsNotPresentedAsZeroMemoryUsage() {
        final StringBuilder text = new StringBuilder();
        StallLog.appendSystemSnapshot(text, new LeavesXSystemDiagnostics.Reading(null, -1L, false, true));
        assertTrue(text.toString().contains("尚未就绪"));
    }

    @Test
    void staleSystemSampleKeepsItsAgeAndUnknownValues() {
        final var system = new LeavesXSystemDiagnostics.Snapshot(
            8, "0-7", "0-7", new LeavesXSystemDiagnostics.CpuQuota(false, -1L, -1L, -1.0D),
            -1L, -1L, -1L, -1L, -1L, -1L, -1L, -1L, -1L, -1.0D, -1.0D
        );
        final StringBuilder text = new StringBuilder();
        StallLog.appendSystemSnapshot(text, new LeavesXSystemDiagnostics.Reading(system, 15000L, true, true));
        assertAll(
            () -> assertTrue(text.toString().contains("距今 ms=15000")),
            () -> assertTrue(text.toString().contains("已用=-1")),
            () -> assertTrue(text.toString().contains("CPU 限流累计 us=-1"))
        );
    }
}
