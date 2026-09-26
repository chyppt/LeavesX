package org.leavesx.leavesx.worldgen;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

@Normal
class BiomeCornerCacheTest {
    @Test
    void exactCoordinatesAndCurrentSourceArePreservedAcrossBoundaries() {
        final AtomicReference<Holder<Biome>> source = new AtomicReference<>(Holder.direct(mock(Biome.class)));
        final Holder<Biome> firstBiome = source.get();
        final Holder<Biome> secondBiome = Holder.direct(mock(Biome.class));
        final AtomicReference<BlockPos> sampled = new AtomicReference<>();
        for (long seed : new long[]{0, 42, -1, Long.MIN_VALUE}) {
            final BiomeManager manager = new BiomeManager((x, y, z) -> {
                sampled.set(new BlockPos(x, y, z));
                return source.get();
            }, seed);
            for (int x = -9; x <= 9; x++) for (int y = -9; y <= 9; y++) for (int z = -9; z <= 9; z++) {
                final BlockPos pos = new BlockPos(x, y, z);
                manager.getBiome(pos);
                final BlockPos expected = sampled.get();
                manager.leavesX$getBiomeCached(pos);
                assertEquals(expected, sampled.get());
                source.set(source.get() == firstBiome ? secondBiome : firstBiome);
                assertSame(source.get(), manager.leavesX$getBiomeCached(pos), "live biome changes must be visible");
                assertEquals(expected, sampled.get());
            }
        }
    }

    @Test
    void concurrentQueriesCannotMixCornersBetweenThreads() {
        final Holder<Biome> biome = Holder.direct(mock(Biome.class));
        final ThreadLocal<BlockPos> sampled = new ThreadLocal<>();
        final BiomeManager manager = new BiomeManager((x, y, z) -> {
            sampled.set(new BlockPos(x, y, z));
            return biome;
        }, 91);
        CompletableFuture.allOf(java.util.stream.IntStream.range(0, 32).mapToObj(worker -> CompletableFuture.runAsync(() -> {
            for (int i = 0; i < 1000; i++) {
                final BlockPos pos = new BlockPos(i * 31 + worker, (i & 127) - 64, -i * 17);
                manager.getBiome(pos);
                final BlockPos expected = sampled.get();
                manager.leavesX$getBiomeCached(pos);
                manager.leavesX$getBiomeCached(pos);
                assertEquals(expected, sampled.get());
            }
        })).toArray(CompletableFuture[]::new)).join();
    }
}
