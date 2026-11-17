package org.leavesx.leavesx.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

/** 与每次新建随机源的原循环对照，覆盖负坐标、种子交替及共享放置器并发调用。 */
@Normal
public class ParallelStructurePlacementTest {
    @Test
    void reusedWorkerRandomPreservesVanillaSequenceAcrossSeedsAndPlacements() {
        for (final RandomSpreadType spread : RandomSpreadType.values()) {
            final var placement = new RandomSpreadStructurePlacement(34, 8, spread, 10_387_312);
            for (final long seed : new long[]{0L, 1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE}) {
                for (int coordinate = -1024; coordinate <= 1024; coordinate++) {
                    final int x = coordinate * 31;
                    final int z = -coordinate * 17 - 1;
                    assertEquals(vanilla(seed, x, z, spread), placement.getPotentialStructureChunk(seed, x, z));
                }
            }
        }
    }

    @Test
    void generationWorkersCannotContaminateEachOthersRandomState() throws Exception {
        final var linear = new RandomSpreadStructurePlacement(34, 8, RandomSpreadType.LINEAR, 10_387_312);
        final var triangular = new RandomSpreadStructurePlacement(34, 8, RandomSpreadType.TRIANGULAR, 10_387_312);
        try (var workers = Executors.newFixedThreadPool(4)) {
            final var tasks = new ArrayList<Callable<Void>>();
            for (int task = 0; task < 32; task++) {
                final long seed = task * 341873128712L;
                tasks.add(() -> {
                    for (int index = -512; index < 512; index++) {
                        assertEquals(vanilla(seed, index, -index, RandomSpreadType.LINEAR),
                            linear.getPotentialStructureChunk(seed, index, -index));
                        assertEquals(vanilla(~seed, -index, index, RandomSpreadType.TRIANGULAR),
                            triangular.getPotentialStructureChunk(~seed, -index, index));
                    }
                    return null;
                });
            }
            for (final var result : workers.invokeAll(tasks, 15, TimeUnit.SECONDS)) result.get();
        }
    }

    @Test
    void secureWorldPlacementKeepsItsIndependentRandomStream() {
        final var secure = SecureFeatureSeed.parse("12".repeat(128)).forDimension("minecraft:overworld");
        for (final RandomSpreadType spread : RandomSpreadType.values()) {
            final var placement = new RandomSpreadStructurePlacement(34, 8, spread, 10_387_312);
            for (int x = -100; x <= 100; x++) {
                final int gridX = Math.floorDiv(x, 34);
                final int gridZ = Math.floorDiv(-x, 34);
                final var random = secure.random("structure-placement", gridX, gridZ, 10_387_312);
                random.setLargeFeatureWithSalt(42L, gridX, gridZ, 10_387_312);
                final var expected = new ChunkPos(gridX * 34 + spread.evaluate(random, 26),
                    gridZ * 34 + spread.evaluate(random, 26));
                // 在同一线程交替运行普通世界与安全种子世界。
                placement.getPotentialStructureChunk(17L, x, -x);
                assertEquals(expected, placement.leavesX$getPotentialStructureChunk(42L, x, -x, secure));
            }
        }
    }

    private static ChunkPos vanilla(final long seed, final int x, final int z, final RandomSpreadType spread) {
        final int gridX = Math.floorDiv(x, 34);
        final int gridZ = Math.floorDiv(z, 34);
        final var random = new WorldgenRandom(new LegacyRandomSource(0L));
        random.setLargeFeatureWithSalt(seed, gridX, gridZ, 10_387_312);
        return new ChunkPos(gridX * 34 + spread.evaluate(random, 26), gridZ * 34 + spread.evaluate(random, 26));
    }
}
