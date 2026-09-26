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
    void fallbackNeverComputesSnapshotsBeyondCurrentEntityCount() throws Exception {
        final Fixture fixture = new Fixture();
        // An obsolete tail snapshot has no coordinate frame. Touching it in fallback must fail the test.
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
