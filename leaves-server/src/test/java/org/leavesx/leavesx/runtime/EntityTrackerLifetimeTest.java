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

/** 不需要运行中的世界或网络连接，直接验证真实实体追踪入口。 */
@Normal
class EntityTrackerLifetimeTest {
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
                LeavesXComputeExecutor.Workload.ENTITY_TRACKING, 256, 256, 64
            )).thenReturn(false);
            entry.invoke(fixture.map);
            compute.verify(() -> LeavesXComputeExecutor.shouldAttempt(
                LeavesXComputeExecutor.Workload.ENTITY_TRACKING, 256, 256, 64
            ));
            compute.verify(() -> LeavesXComputeExecutor.invokeRangesWithCount(
                any(LeavesXComputeExecutor.Workload.class), anyInt(), anyInt(), anyInt(), any()
            ), never());
        }
        // 原串行入口仍逐个检查实体，不得因拒绝并行而漏掉追踪。
        for (int index = 0; index < fixture.entities.size(); index++) {
            verify(fixture.entities.getRawDataUnchecked()[index]).moonrise$getTrackedEntity();
        }
    }

    @Test
    void indexedPlayerLookupPreservesFirstIdentityAndClearsReferences() throws Exception {
        final Fixture fixture = new Fixture();
        final Object frame = fixture.frame;
        final Class<?> type = frame.getClass();
        final Method capacity = type.getDeclaredMethod("ensureCapacity", int.class);
        final Method capture = type.getDeclaredMethod("capture", int.class, ServerPlayer.class, double.class);
        final Method lookup = type.getDeclaredMethod("indexOfOrAdd", ServerPlayer.class, double.class);
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
        // 重复帧条目必须保留原线性扫描的首个匹配语义。
        capture.invoke(frame, 32, players[0], 256.0);
        set(frame, "size", 33);
        for (int index = 0; index < players.length; index++) assertEquals(index, lookup.invoke(frame, players[index], 128.0));
        assertEquals(33, lookup.invoke(frame, mock(ServerPlayer.class), 128.0));
        clear.invoke(frame);
        final Field indices = type.getDeclaredField("playerIndices");
        indices.setAccessible(true);
        assertEquals(0, ((java.util.Map<?, ?>) indices.get(frame)).size());
    }

    @Test
    void fallbackNeverComputesSnapshotsBeyondCurrentEntityCount() throws Exception {
        final Fixture fixture = new Fixture();
        // 过期尾部快照没有坐标帧；回退时触碰它必须让测试失败。
        final Class<?> snapshotType = fixture.snapshots.getClass().getComponentType();
        final var constructor = snapshotType.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        final Object obsolete = constructor.newInstance(null, null, null);
        set(obsolete, "playerCount", 1);
        Array.set(fixture.snapshots, 1, obsolete);

        try (var compute = mockStatic(LeavesXComputeExecutor.class)) {
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
            for (int index = 0; index < Array.getLength(this.snapshots); index++) {
                assertNull(Array.get(this.snapshots, index), "snapshot slot " + index);
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
