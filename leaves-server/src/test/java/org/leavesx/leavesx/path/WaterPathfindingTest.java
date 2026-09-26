package org.leavesx.leavesx.path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.leavesx.leavesx.config.ConfigurationTestSupport.publish;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.SwimNodeEvaluator;
import net.minecraft.world.phys.AABB;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.leavesx.leavesx.config.LeavesXConfig;
import org.leavesx.leavesx.runtime.LeavesXAsyncRuntime;
import org.leavesx.leavesx.runtime.LeavesXComputeExecutor;
import org.spongepowered.configurate.BasicConfigurationNode;

@Normal
class WaterPathfindingTest {
    @AfterEach void cleanup() {
        LeavesXAsyncRuntime.shutdown();
        publish(LeavesXConfig.safeDefaults());
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7})
    void snapshotPreservesVanillaSearchAndNeverReadsTheWorldOnWorker(final int scenario) throws Exception {
        final BlockPos start = new BlockPos(-18, 3, -17);
        final float width = scenario == 6 ? 1.3F : 0.8F;
        final Mob mob = mob(start, width);
        if (scenario == 7) when(mob.getPathfindingMalus(PathType.WATER)).thenReturn(3.5F);
        final boolean breach = scenario % 2 == 0;
        final var region = region(pos -> terrain(pos, scenario));
        final Set<BlockPos> targets = new LinkedHashSet<>(List.of(start.offset(9, scenario == 2 ? 3 : 0, 2), start.offset(-8, 0, 6)));
        final var snapshot = WaterPathSnapshot.capture(region, WaterPathSnapshot.Body.capture(mob, breach));
        assertNotNull(snapshot);
        final Path expected = new PathFinder(new SwimNodeEvaluator(breach), 2048).findPath(region, mob, targets, 24, 1, 1);
        final var actual = CompletableFuture.supplyAsync(() -> snapshot.search(List.copyOf(targets), 2048, 24, 1, 1)).get(10, TimeUnit.SECONDS);
        assertFalse(actual.outside());
        assertPath(expected, actual.path());
        assertTrue(actual.matches(region));
    }

    @Test void searchBeyondSnapshotReturnsFallbackSignalRatherThanATruncatedRoute() {
        final Mob mob = mob(BlockPos.ZERO, 0.8F);
        final var region = region(pos -> Blocks.WATER.defaultBlockState());
        final var snapshot = WaterPathSnapshot.capture(region, WaterPathSnapshot.Body.capture(mob, false));
        final var result = snapshot.search(List.of(new BlockPos(40, 0, 0)), 2048, 64, 0, 1);
        assertTrue(result.outside());
        assertNull(result.path());
    }

    @Test void terrainChangesCauseOneCompleteOwnerThreadRecalculation() throws Exception {
        configure();
        final AtomicBoolean changed = new AtomicBoolean();
        final var region = region(pos -> changed.get() && pos.getX() == 4 ? Blocks.STONE.defaultBlockState() : Blocks.WATER.defaultBlockState());
        final Mob mob = mob(BlockPos.ZERO, 0.8F);
        final PathFinder finder = new PathFinder(new SwimNodeEvaluator(false), 1024);
        final Set<BlockPos> targets = Set.of(new BlockPos(10, 0, 0));
        final Path path = WaterPathfinding.tryCreate(mob, finder, region, targets, 24, 1, 1, () -> true, () -> region);
        assertInstanceOf(LeavesXAsyncPath.class, path);
        changed.set(true);
        final Path adopted = ((LeavesXAsyncPath) path).resolvedPath();
        assertPath(finder.findPath(region, mob, targets, 24, 1, 1), adopted);
        assertTrue(WaterPathfinding.diagnostics().contains("状态变化重算"));
    }

    @Test void oldOrRemovedMobRequestsCannotAdoptOrRecompute() {
        configure();
        final var region = region(pos -> Blocks.WATER.defaultBlockState());
        final Mob mob = mob(BlockPos.ZERO, 0.8F);
        final AtomicBoolean current = new AtomicBoolean(true);
        final var finder = new PathFinder(new SwimNodeEvaluator(false), 1024);
        final Path pending = WaterPathfinding.tryCreate(mob, finder, region, Set.of(new BlockPos(10, 0, 0)),
            24, 1, 1, current::get, () -> { throw new AssertionError("obsolete request must not read terrain"); });
        assertNotNull(pending);
        current.set(false);
        assertNull(((LeavesXAsyncPath) pending).resolvedPath());
        current.set(true);
        final Path removed = WaterPathfinding.tryCreate(mob, finder, region, Set.of(new BlockPos(10, 0, 0)),
            24, 1, 1, current::get, () -> { throw new AssertionError("removed mob must not recompute"); });
        when(mob.isRemoved()).thenReturn(true);
        assertNull(((LeavesXAsyncPath) removed).resolvedPath());
    }

    @Test void poolBudgetRejectsWithoutWaitingAndIndependentSearchesUseBothWorkers() throws Exception {
        configure();
        final CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1);
        for (int i = 0; i < 2; i++) LeavesXAsyncRuntime.trySubmitValue(LeavesXAsyncRuntime.Workload.PATHFINDING, () -> {
            entered.countDown();
            try { assertTrue(release.await(20, TimeUnit.SECONDS)); } catch (InterruptedException ex) { throw new AssertionError(ex); }
            return null;
        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            final var region = region(pos -> Blocks.WATER.defaultBlockState());
            final var mob = mob(BlockPos.ZERO, 0.8F);
            final var finder = new PathFinder(new SwimNodeEvaluator(false), 1024);
            for (int i = 0; i < 16; i++) assertNotNull(WaterPathfinding.tryCreate(mob, finder, region,
                Set.of(new BlockPos(10, 0, 0)), 24, 1, 1, () -> true, () -> region));
            assertNull(WaterPathfinding.tryCreate(mob, finder, region, Set.of(new BlockPos(10, 0, 0)),
                24, 1, 1, () -> true, () -> region));
        } finally { release.countDown(); }
    }

    @Test void pendingPathRejectsWorkerAdoptionAndRecoversFromComputationFailureOnOwner() throws Exception {
        final Path expected = new Path(new java.util.ArrayList<>(List.of(new net.minecraft.world.level.pathfinder.Node(1, 2, 3))), new BlockPos(1, 2, 3), true);
        final Thread owner = Thread.currentThread();
        final var pending = new LeavesXAsyncPath(Set.of(BlockPos.ZERO), CompletableFuture.failedFuture(new IllegalStateException("injected")), (path, failure) -> {
            assertSame(owner, Thread.currentThread());
            assertNotNull(failure);
            return expected;
        });
        final Throwable failure = CompletableFuture.supplyAsync(() -> assertThrows(IllegalStateException.class, pending::resolvedPath)).get(5, TimeUnit.SECONDS);
        assertNotNull(failure);
        assertSame(expected, pending.resolvedPath());
    }

    @Test void disabledUnknownAndUnloadedInputsUseOriginalSearch() {
        configure();
        final var mob = mob(BlockPos.ZERO, 0.8F);
        final var region = region(pos -> Blocks.WATER.defaultBlockState());
        final var custom = new PathFinder(new SwimNodeEvaluator(false) {}, 1024);
        assertNull(WaterPathfinding.tryCreate(mob, custom, region, Set.of(new BlockPos(10, 0, 0)), 24, 1, 1, () -> true, () -> region));
        final var missing = mock(PathNavigationRegion.class);
        assertNull(WaterPathSnapshot.capture(missing, WaterPathSnapshot.Body.capture(mob, false)));
        publish(LeavesXConfig.safeDefaults());
        assertNull(WaterPathfinding.tryCreate(mob, new PathFinder(new SwimNodeEvaluator(false), 1024), region,
            Set.of(new BlockPos(10, 0, 0)), 24, 1, 1, () -> true, () -> region));
    }

    private static void configure() {
        final var config = BasicConfigurationNode.root();
        try { config.node("async", "pathfinding", "water-snapshot").set(true); } catch (Exception failure) { throw new AssertionError(failure); }
        publish(config);
        LeavesXComputeExecutor.shutdown();
        LeavesXAsyncRuntime.configure(new LeavesXConfig.AsyncSettings(false, 64, false, true, 2, 64, 60, false, 64, false, 0, 64));
    }

    private static Mob mob(final BlockPos start, final float width) {
        final Mob mob = mock(Mob.class);
        when(mob.getBoundingBox()).thenReturn(new AABB(start.getX(), start.getY(), start.getZ(), start.getX() + width, start.getY() + 0.8, start.getZ() + width));
        when(mob.getBbWidth()).thenReturn(width);
        when(mob.getBbHeight()).thenReturn(0.8F);
        when(mob.blockPosition()).thenReturn(start);
        when(mob.level()).thenReturn(mock(Level.class));
        when(mob.isAlive()).thenReturn(true);
        return mob;
    }

    private static PathNavigationRegion region(final Function<BlockPos, BlockState> terrain) {
        final Thread owner = Thread.currentThread();
        final var region = mock(PathNavigationRegion.class);
        final org.mockito.stubbing.Answer<BlockState> state = invocation -> {
            assertSame(owner, Thread.currentThread(), "worker must not read the live region");
            return terrain.apply(invocation.getArgument(0));
        };
        when(region.getBlockState(any())).thenAnswer(state);
        when(region.getBlockStateIfLoaded(any())).thenAnswer(state);
        when(region.getFluidState(any())).thenAnswer(invocation -> state.answer(invocation).getFluidState());
        when(region.getChunkIfLoaded(anyInt(), anyInt())).thenAnswer(invocation -> {
            assertSame(owner, Thread.currentThread());
            final int chunkX = invocation.getArgument(0), chunkZ = invocation.getArgument(1);
            final var chunk = mock(net.minecraft.world.level.chunk.ChunkAccess.class);
            when(chunk.getSectionIndex(anyInt())).thenAnswer(call -> ((Integer) call.getArgument(0)) >> 4);
            when(chunk.getSection(anyInt())).thenAnswer(call -> {
                assertSame(owner, Thread.currentThread());
                final int sectionY = call.getArgument(0);
                final var palette = new net.minecraft.world.level.chunk.PalettedContainer<BlockState>(Blocks.AIR.defaultBlockState(),
                    net.minecraft.world.level.chunk.Strategy.createForBlockStates(net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY), null);
                final var cursor = new BlockPos.MutableBlockPos();
                for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    palette.set(x, y, z, terrain.apply(cursor.set(chunkX * 16 + x, sectionY * 16 + y, chunkZ * 16 + z)));
                }
                final var section = mock(net.minecraft.world.level.chunk.LevelChunkSection.class);
                when(section.getStates()).thenReturn(palette);
                return section;
            });
            return chunk;
        });
        return region;
    }

    private static BlockState terrain(final BlockPos pos, final int scenario) {
        if (scenario == 4) return Blocks.STONE.defaultBlockState();
        if (pos.getY() > 5) return Blocks.AIR.defaultBlockState();
        if (pos.getY() < 0) return Blocks.STONE.defaultBlockState();
        if ((scenario == 1 || scenario == 3) && pos.getX() == -14 && pos.getZ() < -14) return Blocks.STONE.defaultBlockState();
        if (scenario == 5 && pos.getX() == -14) return Blocks.KELP.defaultBlockState();
        return Blocks.WATER.defaultBlockState();
    }

    private static void assertPath(final Path expected, final Path actual) {
        if (expected == null) { assertNull(actual); return; }
        assertNotNull(actual);
        assertEquals(expected.getTarget(), actual.getTarget());
        assertEquals(expected.canReach(), actual.canReach());
        assertEquals(expected.getNodeCount(), actual.getNodeCount());
        for (int i = 0; i < expected.getNodeCount(); i++) {
            assertEquals(expected.getNodePos(i), actual.getNodePos(i));
            assertEquals(expected.getNode(i).costMalus, actual.getNode(i).costMalus);
            assertEquals(expected.getNode(i).g, actual.getNode(i).g);
        }
    }
}
