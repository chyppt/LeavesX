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

/** 主线程负责准入和采用脱离的水域搜索结果；绝不把世界访问提交给执行器。 */
public final class WaterPathfinding {
    // 独立限制快照内存，不受共享池较大队列影响；Tick 线程准入绝不阻塞。
    private static final Semaphore SNAPSHOTS = new Semaphore(16);
    private static final LongAdder ACCEPTED = new LongAdder(), ADOPTED = new LongAdder(), INVALID = new LongAdder();
    private static final LongAdder OUTSIDE = new LongAdder(), BUSY = new LongAdder(), FAILED = new LongAdder();
    private static final LongAdder CANCELLED = new LongAdder(), UNAVAILABLE = new LongAdder();
    private WaterPathfinding() { }

    public static @Nullable Path tryCreate(final Mob mob, final PathFinder finder, final PathNavigationRegion region,
        final Set<BlockPos> targets, final float length, final int reach, final float multiplier,
        final BooleanSupplier current, final Supplier<PathNavigationRegion> freshRegion) {
        return tryCreate(mob, finder, region, targets, length, reach, multiplier, current, freshRegion, () -> false);
    }

    /** 取消判断只能读取脱离状态，绝不能读取实体或世界。 */
    public static @Nullable Path tryCreate(final Mob mob, final PathFinder finder, final PathNavigationRegion region,
        final Set<BlockPos> targets, final float length, final int reach, final float multiplier,
        final BooleanSupplier current, final Supplier<PathNavigationRegion> freshRegion, final BooleanSupplier cancelled) {
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
            // 供应器只捕获快照和不可变标量输入；不同生物可以并发运行。
            calculation = LeavesXAsyncRuntime.trySubmitValue(LeavesXAsyncRuntime.Workload.PATHFINDING,
                () -> {
                    // 跳过过期的排队搜索，但让已准入任务正常完成：完成回调必须恰好释放一次快照许可。
                    if (cancelled.getAsBoolean()) { CANCELLED.increment(); return null; }
                    return snapshot.search(orderedTargets, visited, length, reach, multiplier);
                });
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
        return new LeavesXAsyncPath(stableTargets, calculation.thenApply(result -> result == null ? null : result.path()), (path, failure) -> {
            if (!current.getAsBoolean() || mob.isRemoved() || !mob.isAlive()) { CANCELLED.increment(); return null; }
            final PathNavigationRegion liveRegion = freshRegion.get();
            if (failure != null) {
                FAILED.increment();
            } else {
                final WaterPathSnapshot.Result result = calculation.join();
                if (result == null) return null; // Detached cancellation before the search started.
                if (result.outside()) OUTSIDE.increment();
                else if (finder.leavesX$maxVisitedNodes() == visited && !finder.leavesX$capturesDebug()
                    && body.equals(WaterPathSnapshot.Body.capture(mob, body.breaching())) && result.matches(liveRegion)) {
                    ADOPTED.increment();
                    return path;
                } else INVALID.increment();
            }
            // 校验失败时绝不把部分节点与新结果混合；所有实时读取仍在所有者线程完成。
            return finder.findPath(liveRegion, mob, stableTargets, length, reach, multiplier);
        }, current);
    }

    public static String diagnostics() {
        return "水中快照寻路：提交 " + ACCEPTED.sum() + " / 采用 " + ADOPTED.sum() + " / 状态变化重算 " + INVALID.sum()
            + " / 越界重算 " + OUTSIDE.sum() + " / 繁忙回退 " + BUSY.sum() + " / 快照不可用 " + UNAVAILABLE.sum()
            + " / 异常重算 " + FAILED.sum() + " / 过期取消 " + CANCELLED.sum();
    }
}
