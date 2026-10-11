package org.leavesx.leavesx.diagnostics;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerLevel;
import org.leavesx.leavesx.config.LeavesXConfig;
import org.leavesx.leavesx.runtime.LeavesXRegionTicking;

/** 世界 Tick 的墙钟统计；不改变调度，MSPT 包含世界内等待，但不包含全服调度屏障。 */
public final class LeavesXWorldTickMetrics {
    private static final int SAMPLE_COUNT = 100;
    private static final long STALE_NANOS = 5_000_000_000L;
    private static final Map<UUID, Samples> SAMPLES = new ConcurrentHashMap<>();

    private LeavesXWorldTickMetrics() {
    }

    /** 即使兼容保护使世界串行执行，开启 PWT 后仍显示真实的分世界耗时。 */
    public static boolean displayWorldMetrics() {
        return LeavesXRegionTicking.mode() != LeavesXConfig.RegionTickingSettings.Mode.OFF;
    }

    public static void record(final ServerLevel level, final long started) {
        final long ended = System.nanoTime();
        SAMPLES.computeIfAbsent(level.uuid, ignored -> new Samples()).record(started, ended);
    }

    public static Snapshot snapshot(final ServerLevel level) {
        final Samples samples = SAMPLES.get(level.uuid);
        return samples == null ? Snapshot.EMPTY : samples.snapshot(System.nanoTime());
    }

    public static void remove(final ServerLevel level) {
        SAMPLES.remove(level.uuid);
    }

    public record Snapshot(double tps, double mspt, int samples) {
        public static final Snapshot EMPTY = new Snapshot(0.0D, 0.0D, 0);

        public boolean available() {
            return samples > 1;
        }
    }

    // 每个世界仅锁自己的常量时间统计；不在持锁期间读取或修改世界状态。
    static final class Samples {
        private final long[] durations = new long[SAMPLE_COUNT];
        private final long[] intervals = new long[SAMPLE_COUNT];
        private int size;
        private int next;
        private int intervalSamples;
        private long durationTotal;
        private long intervalTotal;
        private long previousStart;
        private long lastEnd;

        synchronized void record(final long started, final long ended) {
            if (size > 0 && started - lastEnd > STALE_NANOS) {
                // 暂停或重启采样后，不把停机时间计入新窗口。
                java.util.Arrays.fill(durations, 0L);
                java.util.Arrays.fill(intervals, 0L);
                size = next = intervalSamples = 0;
                durationTotal = intervalTotal = 0L;
            }
            final long duration = Math.max(0L, ended - started);
            final long interval = size == 0 ? 0L : Math.max(0L, started - previousStart);
            durationTotal += duration - durations[next];
            intervalTotal += interval - intervals[next];
            if (intervals[next] > 0L) intervalSamples--;
            if (interval > 0L) intervalSamples++;
            durations[next] = duration;
            intervals[next] = interval;
            next = (next + 1) % SAMPLE_COUNT;
            size = Math.min(size + 1, SAMPLE_COUNT);
            previousStart = started;
            lastEnd = ended;
        }

        synchronized Snapshot snapshot(final long now) {
            if (size < 2 || intervalSamples == 0 || now - lastEnd > STALE_NANOS) {
                return Snapshot.EMPTY;
            }
            // TPS 来自实际 Tick 间隔，不能用 1000 / MSPT 伪造世界运行速度。
            return new Snapshot(Math.min(20.0D, 1_000_000_000.0D * intervalSamples / intervalTotal),
                durationTotal / (double) size / 1_000_000.0D, size);
        }
    }
}
