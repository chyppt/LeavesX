package org.leavesx.leavesx.performance;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;
import org.leavesx.leavesx.config.KernelBenchmarkConfiguration;

/** 对照追踪批次准备和清理的驻留策略；不包含实体采集、距离计算和网络提交。 */
public final class SnapshotCacheBenchmark {
    private static volatile int consumed;
    private static final double[] NO_COORDINATES = new double[0];

    public static void main(final String[] args) {
        final int order = Integer.parseInt(args[1]);
        final ThreadMXBean memory = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!memory.isThreadAllocatedMemorySupported()) throw new IllegalStateException("Allocation counters unavailable");
        memory.setThreadAllocatedMemoryEnabled(true);
        KernelBenchmarkConfiguration.restore();
        System.out.printf(Locale.ROOT, "BENCH_ENV java=%s suite=snapshot-cache units=ns-and-bytes-per-two-batches%n", Runtime.version());
        for (final int[] sizes : new int[][] {{64, 64}, {512, 512}, {8192, 8192}, {12000, 16000}}) {
            compare(sizes, order, memory);
        }
        System.out.println("BENCH_DONE checksum=" + consumed);
    }

    private static void compare(final int[] sizes, final int order, final ThreadMXBean memory) {
        final EntityTrackingRangeBatch current = new EntityTrackingRangeBatch();
        final PreviousEntityTrackingRangeBatch previous = new PreviousEntityTrackingRangeBatch();
        final Runnable[] variants = {
            () -> {
                for (final int rows : sizes) {
                    previous.prepare(rows, NO_COORDINATES, NO_COORDINATES);
                    for (int row = 0; row < rows; row++) previous.setEmptyRow(row);
                    consumed = previous.comparisonCount();
                    previous.clear();
                }
            },
            () -> {
                for (final int rows : sizes) {
                    current.prepare(rows, NO_COORDINATES, NO_COORDINATES);
                    for (int row = 0; row < rows; row++) current.setEmptyRow(row);
                    consumed = current.comparisonCount();
                    current.clear();
                }
            }
        };
        for (int warmup = 0; warmup < 8; warmup++) window(variants[(warmup + order) & 1], memory, 50_000_000);
        final double[][] nanos = new double[2][11];
        final double[][] bytes = new double[2][11];
        for (int sample = 0; sample < 11; sample++) {
            for (int pass = 0; pass < 2; pass++) {
                final int variant = (sample + pass + order) & 1;
                final double[] result = window(variants[variant], memory, 25_000_000);
                nanos[variant][sample] = result[0];
                bytes[variant][sample] = result[1];
            }
        }
        for (int variant = 0; variant < 2; variant++) {
            Arrays.sort(nanos[variant]);
            Arrays.sort(bytes[variant]);
            System.out.printf(Locale.ROOT, "BENCH_RESULT,snapshot-cache,%d-%d,%s,median-ns=%.3f,max-ns=%.3f,median-bytes=%.3f%n",
                sizes[0], sizes[1], variant == 0 ? "previous" : "current", nanos[variant][5], nanos[variant][10], bytes[variant][5]);
        }
    }

    private static double[] window(final Runnable operation, final ThreadMXBean memory, final long duration) {
        final long thread = Thread.currentThread().threadId();
        final long allocated = memory.getThreadAllocatedBytes(thread);
        final long start = System.nanoTime();
        int count = 0;
        do {
            operation.run();
            count++;
        } while (System.nanoTime() - start < duration);
        final long elapsed = System.nanoTime() - start;
        final long totalBytes = memory.getThreadAllocatedBytes(thread) - allocated;
        return new double[] {elapsed / (double) count, totalBytes / (double) count};
    }
}
