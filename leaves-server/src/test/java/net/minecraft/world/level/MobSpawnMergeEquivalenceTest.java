package net.minecraft.world.level;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.Arrays;
import java.util.Random;
import net.minecraft.world.entity.MobCategory;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.leavesx.leavesx.runtime.LeavesXComputeExecutor;

/** 在实际双计数入口固定分片，核对合并值、迭代次序及每套结果的独立所有权。 */
@Normal
public class MobSpawnMergeEquivalenceTest {
    @AfterEach
    void shutdown() { LeavesXComputeExecutor.shutdown(); }

    @Test
    void sparseLocalRangesAndInterleavedKeysKeepOriginalMergeOrder() {
        final int size = 4_097;
        for (final int localSize : new int[] {1, 33, size}) {
            final var players = inputs(size, 17L);
            final var local = inputs(localSize, 29L);
            final var expectedLocal = mergeReference(local, size, 7);
            final var expectedPlayers = mergeReference(players, size, 7);
            try (var execution = org.mockito.Mockito.mockStatic(LeavesXComputeExecutor.class, call -> {
                if (call.getMethod().getName().equals("computePartitionCapacity")) return 7;
                if (call.getMethod().getName().equals("invokeRangesWithCount") && call.getArguments().length == 4) {
                    final LeavesXComputeExecutor.RangeTask task = call.getArgument(3);
                    for (int partition = 0; partition < 7; partition++) {
                        task.run(partition, partition * size / 7, (partition + 1) * size / 7);
                    }
                    return 7;
                }
                return call.callRealMethod();
            })) {
                final var result = MobSpawnCountAccumulator.countBoth(local, players, 128);
                assertCountsAndOrder(expectedLocal, result.local());
                assertCountsAndOrder(expectedPlayers, result.players());
            }
        }
    }

    @Test
    void identicalInputsStillProduceIndependentMapsAndCounterArrays() {
        LeavesXComputeExecutor.configure(3, 64, true, true);
        final var input = inputs(4_097, 31L);
        final var first = MobSpawnCountAccumulator.countBoth(input, input, 128);
        final var second = MobSpawnCountAccumulator.countBoth(input, input, 128);
        assertNotSame(first.local(), first.players());
        assertCountsAndOrder(first.local(), first.players());
        assertEquals(first.local().size(), second.local().size());
        for (final long key : first.local().keySet()) {
            assertArrayEquals(first.local().get(key), second.local().get(key));
            assertNotSame(first.local().get(key), first.players().get(key));
            assertNotSame(first.local().get(key), second.local().get(key));
        }
        final long key = first.local().keySet().iterator().nextLong();
        final int oldValue = second.local().get(key)[0];
        first.local().get(key)[0] += 100;
        assertEquals(oldValue, first.players().get(key)[0]);
        assertEquals(oldValue, second.local().get(key)[0]);
    }

    @Test
    void commitPreservesChunkAndCategoryOrderOnTheCallingThread() {
        final var localInputs = inputs(257, 47L);
        final var playerInputs = inputs(511, 59L);
        final var local = MobSpawnCountAccumulator.count(localInputs, false, 128);
        final var players = MobSpawnCountAccumulator.count(playerInputs, false, 128);
        final java.util.List<CountWrite> expected = new java.util.ArrayList<>();
        appendReference(expected, "local", local);
        appendReference(expected, "player", players);
        final Thread owner = Thread.currentThread();
        final java.util.List<CountWrite> actual = new java.util.ArrayList<>();
        final var localCounter = org.mockito.Mockito.mock(LocalMobCapCalculator.class);
        final var playerCounter = org.mockito.Mockito.mock(net.minecraft.server.level.ChunkMap.class);
        org.mockito.Mockito.doAnswer(call -> {
            assertSame(owner, Thread.currentThread());
            actual.add(new CountWrite("local", call.getArgument(0), call.getArgument(1), call.getArgument(2)));
            return null;
        }).when(localCounter).addMobs(org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.any(MobCategory.class), org.mockito.ArgumentMatchers.anyInt());
        org.mockito.Mockito.doAnswer(call -> {
            assertSame(owner, Thread.currentThread());
            actual.add(new CountWrite("player", call.getArgument(0), call.getArgument(1), call.getArgument(2)));
            return null;
        }).when(playerCounter).updatePlayerMobTypeMap(org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.any(MobCategory.class), org.mockito.ArgumentMatchers.anyInt());
        final var totals = new it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<MobCategory>();
        final var potential = new PotentialCalculator();
        final var prepared = new MobSpawnCounting.Prepared(localInputs, playerInputs, playerCounter, totals, potential);
        final var result = MobSpawnCounting.commit(new MobSpawnCounting.Computed(prepared, local, players), localCounter);
        assertEquals(expected, actual);
        assertSame(totals, result.mobCounts());
        assertSame(potential, result.spawnPotential());
    }

    private static void appendReference(final java.util.List<CountWrite> expected, final String kind,
                                        final Long2ObjectOpenHashMap<int[]> counts) {
        for (final var entry : counts.long2ObjectEntrySet()) {
            for (final MobCategory category : NaturalSpawner.SPAWNING_CATEGORIES) {
                final int amount = entry.getValue()[category.ordinal()];
                if (amount != 0) expected.add(new CountWrite(kind, entry.getLongKey(), category, amount));
            }
        }
    }

    private record CountWrite(String kind, long chunkKey, MobCategory category, int amount) {}

    private static MobSpawnCountAccumulator.Inputs inputs(final int size, final long seed) {
        final long[] keys = new long[size];
        final byte[] categories = new byte[size];
        final Random random = new Random(seed);
        for (int index = 0; index < size; index++) {
            keys[index] = switch (index % 11) {
                case 0 -> 0L;
                case 1 -> Long.MIN_VALUE;
                case 2 -> Long.MAX_VALUE;
                default -> random.nextLong(512) - 256;
            };
            categories[index] = (byte)(index % MobCategory.values().length);
        }
        return new MobSpawnCountAccumulator.Inputs(keys, categories, size);
    }

    private static Long2ObjectOpenHashMap<int[]> mergeReference(final MobSpawnCountAccumulator.Inputs input,
                                                               final int size, final int partitions) {
        Long2ObjectOpenHashMap<int[]> merged = null;
        for (int partition = 0; partition < partitions; partition++) {
            final int start = (int)((long)(partition * size / partitions) * input.size() / size);
            final int end = (int)((long)((partition + 1) * size / partitions) * input.size() / size);
            final var range = new MobSpawnCountAccumulator.Inputs(Arrays.copyOfRange(input.chunkKeys(), start, end),
                Arrays.copyOfRange(input.categoryOrdinals(), start, end), end - start);
            final var partial = range.size() == 0 ? new Long2ObjectOpenHashMap<int[]>()
                : MobSpawnCountAccumulator.count(range, false, 128);
            if (merged == null) {
                merged = partial;
                continue;
            }
            // 保留改动前的普通条目迭代及合并实现作为独立对照。
            for (final var entry : partial.long2ObjectEntrySet()) {
                final int[] existing = merged.get(entry.getLongKey());
                if (existing == null) merged.put(entry.getLongKey(), entry.getValue());
                else for (int category = 0; category < existing.length; category++) existing[category] += entry.getValue()[category];
            }
        }
        return merged;
    }

    private static void assertCountsAndOrder(final Long2ObjectOpenHashMap<int[]> expected,
                                            final Long2ObjectOpenHashMap<int[]> actual) {
        assertArrayEquals(expected.keySet().toLongArray(), actual.keySet().toLongArray());
        for (final long key : expected.keySet()) assertArrayEquals(expected.get(key), actual.get(key));
    }
}
