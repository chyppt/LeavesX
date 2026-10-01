package org.leavesx.leavesx.diagnostics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.CraftWorld;
import org.leavesx.leavesx.config.LeavesXRuntime;
import org.leavesx.leavesx.runtime.LeavesXComputeExecutor;

/** Bounded main-thread sampling; file I/O receives only immutable, privacy-safe text. */
public final class LeavesXHealthReport {
    private static final AtomicBoolean WRITING = new AtomicBoolean();
    private static final Path DIRECTORY = Path.of("logs", "leavesx-diagnostics");

    private LeavesXHealthReport() {}

    public static void show(final CommandSender sender, final boolean export) {
        if (!sender.isOp()) {
            sender.sendMessage(Component.text("此命令仅 OP 可用。", NamedTextColor.RED));
            return;
        }
        if (!Bukkit.isPrimaryThread()) {
            sender.sendMessage(Component.text("请在服务器主线程执行诊断命令。", NamedTextColor.RED));
            return;
        }
        if (!export) {
            capture().forEach(line -> sender.sendMessage(Component.text(line)));
            return;
        }
        if (!WRITING.compareAndSet(false, true)) {
            sender.sendMessage(Component.text("诊断报告正在保存，请稍后再试。", NamedTextColor.YELLOW));
            return;
        }
        // Capture before launching the writer: no world, player or CommandSender crosses the thread boundary.
        final List<String> lines;
        try {
            lines = capture();
        } catch (final RuntimeException exception) {
            WRITING.set(false);
            throw exception;
        }
        try {
            Thread.ofVirtual().name("LeavesX diagnostic report").start(() -> {
                try {
                    final Path output = write(DIRECTORY, lines);
                    org.slf4j.LoggerFactory.getLogger(LeavesXHealthReport.class).info("LeavesX 诊断报告已保存：{}", output);
                } catch (final IOException exception) {
                    org.slf4j.LoggerFactory.getLogger(LeavesXHealthReport.class).error("LeavesX 诊断报告保存失败", exception);
                } finally {
                    WRITING.set(false);
                }
            });
        } catch (final RuntimeException exception) {
            WRITING.set(false);
            throw exception;
        }
        sender.sendMessage(Component.text("报告正在保存至 logs/leavesx-diagnostics，结果见控制台。", NamedTextColor.GREEN));
    }

    private static List<String> capture() {
        final List<String> lines = new ArrayList<>();
        lines.add("----- LeavesX 健康检查 -----");
        lines.add("采样时间：" + Instant.now());
        lines.add("服务端：" + Bukkit.getVersion());
        lines.add("Java：" + Runtime.version() + "；可用处理器：" + Runtime.getRuntime().availableProcessors());
        final double mspt = Bukkit.getAverageTickTime();
        lines.add("TPS：" + Arrays.toString(Bukkit.getTPS()) + "；平均 MSPT：" + mspt);
        final TickSummary ticks = summarize(Bukkit.getTickTimes());
        lines.add("最近 " + ticks.samples() + " 个有效 Tick：P95 " + ticks.p95Millis() + " ms / P99 " + ticks.p99Millis() + " ms / 最长 "
            + ticks.maximumMillis() + " ms / 超过 50 ms：" + ticks.overBudget());
        final Runtime runtime = Runtime.getRuntime();
        lines.add("堆内存（MiB）：" + ((runtime.totalMemory() - runtime.freeMemory()) >> 20) + " / " + (runtime.maxMemory() >> 20));
        int index = 0;
        for (final var world : Bukkit.getWorlds()) {
            final var level = ((CraftWorld) world).getHandle();
            // Number worlds instead of exporting custom names, locations or player identities.
            lines.add("世界 " + (++index) + "：区块 " + level.getChunkSource().getFullChunksCount()
                + " / 实体 " + level.getEntityCount() + " / 玩家 " + world.getPlayerCount());
        }
        final var metrics = LeavesXComputeExecutor.metrics();
        final var window = LeavesXComputeExecutor.recentMetrics(60);
        lines.add("计算池：" + metrics.activeWorkers() + " 活跃 / " + metrics.workers() + " 线程 / " + metrics.queuedTasks() + " 排队");
        lines.add("并行窗口（秒）：" + window.elapsedNanos() / 1_000_000_000.0 + "；调用 " + window.invocations()
            + " / 并行 " + window.parallelInvocations() + " / 主线程回退 " + window.mainThreadFallbacks()
            + " / 异常 " + window.failedInvocations() + " / 拒绝 " + window.rejectedTasks());
        final var gc = LeavesXGcDiagnostics.snapshot(60);
        for (final var site : OwnerThreadWaits.Site.values()) {
            final var wait = OwnerThreadWaits.recentSnapshot(site);
            final var mainWait = OwnerThreadWaits.recentMainThreadSnapshot(site);
            lines.add("显式等待 " + site + "（最近约 60 秒）：" + wait.samples() + " 次 / 总计 "
                + wait.totalNanos() / 1_000_000.0 + " ms / 最长 " + wait.maximumNanos() / 1_000_000.0 + " ms");
            lines.add("  其中服务器主线程：" + mainWait.samples() + " 次 / 总计 "
                + mainWait.totalNanos() / 1_000_000.0 + " ms / 最长 " + mainWait.maximumNanos() / 1_000_000.0 + " ms");
        }
        lines.add("显式等待是调用线程的墙钟时间；有序屏障包含提交等待，不能相加，也不覆盖所有主线程等待。");
        for (final var workload : org.leavesx.leavesx.runtime.LeavesXAsyncRuntime.Workload.values()) {
            final var state = org.leavesx.leavesx.runtime.LeavesXAsyncRuntime.metrics(workload);
            if (!state.enabled()) continue;
            final var timing = org.leavesx.leavesx.runtime.LeavesXAsyncRuntime.recentTaskTiming(workload);
            final var queue = org.leavesx.leavesx.runtime.LeavesXAsyncRuntime.recentQueueTiming(workload);
            lines.add(workload.displayName() + "（启用以来累计）：完成 " + state.completedTasks()
                + " / 回退 " + state.callerRuns() + " / 失败 " + state.failedTasks()
                + " / 当前排队 " + state.queuedTasks() + " / 队列峰值 " + state.maximumQueueDepth());
            lines.add("  最近约 60 秒已接纳任务耗时：样本 " + timing.samples() + " / 平均 " + timing.averageMillis()
                + " ms / 最长 " + timing.maximumNanos() / 1_000_000.0
                + " ms（含完成回调及归属队列接管，不含普通同步回退；非 CPU 耗时）");
            lines.add("  最近约 60 秒排队至执行：样本 " + queue.samples() + " / 平均 " + queue.averageMillis()
                + " ms / 最长 " + queue.maximumNanos() / 1_000_000.0 + " ms（不含提交容量等待）");
        }
        lines.add("GC 窗口：暂停 " + gc.windowPauseNanos() / 1_000_000.0 + " ms；最长 " + gc.maximumPauseNanos() / 1_000_000.0
            + " ms" + (gc.windowTruncated() ? "（记录不完整）" : ""));
        if (mspt > 50) lines.add("提示：平均 Tick 已超过 50 ms；执行 /leavesx stalls 查看慢 Tick，再按类型排查，不能仅凭 CPU 总占用判断。");
        if (window.elapsedNanos() == 0) lines.add("提示：并行窗口尚无有效样本，不能据此判定多线程失效。");
        if (window.failedInvocations() > 0 || window.rejectedTasks() > 0) lines.add("提示：出现计算异常或拒绝任务，执行 /leavesx parallel 并检查控制台异常。");
        if (!LeavesXRuntime.diagnosticsCommandEnabled()) lines.add("提示：诊断采样已关闭，部分指标可能不可用。");
        lines.add("报告不包含玩家姓名、IP、坐标、聊天、配置内容或插件清单。各指标窗口不同，不代表故障定论。");
        return List.copyOf(lines);
    }

    static Path write(final Path directory, final List<String> lines) throws IOException {
        Files.createDirectories(directory);
        final Path output = Files.createTempFile(directory, "health-", ".txt");
        Files.write(output, lines, StandardCharsets.UTF_8);
        return output;
    }

    static TickSummary summarize(final long[] samples) {
        // The startup ring has unused zero slots. Exclude them and never sort Bukkit's source array in place.
        final long[] sorted = Arrays.stream(samples).filter(value -> value > 0).sorted().toArray();
        if (sorted.length == 0) return new TickSummary(0, 0, 0, 0, 0);
        final int percentile = (int) Math.ceil(sorted.length * 0.95) - 1;
        return new TickSummary(sorted.length, sorted[percentile] / 1_000_000.0,
            sorted[(int) Math.ceil(sorted.length * 0.99) - 1] / 1_000_000.0,
            sorted[sorted.length - 1] / 1_000_000.0, Arrays.stream(sorted).filter(value -> value > 50_000_000L).count());
    }

    record TickSummary(int samples, double p95Millis, double p99Millis, double maximumMillis, long overBudget) {}
}
