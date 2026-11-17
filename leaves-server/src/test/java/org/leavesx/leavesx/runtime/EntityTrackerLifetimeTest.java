package org.leavesx.leavesx.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.spottedleaf.moonrise.common.list.ReferenceList;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

/** Exercises the real tracker entry point without needing a running world or network connection. */
@Normal
class EntityTrackerLifetimeTest {
    @Test
    void emptyPlayerFastPathStillRejectsChangedChunksAndPlayerLists() throws Exception {
        final Fixture fixture = new Fixture();
        final Entity entity = mock(Entity.class);
        final var tracker = mock(ChunkMap.TrackedEntity.class, CALLS_REAL_METHODS);
        set(tracker, "this$0", fixture.map);
        set(tracker, "entity", entity);
        final Class<?> type = fixture.snapshots.getClass().getComponentType();
        final var constructor = type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        final Object snapshot = constructor.newInstance(tracker, entity, fixture.frame);
        final Class<?> chunkType = ca.spottedleaf.moonrise.common.misc.NearbyPlayers.TrackedChunk.class;
        final Method reset = type.getDeclaredMethod("resetEmpty", chunkType, long.class, boolean.class);
        final Method matches = ChunkMap.TrackedEntity.class.getDeclaredMethod("leavesX$matchesSnapshot", type, chunkType);
        reset.setAccessible(true);
        matches.setAccessible(true);
        final var chunk = mock(ca.spottedleaf.moonrise.common.misc.NearbyPlayers.TrackedChunk.class);
        final ReferenceList<ServerPlayer> players = new ReferenceList<>(new ServerPlayer[0]);
        when(chunk.getUpdateCount()).thenReturn(7L);
        when(chunk.getPlayers(ca.spottedleaf.moonrise.common.misc.NearbyPlayers.NearbyMapType.VIEW_DISTANCE)).thenReturn(players);
        reset.invoke(snapshot, chunk, 7L, true);
        assertEquals(true, matches.invoke(tracker, snapshot, chunk));
        verify(entity, never()).getX();
        verify(entity, never()).getY();
        verify(entity, never()).getZ();
        players.add(mock(ServerPlayer.class));
        assertEquals(false, matches.invoke(tracker, snapshot, chunk));
        players.clear();
        when(chunk.getUpdateCount()).thenReturn(8L);
        assertEquals(false, matches.invoke(tracker, snapshot, chunk));
        assertEquals(false, matches.invoke(tracker, snapshot, (Object)null));
        reset.invoke(snapshot, null, -1L, true);
        assertEquals(true, matches.invoke(tracker, snapshot, (Object)null));
        assertEquals(false, matches.invoke(tracker, snapshot, chunk));
    }

    @Test
    void disablingEmptyPlayerFastPathKeepsCoordinateChecks() throws Exception {
        final Fixture fixture = new Fixture();
        final Entity entity = mock(Entity.class);
        final var tracker = mock(ChunkMap.TrackedEntity.class, CALLS_REAL_METHODS);
        set(tracker, "this$0", fixture.map);
        set(tracker, "entity", entity);
        final Class<?> type = fixture.snapshots.getClass().getComponentType();
        final var constructor = type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        final Object snapshot = constructor.newInstance(tracker, entity, fixture.frame);
        final Class<?> chunkType = ca.spottedleaf.moonrise.common.misc.NearbyPlayers.TrackedChunk.class;
        final Method reset = type.getDeclaredMethod("resetEmpty", chunkType, long.class, boolean.class);
        final Method matches = ChunkMap.TrackedEntity.class.getDeclaredMethod("leavesX$matchesSnapshot", type, chunkType);
        reset.setAccessible(true);
        matches.setAccessible(true);
        reset.invoke(snapshot, null, -1L, false);
        verify(entity).getX();
        verify(entity).getY();
        verify(entity).getZ();
        when(entity.getX()).thenReturn(1.0);
        assertEquals(false, matches.invoke(tracker, snapshot, (Object)null));
    }

    @Test
    void pipelineAndSubmissionFailureKeepVisibilityAndCommitOrder() throws Exception {
        for (int mode = 0; mode < 4; mode++) {
            final boolean failSubmission = (mode & 1) != 0;
            final boolean sharedPlayers = (mode & 2) != 0;
            final var settings = mock(org.leavesx.leavesx.config.LeavesXConfig.AsyncSettings.class,
                org.mockito.AdditionalAnswers.delegatesTo(org.leavesx.leavesx.config.LeavesXConfig.safeDefaults().asyncSettings()));
            when(settings.kernels()).thenReturn(new org.leavesx.leavesx.config.LeavesXConfig.ComputeKernelSettings(
                true, true, true, true, sharedPlayers, true, 32_768));
            final Fixture fixture = new Fixture();
            fixture.entities.clear();
            set(fixture.frame, "positionData", new double[3]);
            set(fixture.frame, "viewDistanceBlocks", new double[1]);
            final Thread owner = Thread.currentThread();
            final ServerPlayer player = mock(ServerPlayer.class);
            when(player.getX()).thenAnswer(invocation -> { assertSame(owner, Thread.currentThread()); return 0.0; });
            when(fixture.level.players()).thenReturn(List.of(player));
            final var hooks = mock(ca.spottedleaf.moonrise.common.PlatformHooks.class);
            when(hooks.getSendViewDistance(player)).thenReturn(8);
            final var config = mock(io.papermc.paper.configuration.WorldConfiguration.class);
            config.entities = config.new Entities();
            config.entities.trackingRangeY = config.entities.new TrackingRangeY();
            when(fixture.level.paperConfig()).thenReturn(config);
            final var server = mock(net.minecraft.server.MinecraftServer.class);
            when(server.getScaledTrackingDistance(anyInt())).thenAnswer(invocation -> invocation.getArgument(0));
            when(fixture.level.getServer()).thenReturn(server);
            final ReferenceList<ServerPlayer> players = new ReferenceList<>(new ServerPlayer[0]);
            players.add(player);
            final var chunk = mock(ca.spottedleaf.moonrise.common.misc.NearbyPlayers.TrackedChunk.class);
            final java.util.concurrent.atomic.AtomicLong chunkRevision = new java.util.concurrent.atomic.AtomicLong();
            when(chunk.getUpdateCount()).thenAnswer(invocation -> chunkRevision.get());
            when(chunk.getPlayers(ca.spottedleaf.moonrise.common.misc.NearbyPlayers.NearbyMapType.VIEW_DISTANCE)).thenReturn(players);
            final var chunkData = new ca.spottedleaf.moonrise.patches.chunk_system.level.chunk.ChunkData();
            chunkData.nearbyPlayers = chunk;
            final java.util.List<Object> snapshots = new java.util.ArrayList<>();
            final java.util.List<ChunkMap.TrackedEntity> trackers = new java.util.ArrayList<>();
            final java.util.List<Integer> commits = new java.util.ArrayList<>();
            final Class<?> snapshotType = fixture.snapshots.getClass().getComponentType();
            final var constructor = snapshotType.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            for (int index = 0; index < 260; index++) {
                final int slot = index;
                final Entity entity = mock(Entity.class);
                when(entity.getX()).thenAnswer(invocation -> { assertSame(owner, Thread.currentThread()); return (double)slot; });
                when(entity.getIndirectPassengers()).thenReturn(List.of());
                when(entity.moonrise$getChunkData()).thenReturn(chunkData);
                when(entity.moonrise$getChunkStatus()).thenReturn(net.minecraft.server.level.FullChunkStatus.ENTITY_TICKING);
                final var tracker = mock(ChunkMap.TrackedEntity.class, CALLS_REAL_METHODS);
                org.mockito.Mockito.doNothing().when(tracker).removePlayer(player);
                final var serverEntity = mock(net.minecraft.server.level.ServerEntity.class);
                org.mockito.Mockito.doAnswer(invocation -> {
                    assertSame(owner, Thread.currentThread());
                    commits.add(slot);
                    // 模拟前面的提交移除最后一个实体，尾槽清空不能导致异常或重复提交。
                    if (slot == 0) fixture.entities.remove(fixture.entities.getRawDataUnchecked()[259]);
                    if (slot == 0) {
                        // 模拟插件在前一个实体提交中改动附近玩家列表；后续快照必须按当前列表重算。
                        players.remove(player);
                        chunkRevision.incrementAndGet();
                    }
                    if (slot == 1) {
                        final Object first = get(snapshots.get(0), "playerIndices");
                        final Object second = get(snapshots.get(1), "playerIndices");
                        if (sharedPlayers) assertSame(first, second);
                        else org.junit.jupiter.api.Assertions.assertNotSame(first, second);
                    }
                    return null;
                }).when(serverEntity).sendChanges();
                set(tracker, "this$0", fixture.map);
                set(tracker, "entity", entity);
                set(tracker, "range", 128);
                set(tracker, "seenBy", new java.util.HashSet<>());
                set(tracker, "serverEntity", serverEntity);
                trackers.add(tracker);
                final Object snapshot = constructor.newInstance(tracker, entity, fixture.frame);
                set(tracker, "leavesX$trackerSnapshot", snapshot);
                snapshots.add(snapshot);
                when(entity.moonrise$getTrackedEntity()).thenReturn(tracker);
                fixture.entities.add(entity);
            }
            LeavesXComputeExecutor.configure(4, 64, true, true);
            try (var platform = mockStatic(ca.spottedleaf.moonrise.common.PlatformHooks.class);
                 var compute = mockStatic(LeavesXComputeExecutor.class, CALLS_REAL_METHODS);
                 var runtime = mockStatic(org.leavesx.leavesx.config.LeavesXRuntime.class, CALLS_REAL_METHODS)) {
                platform.when(ca.spottedleaf.moonrise.common.PlatformHooks::get).thenReturn(hooks);
                runtime.when(org.leavesx.leavesx.config.LeavesXRuntime::asyncSettings).thenReturn(settings);
                if (failSubmission) compute.when(() -> LeavesXComputeExecutor.tryExecuteSnapshot(
                    any(LeavesXComputeExecutor.Workload.class), any(Runnable.class), any()
                )).thenThrow(new java.util.concurrent.RejectedExecutionException("injected submission failure"));
                assertEquals(true, fixture.tick());
                assertEquals(failSubmission ? 1 : 0, LeavesXComputeExecutor.metrics().fullRecomputationRequests());
                if (!failSubmission) assertTrue(LeavesXComputeExecutor.metrics().workerItems() > 0);
            } finally {
                LeavesXComputeExecutor.shutdown();
            }
            assertEquals(259, commits.size());
            for (int index = 0; index < commits.size(); index++) assertEquals(index, commits.get(index));
            for (int index = 1; index < trackers.size() - 1; index++) verify(trackers.get(index)).moonrise$tick(chunk);
            for (int index = 0; index < snapshots.size(); index++) {
                final boolean[] visible = (boolean[])get(snapshots.get(index), "inRange");
                assertEquals(index <= 128, visible[0], "visibility of entity " + index);
            }
            fixture.assertReleased();
        }
    }

    @Test
    void rejectedAdmissionSkipsSnapshotPreparation() throws Exception {
        final Fixture fixture = new Fixture();
        final var lookup = mock(ca.spottedleaf.moonrise.patches.chunk_system.level.entity.server.ServerEntityLookup.class);
        while (fixture.entities.size() < 256) fixture.entities.add(mock(Entity.class));
        set(lookup, "trackerEntities", fixture.entities);
        when(fixture.level.moonrise$getEntityLookup()).thenReturn(lookup);
        when(fixture.level.players()).thenReturn(List.of(mock(ServerPlayer.class)));
        // 故意清空快照准备依赖：误入快照路径会抛异常，不能靠统计数字掩盖额外复制。
        set(fixture.map, "leavesX$trackerPlayerFrame", null);
        final Method entry = ChunkMap.class.getDeclaredMethod("newTrackerTick");
        entry.setAccessible(true);
        try (var async = mockStatic(LeavesXAsyncRuntime.class);
             var compute = mockStatic(LeavesXComputeExecutor.class)) {
            async.when(() -> LeavesXAsyncRuntime.enabled(LeavesXAsyncRuntime.Workload.ENTITY_TRACKER)).thenReturn(true);
            compute.when(() -> LeavesXComputeExecutor.shouldAttempt(
                LeavesXComputeExecutor.Workload.ENTITY_TRACKING, 0, 256, 64
            )).thenReturn(false);
            entry.invoke(fixture.map);
            compute.verify(() -> LeavesXComputeExecutor.shouldAttempt(
                LeavesXComputeExecutor.Workload.ENTITY_TRACKING, 0, 256, 64
            ));
            compute.verify(() -> LeavesXComputeExecutor.invokeRangesWithCount(
                any(LeavesXComputeExecutor.Workload.class), anyInt(), anyInt(), anyInt(), any()
            ), never());
        }
        // 原串行入口仍逐个检查实体，不得因拒绝并行而漏掉追踪。
        for (int index = 0; index < fixture.entities.size(); index++) {
            verify(fixture.entities.getRawDataUnchecked()[index], org.mockito.Mockito.times(2)).moonrise$getTrackedEntity();
        }
    }

    @Test
    void admissionStopsOnceEnoughComparisonsAreKnown() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.entities.clear();
        final Entity first = mock(Entity.class);
        final ChunkMap.TrackedEntity tracker = mock(ChunkMap.TrackedEntity.class, CALLS_REAL_METHODS);
        set(tracker, "entity", first);
        when(first.moonrise$getTrackedEntity()).thenReturn(tracker);
        final var data = new ca.spottedleaf.moonrise.patches.chunk_system.level.chunk.ChunkData();
        when(first.moonrise$getChunkData()).thenReturn(data);
        final var nearby = mock(ca.spottedleaf.moonrise.common.misc.NearbyPlayers.TrackedChunk.class);
        set(data, "nearbyPlayers", nearby);
        final ReferenceList<ServerPlayer> players = new ReferenceList<>(new ServerPlayer[0]);
        for (int index = 0; index < 256; index++) players.add(mock(ServerPlayer.class));
        when(nearby.getPlayers(ca.spottedleaf.moonrise.common.misc.NearbyPlayers.NearbyMapType.VIEW_DISTANCE)).thenReturn(players);
        fixture.entities.add(first);
        final Entity tail = mock(Entity.class);
        when(tail.moonrise$getTrackedEntity()).thenThrow(new AssertionError("准入已足够，不应继续扫描尾部"));
        fixture.entities.add(tail);
        final Method estimate = ChunkMap.class.getDeclaredMethod("leavesX$estimatedTrackerComparisons", ReferenceList.class);
        estimate.setAccessible(true);
        final var defaults = org.leavesx.leavesx.config.LeavesXConfig.safeDefaults().asyncSettings();
        for (final boolean lowLoad : new boolean[] {false, true}) {
            final var settings = mock(org.leavesx.leavesx.config.LeavesXConfig.AsyncSettings.class,
                org.mockito.AdditionalAnswers.delegatesTo(defaults));
            when(settings.lowLoadWorkers()).thenReturn(lowLoad);
            try (var runtime = mockStatic(org.leavesx.leavesx.config.LeavesXRuntime.class)) {
                runtime.when(org.leavesx.leavesx.config.LeavesXRuntime::asyncSettings).thenReturn(settings);
                assertEquals(lowLoad ? 1 : 256, estimate.invoke(fixture.map, fixture.entities));
            }
        }
    }

    @Test
    void existingPlayerLookupDoesNotReadViewDistanceAgain() throws Exception {
        final Fixture fixture = new Fixture();
        final Class<?> type = fixture.frame.getClass();
        final Method capacity = type.getDeclaredMethod("ensureCapacity", int.class);
        final Method capture = type.getDeclaredMethod("capture", int.class, ServerPlayer.class, double.class);
        final Method lookup = type.getDeclaredMethod("indexOfOrAdd", ServerPlayer.class);
        for (final Method method : List.of(capacity, capture, lookup)) method.setAccessible(true);
        capacity.invoke(fixture.frame, 4);
        final ServerPlayer player = mock(ServerPlayer.class);
        capture.invoke(fixture.frame, 0, player, 128.0);
        set(fixture.frame, "size", 1);
        try (var hooks = mockStatic(ca.spottedleaf.moonrise.common.PlatformHooks.class)) {
            hooks.when(ca.spottedleaf.moonrise.common.PlatformHooks::get)
                .thenThrow(new AssertionError("重复玩家不能再次读取视距"));
            for (int index = 0; index < 1000; index++) assertEquals(0, lookup.invoke(fixture.frame, player));
        }
    }

    @Test
    void indexedPlayerLookupPreservesFirstIdentityAndClearsReferences() throws Exception {
        final Fixture fixture = new Fixture();
        final Object frame = fixture.frame;
        final Class<?> type = frame.getClass();
        final Method capacity = type.getDeclaredMethod("ensureCapacity", int.class);
        final Method capture = type.getDeclaredMethod("capture", int.class, ServerPlayer.class, double.class);
        final Method lookup = type.getDeclaredMethod("indexOfOrAdd", ServerPlayer.class);
        final Method clear = type.getDeclaredMethod("clear");
        for (final Method method : List.of(capacity, capture, lookup, clear)) method.setAccessible(true);
        capacity.invoke(frame, 64);
        set(frame, "indexed", true);
        final ServerPlayer[] players = new ServerPlayer[32];
        for (int index = 0; index < players.length; index++) {
            players[index] = mock(ServerPlayer.class);
            capture.invoke(frame, index, players[index], 128.0);
        }
        set(frame, "size", 32);
        // A duplicate frame entry must retain the original linear scan's first-match semantics.
        capture.invoke(frame, 32, players[0], 256.0);
        set(frame, "size", 33);
        for (int index = 0; index < players.length; index++) assertEquals(index, lookup.invoke(frame, players[index]));
        try (var hooks = mockStatic(ca.spottedleaf.moonrise.common.PlatformHooks.class)) {
            final var platform = mock(ca.spottedleaf.moonrise.common.PlatformHooks.class);
            hooks.when(ca.spottedleaf.moonrise.common.PlatformHooks::get).thenReturn(platform);
            assertEquals(33, lookup.invoke(frame, mock(ServerPlayer.class)));
        }
        clear.invoke(frame);
        final Field indices = type.getDeclaredField("playerIndices");
        indices.setAccessible(true);
        assertEquals(0, ((java.util.Map<?, ?>) indices.get(frame)).size());
    }

    @Test
    void fallbackNeverComputesSnapshotsBeyondCurrentEntityCount() throws Exception {
        final Fixture fixture = new Fixture();
        // An obsolete tail snapshot has no coordinate frame. Touching it in fallback must fail the test.
        final Class<?> snapshotType = fixture.snapshots.getClass().getComponentType();
        final var constructor = snapshotType.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        final Object obsolete = constructor.newInstance(null, null, null);
        set(obsolete, "playerCount", 1);
        Array.set(fixture.snapshots, 1, obsolete);

        final var settings = mock(org.leavesx.leavesx.config.LeavesXConfig.AsyncSettings.class);
        when(settings.trackerComparisonSharding()).thenReturn(true);
        when(settings.kernels()).thenReturn(org.leavesx.leavesx.config.LeavesXConfig.ComputeKernelSettings.defaults());
        try (var compute = mockStatic(LeavesXComputeExecutor.class);
             var runtime = mockStatic(org.leavesx.leavesx.config.LeavesXRuntime.class)) {
            runtime.when(org.leavesx.leavesx.config.LeavesXRuntime::asyncSettings).thenReturn(settings);
            compute.when(() -> LeavesXComputeExecutor.invokeRangesWithCount(
                any(LeavesXComputeExecutor.Workload.class), anyInt(), anyInt(), anyInt(), any()
            )).thenReturn(0);
            assertEquals(true, fixture.tick());
        }
    }

    @Test
    void completedTickReleasesRetainedEntitiesAndPlayerFrame() throws Exception {
        final Fixture fixture = new Fixture();
        try (var compute = mockStatic(LeavesXComputeExecutor.class)) {
            compute.when(() -> LeavesXComputeExecutor.invokeRangesWithCount(
                any(LeavesXComputeExecutor.Workload.class), anyInt(), anyInt(), anyInt(), any()
            )).thenReturn(1);
            assertEquals(true, fixture.tick());
        }
        fixture.assertReleased();
    }

    @Test
    void preparationFailureStillReleasesReferences() throws Exception {
        final Fixture fixture = new Fixture();
        when(fixture.level.players()).thenThrow(new IllegalStateException("injected preparation failure"));
        final InvocationTargetException failure = assertThrows(InvocationTargetException.class, fixture::tick);
        assertEquals("injected preparation failure", failure.getCause().getMessage());
        fixture.assertReleased();
    }

    private static final class Fixture {
        private final ChunkMap map = mock(ChunkMap.class, CALLS_REAL_METHODS);
        private final ServerLevel level = mock(ServerLevel.class);
        private final Object frame;
        private final Object snapshots;
        private final ReferenceList<Entity> entities = new ReferenceList<>(new Entity[0]);
        private final Method tick = ChunkMap.class.getDeclaredMethod("leavesX$parallelTrackerTick", ReferenceList.class);

        private Fixture() throws Exception {
            final Class<?> frameType = Class.forName(ChunkMap.class.getName() + "$LeavesXTrackerPlayerFrame");
            final var frameConstructor = frameType.getDeclaredConstructor(ChunkMap.class);
            frameConstructor.setAccessible(true);
            this.frame = frameConstructor.newInstance(this.map);
            final Class<?> snapshotType = Class.forName(ChunkMap.class.getName() + "$LeavesXTrackerSnapshot");
            this.snapshots = Array.newInstance(snapshotType, 4);
            final var snapshotConstructor = snapshotType.getDeclaredConstructors()[0];
            snapshotConstructor.setAccessible(true);
            Array.set(this.snapshots, 3, snapshotConstructor.newInstance(null, null, this.frame));
            set(this.map, "level", this.level);
            set(this.map, "leavesX$trackerPlayerFrame", this.frame);
            set(this.map, "leavesX$trackerSnapshots", this.snapshots);
            set(this.map, "leavesX$trackerRangeBatch", new org.leavesx.leavesx.performance.EntityTrackingRangeBatch());
            when(this.level.players()).thenReturn(List.of());
            set(this.frame, "players", new ServerPlayer[] {mock(ServerPlayer.class)});
            set(this.frame, "size", 1);
            this.entities.add(mock(Entity.class));
            this.tick.setAccessible(true);
        }

        private Object tick() throws Exception {
            return this.tick.invoke(this.map, this.entities);
        }

        private void assertReleased() throws Exception {
            final Object currentSnapshots = get(this.map, "leavesX$trackerSnapshots");
            for (int index = 0; index < Array.getLength(currentSnapshots); index++) {
                assertNull(Array.get(currentSnapshots, index), "snapshot slot " + index);
            }
            for (final Object player : (Object[]) get(this.frame, "players")) {
                assertNull(player);
            }
            assertEquals(0, get(this.frame, "size"));
        }
    }

    private static void set(final Object target, final String name, final Object value) throws Exception {
        final Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object get(final Object target, final String name) throws Exception {
        final Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
