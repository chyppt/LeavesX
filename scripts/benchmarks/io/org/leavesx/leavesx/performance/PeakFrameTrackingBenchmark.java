package org.leavesx.leavesx.performance;

import java.util.Arrays;
import java.util.Locale;
import org.leavesx.leavesx.runtime.LeavesXComputeExecutor;
import org.leavesx.leavesx.runtime.LeavesXSnapshotBatch;

/** 对照有效玩家数与峰值预留容量，包含追踪行准备、派发、完整屏障和清理。 */
public final class PeakFrameTrackingBenchmark {
    private static volatile int consumed;

    public static void main(String[] args) {
        int workers = Integer.parseInt(args[0]);
        int order = Integer.parseInt(args[1]);
        System.out.printf(Locale.ROOT, "BENCH_ENV java=%s processors=%d workers=%d suite=tracker-frame-peak%n",
            Runtime.version(), Runtime.getRuntime().availableProcessors(), workers);
        LeavesXComputeExecutor.configure(workers, 64, true, true);
        try {
            for (int rows : new int[] {1, 512, 8192}) {
                for (int players : new int[] {5, 64}) {
                    for (int capacity : new int[] {players, 4096}) {
                        run(rows, players, 1, capacity, order);
                        if (rows > 1) run(rows, players, 16, capacity, order);
                    }
                }
            }
        } finally { LeavesXComputeExecutor.shutdown(); }
        System.out.println("BENCH_DONE checksum=" + consumed);
    }

    private static void run(int rows, int players, int stride, int capacity, int order) {
        PeakFrameCandidateBatch range = new PeakFrameCandidateBatch();
        PeakFrameBaselineBatch previous = new PeakFrameBaselineBatch();
        // 模拟玩家峰值后保留容量，实际有效玩家只有前缀。
        double[] positions = new double[capacity * 3];
        double[] views = new double[capacity];
        int[] indices = new int[players];
        boolean[][] result = new boolean[rows][players];
        for (int p = 0; p < players; p++) {
            indices[p] = p;
            positions[p * 3] = p * 16.0;
            positions[p * 3 + 1] = p;
            views[p] = 128.0;
        }
        final boolean[][] oldResults = new boolean[rows][players];
        Runnable[] operations = {
            () -> runPrevious(previous, rows, players, stride, positions, views, indices, oldResults),
            () -> runCurrent(range, rows, players, stride, positions, views, indices, result)
        };
        operations[0].run();
        operations[1].run();
        verify(rows, players, stride, positions, result);
        verify(rows, players, stride, positions, oldResults);
        double[][] samples = new double[2][11];
        for (int pass = 0; pass < 4; pass++) {
            for (int variant = 0; variant < 2; variant++) window(operations[variant], 75_000_000L);
        }
        for (int sample = 0; sample < 11; sample++) {
            for (int pass = 0; pass < 2; pass++) {
                final int variant = (sample + pass + order) & 1;
                samples[variant][sample] = window(operations[variant], 35_000_000L);
            }
        }
        for (int variant = 0; variant < 2; variant++) {
            Arrays.sort(samples[variant]);
            System.out.printf(Locale.ROOT, "BENCH_RESULT,tracker-frame-peak,%d,%d,stride-%d,capacity-%d,%s,%.3f,%.3f%n",
                rows, players, stride, capacity, variant == 0 ? "previous" : "current",
                samples[variant][5] / 1000.0, samples[variant][10] / 1000.0);
        }
    }

    private static void runCurrent(PeakFrameCandidateBatch range, int rows, int players, int stride,
                                   double[] positions, double[] views, int[] indices, boolean[][] output) {
        range.prepare(rows, positions, views, players);
        try (LeavesXSnapshotBatch pipeline = LeavesXSnapshotBatch.acquire(LeavesXComputeExecutor.Workload.ENTITY_TRACKING)) {
            int published = 0;
            int rowSize = LeavesXSnapshotBatch.partitionSize(rows, PeakFrameCandidateBatch.PIPELINE_ROWS);
            for (int row = 0; row < rows; row++) {
                setRow(range, row, players, stride, indices, output);
                if (row + 1 - published == rowSize || row + 1 == rows) {
                    range.submitRows(pipeline, published, row + 1, positions, views, players);
                    published = row + 1;
                }
            }
            if (!pipeline.finish()) range.compute(0, range.comparisonCount());
        }
        consumed = output[0][0] ? 1 : 0;
        range.clear();
    }

    private static void runPrevious(PeakFrameBaselineBatch range, int rows, int players, int stride,
                                    double[] positions, double[] views, int[] indices, boolean[][] output) {
        range.prepare(rows, positions, views, players);
        try (LeavesXSnapshotBatch pipeline = LeavesXSnapshotBatch.acquire(LeavesXComputeExecutor.Workload.ENTITY_TRACKING)) {
            int published = 0;
            int rowSize = LeavesXSnapshotBatch.partitionSize(rows, PeakFrameBaselineBatch.PIPELINE_ROWS);
            for (int row = 0; row < rows; row++) {
                setRow(range, row, players, stride, indices, output);
                if (row + 1 - published == rowSize || row + 1 == rows) {
                    range.submitRows(pipeline, published, row + 1, positions, views, players);
                    published = row + 1;
                }
            }
            if (!pipeline.finish()) range.compute(0, range.comparisonCount());
        }
        consumed = output[0][0] ? 1 : 0;
        range.clear();
    }

    private static void setRow(PeakFrameCandidateBatch range, int row, int players, int stride,
                               int[] indices, boolean[][] output) {
        if (row % stride == 0) range.setRow(row, row % 256, row % 32, row % 64,
            128, row % 2 == 0 ? 32 : -1, indices, players, output[row]);
        else range.setEmptyRow(row);
    }

    private static void setRow(PeakFrameBaselineBatch range, int row, int players, int stride,
                               int[] indices, boolean[][] output) {
        if (row % stride == 0) range.setRow(row, row % 256, row % 32, row % 64,
            128, row % 2 == 0 ? 32 : -1, indices, players, output[row]);
        else range.setEmptyRow(row);
    }

    private static void verify(int rows, int players, int stride, double[] positions, boolean[][] result) {
        for (int row = 0; row < rows; row++) {
            for (int p = 0; p < players; p++) {
                boolean expected = false;
                if (row % stride == 0) {
                    double dx = positions[p * 3] - row % 256;
                    double dz = positions[p * 3 + 2] - row % 64;
                    double dy = positions[p * 3 + 1] - row % 32;
                    expected = dx * dx + dz * dz <= 128.0 * 128.0
                        && (row % 2 != 0 || dy * dy <= 32.0 * 32.0);
                }
                if (result[row][p] != expected) throw new AssertionError("追踪结果不一致");
            }
        }
    }

    private static double window(Runnable operation, long nanos) {
        long start = System.nanoTime();
        int calls = 0;
        do { operation.run(); calls++; } while (System.nanoTime() - start < nanos);
        return (System.nanoTime() - start) / (double) calls;
    }
}
