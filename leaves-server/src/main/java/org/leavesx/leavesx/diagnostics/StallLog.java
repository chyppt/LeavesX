package org.leavesx.leavesx.diagnostics;

import java.io.IOException;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 由诊断采样线程保存卡顿证据，不读取世界状态，也不阻塞服务器主线程。 */
public final class StallLog {
    private static final Logger LOGGER = LoggerFactory.getLogger(StallLog.class);
    private static final Path FILE = Path.of("logs", "leavesx-stalls.log");
    private static final long MAX_BYTES = 4L * 1024 * 1024;
    private static long lastWrite;

    private StallLog() {}

    public static synchronized void record(final long tick, final long elapsed, final ThreadInfo thread) {
        final long now = System.nanoTime();
        // 持续卡顿最多每十秒写一次；使用已有诊断开关控制采样。
        if (elapsed < 5_000_000_000L || now - lastWrite < 10_000_000_000L) return;
        lastWrite = now;
        final StringBuilder text = new StringBuilder(4096);
        text.append(Instant.now()).append(" LeavesX Tick ").append(tick)
            .append(" 卡顿 ").append(elapsed / 1_000_000L).append(" ms\n")
            .append("主线程：").append(thread.getThreadName()).append(" / ").append(thread.getThreadState())
            .append(" / 等待锁：").append(thread.getLockName())
            .append(" / 锁持有线程：").append(thread.getLockOwnerName()).append('\n');
        for (final StackTraceElement frame : thread.getStackTrace()) text.append("  at ").append(frame).append('\n');
        // 附加统计失效时仍保留最基本的时间、线程与堆栈，不能让诊断本身丢失故障记录。
        try {
            appendRuntimeContext(text);
        } catch (final RuntimeException failure) {
            text.append("运行时统计采集失败：").append(failure).append('\n');
        }
        try {
            Files.createDirectories(FILE.getParent());
            if (Files.exists(FILE) && Files.size(FILE) >= MAX_BYTES) {
                Files.move(FILE, FILE.resolveSibling("leavesx-stalls.previous.log"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(FILE, text, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            LOGGER.warn("主线程卡顿 {} ms，诊断已保存到 {}", elapsed / 1_000_000L, FILE);
        } catch (final IOException | RuntimeException failure) {
            LOGGER.error("无法保存卡顿诊断：\n" + text, failure);
        }
    }

    private static void appendRuntimeContext(final StringBuilder text) {
        final var runtime = ManagementFactory.getRuntimeMXBean();
        final var memory = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        final var compute = org.leavesx.leavesx.runtime.LeavesXComputeExecutor.metrics();
        text.append("JVM PID=").append(ProcessHandle.current().pid())
            .append(" / 启动时间=").append(Instant.ofEpochMilli(runtime.getStartTime()))
            .append(" / 已运行 ms=").append(runtime.getUptime()).append('\n')
            .append("JVM 当前线程数：").append(ManagementFactory.getThreadMXBean().getThreadCount()).append('\n')
            .append("堆 MiB：已用=").append(toMiB(memory.getUsed()))
            .append(" / 提交=").append(toMiB(memory.getCommitted()))
            .append(" / 上限=").append(toMiB(memory.getMax()))
            .append(" / 非堆已用=").append(toMiB(ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getUsed()))
            .append('\n')
            .append("共享计算池：活跃=").append(compute.activeWorkers())
            .append(" / 工作线程=").append(compute.workers())
            .append(" / 排队=").append(compute.queuedTasks())
            .append(" / 已完成=").append(compute.completedTasks()).append('\n');
        for (final var pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
            text.append("缓冲池 ").append(pool.getName()).append("：已用 MiB=")
                .append(toMiB(pool.getMemoryUsed())).append(" / 数量=").append(pool.getCount()).append('\n');
        }
        // 明确区分主线程等待与其他调用者，尚未结束的等待不计入累计值。
        for (final var site : OwnerThreadWaits.Site.values()) {
            final var waits = OwnerThreadWaits.mainThreadSnapshot(site);
            text.append("主线程已完成等待 ").append(site).append("：累计次数=").append(waits.samples())
                .append(" / 累计 ms=").append(waits.totalNanos() / 1_000_000L)
                .append(" / 最大 ms=").append(waits.maximumNanos() / 1_000_000L).append('\n');
        }
        final var gc = LeavesXGcDiagnostics.snapshot(60);
        text.append("近 60 秒 GC 通知：可用=").append(gc.notificationsAvailable())
            .append(" / 窗口截断=").append(gc.windowTruncated())
            .append(" / 事件=").append(gc.windowCollections())
            .append(" / STW ms=").append(gc.windowPauseNanos() / 1_000_000.0)
            .append(" / 并发周期 ms=").append(gc.concurrentGcNanos() / 1_000_000.0).append('\n');
        appendSystemSnapshot(text, LeavesXSystemDiagnostics.snapshot());
        text.append("提示：堆栈仅代表采样位置；累计等待不含未结束调用，GC 通知可能延迟，不能据此排除分配停顿。\n");
        for (final var workload : org.leavesx.leavesx.runtime.LeavesXAsyncRuntime.Workload.values()) {
            final var state = org.leavesx.leavesx.runtime.LeavesXAsyncRuntime.metrics(workload);
            if (state.enabled()) text.append(workload.displayName()).append(" 排队=").append(state.queuedTasks())
                .append(" 完成=").append(state.completedTasks()).append(" 回退=").append(state.callerRuns())
                .append(" 失败=").append(state.failedTasks()).append('\n');
        }
    }

    /** 只读取已有异步缓存，不让故障采样线程等待系统性能计数器。 */
    static void appendSystemSnapshot(final StringBuilder text, final LeavesXSystemDiagnostics.Reading reading) {
        if (!reading.available()) {
            text.append("系统采样：尚未就绪，不能视为无内存压力或无限流。\n");
            return;
        }
        final var system = reading.snapshot();
        text.append("系统采样：距今 ms=").append(reading.ageMillis())
            .append(" / 刷新中=").append(reading.refreshing()).append('\n')
            .append("cgroup 内存 MiB：已用=").append(toMiB(system.cgroupMemoryCurrent()))
            .append(" / 上限=").append(toMiB(system.cgroupMemoryMaximum()))
            .append(" / CPU 限流累计 us=").append(system.throttledMicros()).append('\n')
            .append("系统内存 MiB：空闲=").append(toMiB(system.physicalFree()))
            .append(" / Swap 总量=").append(toMiB(system.swapTotal()))
            .append(" / Swap 空闲=").append(toMiB(system.swapFree()))
            .append("（-1 表示未知）\n");
    }

    private static long toMiB(final long bytes) {
        return bytes < 0L ? -1L : bytes / (1024L * 1024L);
    }
}
