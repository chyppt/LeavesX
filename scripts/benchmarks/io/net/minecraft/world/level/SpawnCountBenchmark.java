package net.minecraft.world.level;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.Arrays;
import java.util.Locale;
import net.minecraft.world.entity.MobCategory;

/** 连续区块计数的生产实现与修改前算法对照；包含 map/数组分配，不代表整服收益。 */
public final class SpawnCountBenchmark {
    private static final int CATEGORIES = MobCategory.values().length;
    private static volatile long consumed;

    public static void main(final String[] args) {
        final int order = Integer.parseInt(args[1]);
        System.out.printf(Locale.ROOT, "BENCH_ENV java=%s processors=%d order=%d suite=spawn-count%n",
            Runtime.version(), Runtime.getRuntime().availableProcessors(), order);
        for (final int size : new int[] {64, 2_048, 8_192}) {
            for (final int runLength : new int[] {1, 256}) compare(size, runLength, order);
        }
        System.out.println("BENCH_DONE checksum=" + consumed);
    }

    private static void compare(final int size, final int runLength, final int order) {
        final long[] keys = new long[size];
        final byte[] categories = new byte[size];
        for (int index = 0; index < size; index++) {
            keys[index] = (index / runLength) % 31;
            categories[index] = (byte) (index % CATEGORIES);
        }
        final var inputs = new MobSpawnCountAccumulator.Inputs(keys, categories, size);
        final var expected = baseline(inputs);
        final var actual = MobSpawnCountAccumulator.count(inputs, false, 128);
        if (expected.size() != actual.size()) throw new AssertionError("chunk count mismatch");
        for (final var entry : expected.long2ObjectEntrySet()) {
            if (!Arrays.equals(entry.getValue(), actual.get(entry.getLongKey()))) {
                throw new AssertionError("category count mismatch");
            }
        }
        final Runnable[] operations = {
            () -> consume(baseline(inputs)),
            () -> consume(MobSpawnCountAccumulator.count(inputs, false, 128))
        };
        for (int pass = 0; pass < 4; pass++) {
            runWindow(operations[(pass + order) & 1], 150_000_000L);
        }
        final double[][] samples = new double[2][11];
        for (int window = 0; window < 11; window++) {
            for (int pass = 0; pass < 2; pass++) {
                final int variant = (window + pass + order) & 1;
                samples[variant][window] = runWindow(operations[variant], 25_000_000L);
            }
        }
        for (int variant = 0; variant < 2; variant++) {
            Arrays.sort(samples[variant]);
            System.out.printf(Locale.ROOT, "BENCH_RESULT,spawn-count,%d,%d,%s,%.3f,%.3f%n",
                size, runLength, variant == 0 ? "baseline" : "locality",
                samples[variant][5] / 1_000.0, samples[variant][10] / 1_000.0);
        }
    }

    private static Long2ObjectOpenHashMap<int[]> baseline(final MobSpawnCountAccumulator.Inputs inputs) {
        final Long2ObjectOpenHashMap<int[]> counts = new Long2ObjectOpenHashMap<>();
        for (int index = 0; index < inputs.size(); index++) {
            final long key = inputs.chunkKeys()[index];
            int[] categoryCounts = counts.get(key);
            if (categoryCounts == null) {
                categoryCounts = new int[CATEGORIES];
                counts.put(key, categoryCounts);
            }
            categoryCounts[Byte.toUnsignedInt(inputs.categoryOrdinals()[index])]++;
        }
        return counts;
    }

    private static void consume(final Long2ObjectOpenHashMap<int[]> counts) {
        consumed = counts.size() + counts.get(0L)[0];
    }

    private static double runWindow(final Runnable operation, final long duration) {
        final long start = System.nanoTime();
        long count = 0;
        do {
            for (int index = 0; index < 8; index++) operation.run();
            count += 8;
        } while (System.nanoTime() - start < duration);
        return (System.nanoTime() - start) / (double) count;
    }
}
