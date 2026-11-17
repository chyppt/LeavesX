package io.papermc.paper.entity.activation;

import java.util.Arrays;
import java.util.Locale;
import java.util.Random;
import org.leavesx.leavesx.config.KernelBenchmarkConfiguration;
import org.leavesx.leavesx.config.LeavesXRuntime;

/** 包含冻结玩家框复制、空间排序、分组及完整求交，防止只计内核而隐藏准备成本。 */
public final class SpatialActivationBenchmark {
    private static volatile long consumed;

    private SpatialActivationBenchmark() {}

    public static void run(final int orderSeed) {
        for (final int players : new int[] {8, 32, 128}) {
            compare(players, false, orderSeed);
            compare(players, true, orderSeed);
        }
    }

    private static void compare(final int players, final boolean separated, final int orderSeed) {
        final int size = 8_193;
        final int boxes = ActivationType.values().length + 1;
        final int stride = boxes * 6;
        final double[] source = new double[players * stride];
        final double[] bounds = new double[source.length];
        final double[] scratch = new double[source.length];
        final double[] groups = new double[((players + 3) / 4) * stride];
        final int[] order = new int[players];
        final int[] orderScratch = new int[players];
        final double[] entities = new double[size * 6];
        final byte[] types = new byte[size];
        final boolean[] results = new boolean[size];
        final Random random = new Random(0x5370617469616CL + players);
        // 登录顺序与空间位置交错，使连续四人的旧分组跨越多个农场。
        for (int player = 0; player < players; player++) {
            final int spatialIndex = (player * 17) % players;
            final double center = spatialIndex * (separated ? 96.0 : 0.5);
            for (int box = 0; box < boxes; box++) {
                final int offset = player * stride + box * 6;
                source[offset] = center - 32;
                source[offset + 1] = -128;
                source[offset + 2] = -32;
                source[offset + 3] = center + 32;
                source[offset + 4] = 320;
                source[offset + 5] = 32;
            }
        }
        for (int entity = 0; entity < size; entity++) {
            final int offset = entity * 6;
            final double x = random.nextDouble(-64, players * (separated ? 96.0 : 0.5) + 64);
            final double z = random.nextDouble(-64, 64);
            entities[offset] = x;
            entities[offset + 1] = 64;
            entities[offset + 2] = z;
            entities[offset + 3] = x + 0.6;
            entities[offset + 4] = 65.8;
            entities[offset + 5] = z + 0.6;
            types[entity] = (byte)(entity % (boxes - 1));
        }
        final boolean[] expected = new boolean[size];
        ActivationRangeSnapshot.evaluateWorldRange(entities, types, source, expected, players, 0, size);
        final Runnable operation = () -> {
            System.arraycopy(source, 0, bounds, 0, source.length);
            if (LeavesXRuntime.asyncSettings().kernels().spatialActivationGroups()) {
                ActivationRangeSnapshot.spatiallyOrderPlayerBounds(bounds, scratch, order, orderScratch, players);
            }
            ActivationRangeSnapshot.snapshotActivationGroups(bounds, groups, players);
            ActivationRangeSnapshot.evaluateWorldSnapshot(entities, types, bounds, null, groups, results,
                size, players, 256, 64);
            consumed = results[size / 2] ? 1 : 0;
        };
        for (final boolean enabled : new boolean[] {false, true}) {
            KernelBenchmarkConfiguration.numericKernels(enabled);
            operation.run();
            if (!Arrays.equals(expected, results)) throw new AssertionError("空间分组结果不一致");
        }
        for (int pass = 0; pass < 6; pass++) {
            KernelBenchmarkConfiguration.numericKernels(((pass + orderSeed) & 1) == 1);
            window(operation, 100_000_000L);
        }
        final double[][] samples = new double[2][11];
        for (int sample = 0; sample < 11; sample++) {
            for (int pass = 0; pass < 2; pass++) {
                final int variant = (sample + pass + orderSeed) & 1;
                KernelBenchmarkConfiguration.numericKernels(variant == 1);
                samples[variant][sample] = window(operation, 35_000_000L);
            }
        }
        for (int variant = 0; variant < 2; variant++) {
            Arrays.sort(samples[variant]);
            System.out.printf(Locale.ROOT, "BENCH_RESULT,spatial-activation,%d,%s,%s,%.3f,%.3f%n",
                players, separated ? "separated" : "clustered", variant == 0 ? "previous" : "current",
                samples[variant][5] / 1_000.0, samples[variant][10] / 1_000.0);
        }
    }

    private static double window(final Runnable operation, final long duration) {
        final long start = System.nanoTime();
        int count = 0;
        do { operation.run(); count++; } while (System.nanoTime() - start < duration);
        return (System.nanoTime() - start) / (double)count;
    }
}
