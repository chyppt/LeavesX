package net.minecraft.world.level;

import java.util.Arrays;
import java.util.Locale;
import java.util.Random;
import org.leavesx.leavesx.config.KernelBenchmarkConfiguration;
import org.leavesx.leavesx.performance.StableDistanceSorter;
import org.leavesx.leavesx.runtime.LeavesXComputeExecutor;

/** 同一 JVM 轮换新旧实现，包含生产调用的数组、分片及汇总开销；不代表整服 MSPT。 */
public final class EntityComputeBenchmark {
    private static volatile long consumed;

    private EntityComputeBenchmark() {}

    public static void main(final String[] args) {
        final int workers = Integer.parseInt(args[0]);
        final int order = Integer.parseInt(args[1]);
        LeavesXComputeExecutor.configure(workers, 64, true, Boolean.parseBoolean(args[2]));
        System.out.printf(Locale.ROOT, "BENCH_ENV java=%s processors=%d workers=%d order=%d suite=entity-compute%n",
            Runtime.version(), Runtime.getRuntime().availableProcessors(), workers, order);
        try {
            for (final int size : new int[] {8_193, 32_769, 65_537, 262_145}) {
                sorting(size, true, order);
                sorting(size, false, order);
            }
            for (final int size : new int[] {1, 245, 2_048, 8_193, 32_769}) spawning(size, order);
        } finally {
            LeavesXComputeExecutor.shutdown();
            KernelBenchmarkConfiguration.restore();
        }
        System.out.println("BENCH_DONE checksum=" + consumed);
    }

    private static void sorting(final int size, final boolean integral, final int order) {
        final double[] x = new double[size];
        final double[] y = new double[size];
        final double[] z = new double[size];
        final Random random = new Random(0x4D6572676542L + size);
        for (int index = 0; index < size; index++) {
            x[index] = random.nextInt(129) - 64 + (integral ? 0.0 : random.nextDouble());
            y[index] = random.nextInt(33) - 16 + (integral ? 0.0 : random.nextDouble());
            z[index] = random.nextInt(129) - 64 + (integral ? 0.0 : random.nextDouble());
        }
        final int[] expected = StableDistanceSorter.sort(x, y, z, 0, 0, 0, false, 256);
        for (final boolean enabled : new boolean[] {false, true}) {
            KernelBenchmarkConfiguration.parallelMerge(enabled);
            if (!Arrays.equals(expected, StableDistanceSorter.sort(x, y, z, 0, 0, 0, true, 256))) {
                throw new AssertionError("归并结果不一致");
            }
        }
        final Runnable operation = () -> {
            final int[] result = StableDistanceSorter.sort(x, y, z, 0, 0, 0, true, 256);
            consumed = result[0] + result[result.length - 1];
        };
        compare("merge", size, integral ? "integer" : "fractional", order,
            new Runnable[] {operation, operation}, true);
    }

    private static void spawning(final int size, final int order) {
        final var local = spawnInputs(size);
        final var players = spawnInputs(Math.max(1, size * 3 / 5));
        final var expectedLocal = MobSpawnCountAccumulator.count(local, false, 128);
        final var expectedPlayers = MobSpawnCountAccumulator.count(players, false, 128);
        final var actual = MobSpawnCountAccumulator.countBoth(local, players, 128);
        assertCounts(expectedLocal, actual.local());
        assertCounts(expectedPlayers, actual.players());
        final Runnable[] operations = {
            () -> {
                consumed = MobSpawnCountAccumulator.count(local, true, 128).size();
                consumed += MobSpawnCountAccumulator.count(players, true, 128).size();
            },
            () -> {
                final var counts = MobSpawnCountAccumulator.countBoth(local, players, 128);
                consumed = counts.local().size() + counts.players().size();
            }
        };
        compare("combined-spawn", size, "two-snapshots", order, operations, false);
    }

    private static MobSpawnCountAccumulator.Inputs spawnInputs(final int size) {
        final long[] keys = new long[size];
        final byte[] categories = new byte[size];
        final int categoryCount = net.minecraft.world.entity.MobCategory.values().length;
        for (int index = 0; index < size; index++) {
            keys[index] = (index / 32) % 97;
            categories[index] = (byte)(index % categoryCount);
        }
        return new MobSpawnCountAccumulator.Inputs(keys, categories, size);
    }

    private static void assertCounts(final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<int[]> expected,
                                     final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<int[]> actual) {
        if (expected.size() != actual.size()) throw new AssertionError("区块计数不一致");
        for (final var entry : expected.long2ObjectEntrySet()) {
            if (!Arrays.equals(entry.getValue(), actual.get(entry.getLongKey()))) throw new AssertionError("分类计数不一致");
        }
    }

    private static void compare(final String name, final int size, final String distribution, final int order,
                                final Runnable[] operations, final boolean toggleMerge) {
        for (int pass = 0; pass < 6; pass++) {
            final int variant = (pass + order) & 1;
            if (toggleMerge) KernelBenchmarkConfiguration.parallelMerge(variant == 1);
            window(operations[variant], 100_000_000L);
        }
        final double[][] samples = new double[2][11];
        for (int sample = 0; sample < 11; sample++) {
            for (int pass = 0; pass < 2; pass++) {
                final int variant = (sample + pass + order) & 1;
                if (toggleMerge) KernelBenchmarkConfiguration.parallelMerge(variant == 1);
                samples[variant][sample] = window(operations[variant], 35_000_000L);
            }
        }
        for (int variant = 0; variant < 2; variant++) {
            Arrays.sort(samples[variant]);
            System.out.printf(Locale.ROOT, "BENCH_RESULT,%s,%d,%s,%s,%.3f,%.3f%n",
                name, size, distribution, variant == 0 ? "previous" : "current",
                samples[variant][5] / 1_000.0, samples[variant][10] / 1_000.0);
        }
    }

    private static double window(final Runnable operation, final long duration) {
        final long start = System.nanoTime();
        long count = 0;
        do {
            operation.run();
            count++;
        } while (System.nanoTime() - start < duration);
        return (System.nanoTime() - start) / (double)count;
    }
}
