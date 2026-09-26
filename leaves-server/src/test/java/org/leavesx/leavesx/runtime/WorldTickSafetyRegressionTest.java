package org.leavesx.leavesx.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.event.Event;
import org.bukkit.plugin.PluginManager;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.leavesx.leavesx.config.LeavesXConfig;

@Normal
@Timeout(value = 10, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
public class WorldTickSafetyRegressionTest {
    @AfterEach
    void reset() {
        LeavesXWorldTicker.shutdownExecutors();
        LeavesXEventGate.setEnabled(false);
        LeavesXEventGate.reopen();
        LeavesXRegionTicking.configure(LeavesXConfig.RegionTickingSettings.safeDefaults());
    }

    @Test
    void vanillaDimensionsKeepOriginalOrderAndThread() {
        final List<ServerLevel> levels = List.of(
            world("custom", customDimension()), world("world", Level.OVERWORLD),
            world("world_nether", Level.NETHER), world("world_the_end", Level.END));
        LeavesXWorldTicker.configure(4);
        LeavesXWorldTicker.refreshWorlds(levels);
        final Thread serverThread = Thread.currentThread();
        final List<ServerLevel> visited = new ArrayList<>();
        final boolean parallel = LeavesXWorldTicker.tickAllParallel(() -> true, (level, time) -> {
            // Failing here also exercises the old ticker's swallowed worker exceptions.
            assertSame(serverThread, Thread.currentThread());
            visited.add(level);
        });
        assertFalse(parallel);
        assertEquals(levels, visited, "Portal transfers must observe the original world tick order");
    }

    @Test
    void workerFailureReachesServerWithoutReplayingWorld() {
        LeavesXWorldTicker.configure(1);
        LeavesXWorldTicker.refreshWorlds(List.of(world("isolated", customDimension())));
        final IllegalStateException cause = new IllegalStateException("world tick failed");
        final CompletionException failure = assertThrows(CompletionException.class,
            () -> LeavesXWorldTicker.tickAllParallel(() -> true, (level, time) -> { throw cause; }));
        assertSame(cause, failure.getCause());
    }

    @Test
    void successfulWorkersCompleteAndClearOwnership() {
        LeavesXWorldTicker.configure(2);
        LeavesXWorldTicker.refreshWorlds(List.of(world("a", customDimension()), world("b", customDimension())));
        final List<LeavesXWorldTicker.WorldTickThread> workers = java.util.Collections.synchronizedList(new ArrayList<>());
        assertTrue(LeavesXWorldTicker.tickAllParallel(() -> true, (level, time) -> {
            final LeavesXWorldTicker.WorldTickThread worker = (LeavesXWorldTicker.WorldTickThread) Thread.currentThread();
            assertSame(level, worker.currentTickingWorld);
            assertTrue(LeavesXEventGate.isWorldTickThread());
            workers.add(worker);
        }));
        assertEquals(2, workers.size());
        workers.forEach(worker -> assertNull(worker.currentTickingWorld));
    }

    @Test
    void oneWorkerSlotStillPumpsEventsForEveryWorld() {
        LeavesXWorldTicker.configure(1);
        LeavesXWorldTicker.refreshWorlds(List.of(world("a", customDimension()), world("b", customDimension())));
        final AtomicInteger handled = new AtomicInteger();
        final PluginManager manager = mock(PluginManager.class);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
            assertTrue(LeavesXWorldTicker.tickAllParallel(() -> true, (level, time) -> {
                assertTrue(LeavesXEventGate.deferFromWorldThread(new LeavesXEventGateTest.MarkerEvent()));
                handled.incrementAndGet();
            }));
        }
        assertEquals(2, handled.get(), "Slot admission must not prevent event pumping");
    }

    @Test
    void failedEventBatchReleasesEveryWaitingWorld() {
        LeavesXWorldTicker.configure(2);
        LeavesXWorldTicker.refreshWorlds(List.of(world("a", customDimension()), world("b", customDimension())));
        final PluginManager manager = mock(PluginManager.class);
        final IllegalStateException cause = new IllegalStateException("event dispatch failed");
        doThrow(cause).when(manager).callEvent(any(Event.class));
        // Wait until both submissions exist before allowing the pump to drain one batch.
        final List<Thread> workers = new ArrayList<>();
        final AtomicInteger failures = new AtomicInteger();
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
            for (int i = 0; i < 2; i++) {
                final Thread worker = new Thread(() -> {
                    LeavesXEventGate.enterWorldThread();
                    try {
                        LeavesXEventGate.deferFromWorldThread(new LeavesXEventGateTest.MarkerEvent());
                    } catch (final IllegalStateException failure) {
                        if (failure == cause) {
                            failures.incrementAndGet();
                        }
                    } finally {
                        LeavesXEventGate.exitWorldThread();
                    }
                });
                worker.setDaemon(true);
                workers.add(worker);
                worker.start();
            }
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (LeavesXEventGate.pendingCount() != 2 && System.nanoTime() < deadline) {
                LockSupport.parkNanos(100_000L);
            }
            assertEquals(2, LeavesXEventGate.pendingCount());
            assertSame(cause, assertThrows(IllegalStateException.class, LeavesXEventGate::pump));
            for (final Thread worker : workers) {
                try {
                    worker.join(1_000);
                } catch (final InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
                assertFalse(worker.isAlive(), "No removed queue entry may be abandoned");
            }
            assertEquals(2, failures.get());
        } finally {
            LeavesXEventGate.close();
        }
    }

    @Test
    void rejectedSubmissionIsReportedWithoutRunningOrReplayingWorld() throws Exception {
        LeavesXWorldTicker.configure(1);
        LeavesXWorldTicker.refreshWorlds(List.of(world("isolated", customDimension())));
        final Field worldsField = LeavesXWorldTicker.class.getDeclaredField("worlds");
        worldsField.setAccessible(true);
        final Object worldExecutor = ((List<?>) worldsField.get(null)).getFirst();
        final Field executorField = worldExecutor.getClass().getDeclaredField("executor");
        executorField.setAccessible(true);
        ((ExecutorService) executorField.get(worldExecutor)).shutdown();
        final AtomicInteger calls = new AtomicInteger();
        final CompletionException failure = assertThrows(CompletionException.class,
            () -> LeavesXWorldTicker.tickAllParallel(() -> true, (level, time) -> calls.incrementAndGet()));
        assertTrue(failure.getCause() instanceof RejectedExecutionException);
        assertEquals(0, calls.get());
    }

    @Test
    void pinnedCustomWorldProtectsEntireOrderedGroup() {
        LeavesXRegionTicking.configure(new LeavesXConfig.RegionTickingSettings(
            LeavesXConfig.RegionTickingSettings.Mode.WORLDS, 8, List.of("PINNED"), 65_536, 25, 64, 2));
        final List<ServerLevel> levels = List.of(world("first", customDimension()), world("pinned", customDimension()));
        LeavesXWorldTicker.refreshWorlds(levels);
        final List<ServerLevel> visited = new ArrayList<>();
        final Thread serverThread = Thread.currentThread();
        assertFalse(LeavesXWorldTicker.tickAllParallel(() -> true, (level, time) -> {
            assertSame(serverThread, Thread.currentThread());
            visited.add(level);
        }));
        assertEquals(levels, visited);
        assertTrue(LeavesXRegionTicking.describeMode().contains("serial-compatibility"));
    }

    @Test
    void transferAndPortalTicketAreVisibleBeforeDestinationTick() {
        final ServerLevel source = world("source", Level.OVERWORLD);
        final ServerLevel destination = world("destination", Level.NETHER);
        LeavesXWorldTicker.refreshWorlds(List.of(source, destination));
        final List<String> trace = new ArrayList<>();
        // A timing contract test, not a replacement for an in-game portal machine test.
        LeavesXWorldTicker.tickAllParallel(() -> true, (level, time) -> {
            if (level == source) {
                trace.add("source-tick");
                trace.add("destination-add-entity");
                trace.add("destination-portal-ticket");
            } else {
                assertEquals(List.of("source-tick", "destination-add-entity", "destination-portal-ticket"), trace);
                trace.add("destination-tick");
            }
        });
        assertEquals(4, trace.size());
    }

    private static ResourceKey<Level> customDimension() {
        return ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("leavesx_test", "isolated"));
    }

    private static ServerLevel world(final String name, final ResourceKey<Level> dimension) {
        final ServerLevel level = mock(ServerLevel.class);
        final CraftWorld world = mock(CraftWorld.class);
        when(world.getName()).thenReturn(name);
        when(level.getWorld()).thenReturn(world);
        when(level.dimension()).thenReturn(dimension);
        return level;
    }
}
