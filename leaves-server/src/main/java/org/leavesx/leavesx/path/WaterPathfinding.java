package org.leavesx.leavesx.path;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.SwimNodeEvaluator;
import org.jspecify.annotations.Nullable;
import org.leavesx.leavesx.config.LeavesXRuntime;
import org.leavesx.leavesx.runtime.LeavesXAsyncRuntime;

/** Main-thread admission and adoption around a detached water search. Never submits world access to the executor. */
public final class WaterPathfinding {
    // Bound snapshot memory independently of the shared pool's larger queue. No blocking admission on the tick thread.
    private static final Semaphore SNAPSHOTS = new Semaphore(16);
    private static final LongAdder ACCEPTED = new LongAdder(), ADOPTED = new LongAdder(), INVALID = new LongAdder();
    private static final LongAdder OUTSIDE = new LongAdder(), BUSY = new LongAdder(), FAILED = new LongAdder();
    private static final LongAdder CANCELLED = new LongAdder(), UNAVAILABLE = new LongAdder();
    private WaterPathfinding() { }

    public static @Nullable Path tryCreate(final Mob mob, final PathFinder finder, final PathNavigationRegion region,
        final Set<BlockPos> targets, final float length, final int reach, final float multiplier,
        final BooleanSupplier current, final Supplier<PathNavigationRegion> freshRegion) {
        if (!LeavesXRuntime.configuration().extensions().asyncWaterPathfinding()
            || !LeavesXRuntime.asyncPathfinding() || !LeavesXAsyncRuntime.enabled(LeavesXAsyncRuntime.Workload.PATHFINDING)
            || finder.getClass() != PathFinder.class || finder.nodeEvaluator.getClass() != SwimNodeEvaluator.class
            || mob.getClass().getClassLoader() != Mob.class.getClassLoader()
            || finder.leavesX$capturesDebug() || !Float.isFinite(length) || length < 8 || !Float.isFinite(multiplier)) return null;
        final var body = WaterPathSnapshot.Body.capture(mob, ((SwimNodeEvaluator) finder.nodeEvaluator).leavesX$allowBreaching());
        if (body.width() < 1 || body.height() < 1 || body.width() > 4 || body.height() > 4) return null;
        if (!SNAPSHOTS.tryAcquire()) { BUSY.increment(); return null; }
        final CompletableFuture<WaterPathSnapshot.Result> calculation;
        final int visited = finder.leavesX$maxVisitedNodes();
        try {
            final WaterPathSnapshot snapshot = WaterPathSnapshot.capture(region, body);
            if (snapshot == null) { UNAVAILABLE.increment(); SNAPSHOTS.release(); return null; }
            final List<BlockPos> orderedTargets = targets.stream().map(BlockPos::immutable).toList();
            // Only snapshot + immutable scalar inputs are captured by this supplier. Different mobs can run concurrently.
            calculation = LeavesXAsyncRuntime.trySubmitValue(LeavesXAsyncRuntime.Workload.PATHFINDING,
                () -> snapshot.search(orderedTargets, visited, length, reach, multiplier));
        } catch (final RuntimeException failure) {
            SNAPSHOTS.release();
            FAILED.increment();
            return null;
        } catch (final Error failure) {
            SNAPSHOTS.release();
            throw failure;
        }
        if (calculation == null) { SNAPSHOTS.release(); BUSY.increment(); return null; }
        calculation.whenComplete((ignored, failure) -> SNAPSHOTS.release());
        ACCEPTED.increment();
        final Set<BlockPos> stableTargets = new java.util.LinkedHashSet<>();
        for (final BlockPos pos : targets) stableTargets.add(pos.immutable());
        return new LeavesXAsyncPath(stableTargets, calculation.thenApply(WaterPathSnapshot.Result::path), (path, failure) -> {
            if (!current.getAsBoolean() || mob.isRemoved() || !mob.isAlive()) { CANCELLED.increment(); return null; }
            final PathNavigationRegion liveRegion = freshRegion.get();
            if (failure != null) {
                FAILED.increment();
            } else {
                final WaterPathSnapshot.Result result = calculation.join();
                if (result.outside()) OUTSIDE.increment();
                else if (finder.leavesX$maxVisitedNodes() == visited && !finder.leavesX$capturesDebug()
                    && body.equals(WaterPathSnapshot.Body.capture(mob, body.breaching())) && result.matches(liveRegion)) {
                    ADOPTED.increment();
                    return path;
                } else INVALID.increment();
            }
            // Validation failures never combine partial nodes with a new result. All live reads remain on this owner thread.
            return finder.findPath(liveRegion, mob, stableTargets, length, reach, multiplier);
        }, current);
    }

    public static String diagnostics() {
        return "水中快照寻路：提交 " + ACCEPTED.sum() + " / 采用 " + ADOPTED.sum() + " / 状态变化重算 " + INVALID.sum()
            + " / 越界重算 " + OUTSIDE.sum() + " / 繁忙回退 " + BUSY.sum() + " / 快照不可用 " + UNAVAILABLE.sum()
            + " / 异常重算 " + FAILED.sum() + " / 过期取消 " + CANCELLED.sum();
    }
}
