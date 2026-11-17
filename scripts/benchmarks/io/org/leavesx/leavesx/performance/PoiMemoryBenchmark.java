package org.leavesx.leavesx.performance;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;

/** 只测 POI 访问标记，不访问世界或村民；同时检查稀疏与全范围访问成本。 */
public final class PoiMemoryBenchmark {
    private static volatile Object retained;

    public static void main(final String[] args) {
        final ThreadMXBean memory = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!memory.isThreadAllocatedMemorySupported()) throw new IllegalStateException("Allocation counters unavailable");
        memory.setThreadAllocatedMemoryEnabled(true);
        final int order = Integer.parseInt(args[1]);
        System.out.println("BENCH_ENV java=" + Runtime.version() + " suite=poi-memory");
        compare(7, 24, 7, 1, order, memory);
        compare(128, 64, 128, 1, order, memory);
        compare(128, 64, 128, 1024, order, memory);
        compare(128, 64, 128, 128 * 64 * 128, order, memory);
        System.out.println("BENCH_DONE retained=" + retained.getClass().getSimpleName());
    }

    private static void compare(final int sx, final int sy, final int sz, final int marks,
                                final int order, final ThreadMXBean memory) {
        final Runnable[] variants = {
            () -> {
                final var visited = PreviousPoiVisitedSectionSet.create(0, 0, 0, sx - 1, sy - 1, sz - 1, true, true);
                for (int i = 0; i < marks; i++) visited.mark((i / sy) % sx, i % sy, i / (sx * sy));
                retained = visited;
            },
            () -> {
                final var visited = PoiVisitedSectionSet.create(0, 0, 0, sx - 1, sy - 1, sz - 1, true, true);
                for (int i = 0; i < marks; i++) visited.mark((i / sy) % sx, i % sy, i / (sx * sy));
                retained = visited;
            }
        };
        for (int pass = 0; pass < 8; pass++) window(variants[(pass + order) & 1], memory, 40_000_000L);
        final double[][] times = new double[2][11];
        final double[][] bytes = new double[2][11];
        for (int sample = 0; sample < 11; sample++) for (int pass = 0; pass < 2; pass++) {
            final int variant = (sample + pass + order) & 1;
            final double[] result = window(variants[variant], memory, 25_000_000L);
            times[variant][sample] = result[0];
            bytes[variant][sample] = result[1];
        }
        for (int variant = 0; variant < 2; variant++) {
            Arrays.sort(times[variant]);
            Arrays.sort(bytes[variant]);
            System.out.printf(Locale.ROOT, "BENCH_RESULT,poi-memory,sections=%d,marks=%d,%s,median-ns=%.3f,bytes=%.3f%n",
                sx * sy * sz, marks, variant == 0 ? "previous" : "current", times[variant][5], bytes[variant][5]);
        }
    }

    private static double[] window(final Runnable operation, final ThreadMXBean memory, final long duration) {
        final long thread = Thread.currentThread().threadId();
        final long bytes = memory.getThreadAllocatedBytes(thread);
        final long start = System.nanoTime();
        int count = 0;
        do { operation.run(); count++; } while (System.nanoTime() - start < duration);
        return new double[] {(System.nanoTime() - start) / (double) count,
            (memory.getThreadAllocatedBytes(thread) - bytes) / (double) count};
    }
}
