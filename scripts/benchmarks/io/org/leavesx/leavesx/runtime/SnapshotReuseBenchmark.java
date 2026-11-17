package org.leavesx.leavesx.runtime;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;

/** 同 JVM 比较当前任务前的批次实现与线程复用；包含派发及完整屏障，数据任务本身完全相同。 */
public final class SnapshotReuseBenchmark {
    private static volatile int consumed;

    public static void main(String[] args) {
        int order = Integer.parseInt(args[1]);
        LeavesXComputeExecutor.configure(Integer.parseInt(args[0]), 64, true, true);
        try {
            for (int tasks : new int[] {1, 8, 64}) {
                for (int items : new int[] {1, 4096}) measure(tasks, items, order);
            }
        } finally { LeavesXComputeExecutor.shutdown(); }
        System.out.println("BENCH_DONE checksum=" + consumed);
    }

    private static void measure(int tasks, int items, int order) {
        int[] output = new int[tasks];
        Runnable[] work = new Runnable[tasks];
        Arrays.setAll(work, index -> () -> {
            int value = index;
            for (int item = 0; item < items; item++) value = value * 31 + item;
            output[index] = value;
        });
        Runnable[] operations = {
            () -> {
                try (var batch = new UncachedSnapshotBatch(LeavesXComputeExecutor.Workload.ENTITY_TRACKING)) {
                    for (int task = 0; task < tasks; task++) batch.submit(items, work[task]);
                    if (!batch.finish()) throw new AssertionError("旧批次失败");
                }
                consumed = Arrays.hashCode(output);
            },
            () -> {
                try (var batch = LeavesXSnapshotBatch.acquire(LeavesXComputeExecutor.Workload.ENTITY_TRACKING)) {
                    for (int task = 0; task < tasks; task++) batch.submit(items, work[task]);
                    if (!batch.finish()) throw new AssertionError("新批次失败");
                }
                consumed = Arrays.hashCode(output);
            }
        };
        operations[0].run();
        int expected = consumed;
        operations[1].run();
        if (consumed != expected) throw new AssertionError("快照结果不同");
        for (int warm = 0; warm < 6; warm++) window(operations[(warm + order) & 1], 60_000_000L);
        double[][] samples = new double[2][11];
        for (int sample = 0; sample < 11; sample++) {
            for (int turn = 0; turn < 2; turn++) {
                int variant = (sample + turn + order) & 1;
                samples[variant][sample] = window(operations[variant], 30_000_000L);
            }
        }
        var bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        for (int variant = 0; variant < 2; variant++) {
            Arrays.sort(samples[variant]);
            long before = bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
            for (int count = 0; count < 200; count++) operations[variant].run();
            double bytes = (bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - before) / 200.0;
            System.out.printf(Locale.ROOT, "BENCH_RESULT,snapshot-reuse,%d,%d,%s,%.3f,%.3f,%.1f%n",
                tasks, items, variant == 0 ? "previous" : "current", samples[variant][5], samples[variant][10], bytes);
        }
    }

    private static double window(Runnable operation, long duration) {
        long start = System.nanoTime();
        long count = 0;
        long end;
        do { operation.run(); count++; end = System.nanoTime(); } while (end - start < duration);
        return (end - start) / (count * 1000.0);
    }
}
