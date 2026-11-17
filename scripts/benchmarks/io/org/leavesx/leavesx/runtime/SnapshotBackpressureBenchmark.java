package org.leavesx.leavesx.runtime;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 真实执行器被占满时比较调用线程整批处理的耗时与分配，不把结果当整服收益。 */
public final class SnapshotBackpressureBenchmark {
    private static volatile int consumed;

    public static void main(String[] args) throws Exception {
        int order = Integer.parseInt(args[1]);
        LeavesXComputeExecutor.configure(1, 64, true, true);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        LeavesXComputeExecutor.tryExecuteSnapshot(() -> {
            started.countDown();
            try { release.await(); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        }, error -> { throw new AssertionError(error); });
        if (!started.await(5, TimeUnit.SECONDS)) throw new AssertionError("未占用计算线程");
        try {
            for (int tasks : new int[] {1, 64, 4096}) measure(tasks, order);
        } finally {
            release.countDown();
            LeavesXComputeExecutor.shutdown();
        }
        System.out.println("BENCH_DONE checksum=" + consumed);
    }

    private static void measure(int tasks, int order) {
        int[] result = {0};
        Runnable computation = () -> result[0] = result[0] * 31 + 7;
        Runnable[] operations = {
            () -> {
                try (PreviousSnapshotBatch batch = new PreviousSnapshotBatch(LeavesXComputeExecutor.Workload.ENTITY_TRACKING)) {
                    for (int i = 0; i < tasks; i++) batch.submit(1, computation);
                    if (!batch.finish()) throw new AssertionError("旧批次失败");
                }
                consumed = result[0];
            },
            () -> {
                try (LeavesXSnapshotBatch batch = new LeavesXSnapshotBatch(LeavesXComputeExecutor.Workload.ENTITY_TRACKING)) {
                    for (int i = 0; i < tasks; i++) batch.submit(1, computation);
                    if (!batch.finish()) throw new AssertionError("新批次失败");
                }
                consumed = result[0];
            }
        };
        for (Runnable operation : operations) { result[0] = 0; operation.run(); }
        final ThreadMXBean bean = (ThreadMXBean)ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        for (int pass = 0; pass < 6; pass++) window(operations[(pass + order) & 1], 75_000_000L);
        double[][] samples = new double[2][11];
        double[] allocations = new double[2];
        for (int sample = 0; sample < 11; sample++) {
            for (int pass = 0; pass < 2; pass++) {
                int variant = (sample + pass + order) & 1;
                samples[variant][sample] = window(operations[variant], 35_000_000L);
            }
        }
        for (int variant = 0; variant < 2; variant++) {
            Arrays.sort(samples[variant]);
            long before = bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
            for (int i = 0; i < 100; i++) operations[variant].run();
            allocations[variant] = (bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - before) / 100.0;
            System.out.printf(Locale.ROOT, "BENCH_RESULT,snapshot-backpressure,%d,%s,%.3f,%.3f,%.1f%n",
                tasks, variant == 0 ? "previous" : "current", samples[variant][5] / 1000.0,
                samples[variant][10] / 1000.0, allocations[variant]);
        }
    }

    private static double window(Runnable operation, long nanos) {
        long start = System.nanoTime();
        int count = 0;
        do { operation.run(); count++; } while (System.nanoTime() - start < nanos);
        return (System.nanoTime() - start) / (double) count;
    }
}
