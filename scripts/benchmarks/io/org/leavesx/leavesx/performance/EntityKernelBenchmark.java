package org.leavesx.leavesx.performance;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Random;
import org.leavesx.leavesx.config.KernelBenchmarkConfiguration;
import org.leavesx.leavesx.runtime.LeavesXComputeExecutor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;

/** 同一 JVM 交替开关新旧算法；统计完整准备、计算及结果采用，不作为整服 MSPT。 */
public final class EntityKernelBenchmark {
    private static volatile long consumed;

    private EntityKernelBenchmark() {}

    public static void main(final String[] args) {
        final int workers = Integer.parseInt(args[0]);
        final int order = Integer.parseInt(args[1]);
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        LeavesXComputeExecutor.configure(workers, 64, true, Boolean.parseBoolean(args[2]));
        System.out.printf(Locale.ROOT, "BENCH_ENV java=%s processors=%d workers=%d order=%d suite=entity-kernels%n",
            Runtime.version(), Runtime.getRuntime().availableProcessors(), workers, order);
        try {
            for (final int size : new int[] {1_025, 8_193, 65_537, 262_145}) {
                for (final boolean integral : new boolean[] {false, true}) sorting(size, integral, order);
            }
            for (final int size : new int[] {245, 1_025, 8_193, 32_769}) nearest(size, order);
            for (final int rows : new int[] {1, 512, 8_192}) {
                for (final boolean uniform : new boolean[] {false, true}) tracking(rows, 16, uniform, order);
            }
            io.papermc.paper.entity.activation.SpatialActivationBenchmark.run(order);
        } finally {
            LeavesXComputeExecutor.shutdown();
            KernelBenchmarkConfiguration.restore();
        }
        System.out.printf("BENCH_DONE checksum=%d%n", consumed);
    }

    private static void nearest(final int size, final int order) {
        final Entity origin = marker(0);
        final ArrayList<Entity> entities = new ArrayList<>(size);
        final Random random = new Random(0x4E656172657374L + size);
        for (int index = 0; index < size; index++) entities.add(marker(random.nextDouble(-512, 512)));
        final int expected = StableDistanceSorter.findNearestIndex(entities, origin, true, 256);
        for (final boolean enabled : new boolean[] {false, true}) {
            KernelBenchmarkConfiguration.numericKernels(enabled);
            if (StableDistanceSorter.findNearestIndex(entities, origin, true, 256) != expected) {
                throw new AssertionError("最近目标归约结果不一致");
            }
        }
        compare("nearest-reduction", size, "spread", order,
            () -> consumed = StableDistanceSorter.findNearestIndex(entities, origin, true, 256));
    }

    private static Entity marker(final double x) {
        final Entity entity = new Marker(EntityType.MARKER, null);
        entity.setPos(x, 64, 0);
        return entity;
    }

    private static void sorting(final int size, final boolean integral, final int order) {
        final Random random = new Random(0x486973744265L + size);
        final double[] x = new double[size];
        final double[] y = new double[size];
        final double[] z = new double[size];
        for (int index = 0; index < size; index++) {
            x[index] = random.nextInt(129) - 64 + (integral ? 0 : random.nextDouble());
            y[index] = random.nextInt(33) - 16 + (integral ? 0 : random.nextDouble());
            z[index] = random.nextInt(129) - 64 + (integral ? 0 : random.nextDouble());
        }
        KernelBenchmarkConfiguration.numericKernels(false);
        final int[] expected = StableDistanceSorter.sort(x, y, z, 0, 0, 0, true, 256);
        KernelBenchmarkConfiguration.numericKernels(true);
        if (!Arrays.equals(expected, StableDistanceSorter.sort(x, y, z, 0, 0, 0, true, 256))) {
            throw new AssertionError("距离排序开关结果不一致");
        }
        compare("histograms", size, integral ? "integer" : "fractional", order, () -> {
            final int[] result = StableDistanceSorter.sort(x, y, z, 0, 0, 0, true, 256);
            consumed = result[0] + result[result.length - 1];
        });
    }

    private static void tracking(final int rows, final int players, final boolean uniform, final int order) {
        final double[] positions = new double[players * 3];
        final double[] views = new double[players];
        final int[] indices = new int[players];
        for (int player = 0; player < players; player++) {
            positions[player * 3] = player * 16.0;
            positions[player * 3 + 1] = player;
            indices[player] = player;
            views[player] = uniform ? 128.0 : 32.0 + player * 16;
        }
        final boolean[][] output = new boolean[rows][players];
        final EntityTrackingRangeBatch batch = new EntityTrackingRangeBatch();
        final Runnable operation = () -> {
            batch.prepare(rows, positions, views, players);
            for (int row = 0; row < rows; row++) {
                batch.setRow(row, row % 256, row % 32, row % 64, 128, row % 2 == 0 ? 32 : -1,
                    indices, players, output[row]);
            }
            final int comparisons = batch.comparisonCount();
            final int complete = LeavesXComputeExecutor.invokeRangesWithCount(
                LeavesXComputeExecutor.Workload.ENTITY_TRACKING, comparisons, 256, 64,
                (partition, start, end) -> batch.compute(start, end));
            if (complete == 0) batch.compute(0, comparisons);
            consumed = output[rows / 2][players / 2] ? 1 : 0;
            batch.clear();
        };
        KernelBenchmarkConfiguration.numericKernels(false);
        operation.run();
        final boolean[][] expected = Arrays.stream(output).map(boolean[]::clone).toArray(boolean[][]::new);
        KernelBenchmarkConfiguration.numericKernels(true);
        operation.run();
        if (!Arrays.deepEquals(expected, output)) throw new AssertionError("追踪开关结果不一致");
        compare("tracker-row", rows, uniform ? "uniform-16" : "mixed-16", order, operation);
    }

    private static void compare(final String name, final int size, final String distribution, final int order, final Runnable operation) {
        for (int pass = 0; pass < 6; pass++) {
            KernelBenchmarkConfiguration.numericKernels(((pass + order) & 1) == 1);
            window(operation, 100_000_000L);
        }
        final double[][] samples = new double[2][11];
        for (int sample = 0; sample < 11; sample++) {
            for (int pass = 0; pass < 2; pass++) {
                final int variant = (sample + pass + order) & 1;
                KernelBenchmarkConfiguration.numericKernels(variant == 1);
                samples[variant][sample] = window(operation, 35_000_000L);
            }
        }
        for (int variant = 0; variant < 2; variant++) {
            Arrays.sort(samples[variant]);
            System.out.printf(Locale.ROOT, "BENCH_RESULT,%s,%d,%s,%s,%.3f,%.3f%n", name, size, distribution,
                variant == 0 ? "previous" : "current", samples[variant][5] / 1_000.0, samples[variant][10] / 1_000.0);
        }
    }

    private static double window(final Runnable operation, final long duration) {
        final long start = System.nanoTime();
        int count = 0;
        do { operation.run(); count++; } while (System.nanoTime() - start < duration);
        return (System.nanoTime() - start) / (double)count;
    }
}
