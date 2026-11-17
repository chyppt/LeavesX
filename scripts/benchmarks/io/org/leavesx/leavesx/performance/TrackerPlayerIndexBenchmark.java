package org.leavesx.leavesx.performance;

import java.util.Arrays;
import java.util.Locale;
import java.util.function.ToIntFunction;

/** 比较玩家索引准备成本；同 JVM 交替运行，不包含距离计算、网络和插件。 */
public final class TrackerPlayerIndexBenchmark {
    private static volatile long consumed;

    public static void main(final String[] args) {
        final int order = Integer.parseInt(args[1]);
        for (final int entities : new int[] {1, 512, 8192}) {
            for (final int players : new int[] {5, 64}) {
                for (final String layout : entities == 1 ? new String[] {"group-1"} : new String[] {"group-1", "group-64", "interleaved-64"}) {
                    run(entities, players, layout, order);
                }
            }
        }
        System.out.println("BENCH_DONE checksum=" + consumed);
    }

    private static void run(final int entities, final int playerCount, final String layout, final int order) {
        final int group = layout.equals("group-1") ? 1 : 64;
        final Object[] players = new Object[playerCount];
        Arrays.setAll(players, ignored -> new Object());
        final Object[] chunks = new Object[layout.equals("interleaved-64") ? 64 : (entities + group - 1) / group];
        Arrays.setAll(chunks, ignored -> new Object());
        final int[][] original = new int[entities][playerCount];
        final TrackerPlayerIndexCache cache = new TrackerPlayerIndexCache();
        final var playerIndices = new java.util.IdentityHashMap<Object, Integer>();
        for (int index = 0; index < playerCount; index++) playerIndices.put(players[index], index);
        final ToIntFunction<Object> lookup = player -> {
            if (playerCount >= 16) return playerIndices.get(player);
            for (int index = 0; index < playerCount; index++) if (players[index] == player) return index;
            throw new AssertionError("Missing player");
        };
        final Runnable previous = () -> {
            long checksum = 0;
            for (int row = 0; row < entities; row++) {
                for (int index = 0; index < playerCount; index++) original[row][index] = lookup.applyAsInt(players[index]);
                checksum += original[row][playerCount - 1];
            }
            consumed = checksum;
        };
        final Runnable current = () -> {
            long checksum = 0;
            for (int row = 0; row < entities; row++) {
                final Object chunk = chunks[layout.equals("interleaved-64") ? row % group : row / group];
                int[] indices = cache.get(chunk, chunk, 7L, playerCount);
                if (indices == null) {
                    indices = original[row];
                    for (int index = 0; index < playerCount; index++) indices[index] = lookup.applyAsInt(players[index]);
                    cache.remember(chunk, chunk, 7L, playerCount, indices);
                }
                checksum += indices[playerCount - 1];
            }
            cache.clear();
            consumed = checksum;
        };
        final Runnable[] operations = {previous, current};
        for (final Runnable operation : operations) {
            operation.run();
            if (consumed != (long)entities * (playerCount - 1)) throw new AssertionError("玩家索引结果不一致");
        }
        for (int pass = 0; pass < 4; pass++) for (final Runnable operation : operations) window(operation, 50_000_000L);
        final double[][] samples = new double[2][11];
        for (int sample = 0; sample < 11; sample++) {
            for (int turn = 0; turn < 2; turn++) {
                final int variant = (sample + order + turn) & 1;
                samples[variant][sample] = window(operations[variant], 25_000_000L);
            }
        }
        for (int variant = 0; variant < 2; variant++) {
            Arrays.sort(samples[variant]);
            System.out.printf(Locale.ROOT, "BENCH_RESULT,tracker-player-lists,%d,%d,%s,%s,%.3f,%.3f%n",
                entities, playerCount, layout, variant == 0 ? "previous" : "current", samples[variant][5], samples[variant][10]);
        }
    }

    private static double window(final Runnable operation, final long duration) {
        long count = 0;
        final long started = System.nanoTime();
        long ended;
        do {
            operation.run();
            count++;
            ended = System.nanoTime();
        } while (ended - started < duration);
        return (ended - started) / (count * 1000.0);
    }
}
