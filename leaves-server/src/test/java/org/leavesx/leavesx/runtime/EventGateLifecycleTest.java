package org.leavesx.leavesx.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.plugin.PluginManager;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

@Normal
class EventGateLifecycleTest {
    @AfterEach
    void reset() {
        LeavesXEventGate.setEnabled(false);
        LeavesXEventGate.exitWorldThread();
        LeavesXEventGate.reopen();
    }

    @Test
    void disablingGateCannotRunSynchronousEventsDirectlyOnWorldWorker() {
        LeavesXEventGate.setEnabled(true);
        LeavesXEventGate.enterWorldThread();
        LeavesXEventGate.setEnabled(false);
        assertThrows(IllegalStateException.class,
            () -> LeavesXEventGate.deferFromWorldThread(new LeavesXEventGateTest.MarkerEvent()));
        assertEquals(0, LeavesXEventGate.pendingCount());
    }

    @Test
    void interruptCancelsEventAlreadyCapturedInPumpBatch() throws Exception {
        LeavesXEventGate.setEnabled(true);
        final AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        final AtomicReference<Throwable> secondFailure = new AtomicReference<>();
        final Thread first = worker(firstFailure);
        final Thread second = worker(secondFailure);
        final PluginManager manager = mock(PluginManager.class);
        doAnswer(invocation -> {
            second.interrupt();
            second.join(2_000L);
            assertFalse(second.isAlive(), "尚未派发的批次成员必须可以取消");
            return null;
        }).when(manager).callEvent(any(Event.class));
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
            first.start();
            awaitQueued(1);
            second.start();
            awaitQueued(2);
            assertEquals(1, LeavesXEventGate.pump());
            first.join(2_000L);
            assertFalse(first.isAlive());
            assertNull(firstFailure.get());
            assertInstanceOf(IllegalStateException.class, secondFailure.get());
            verify(manager, times(1)).callEvent(any(Event.class));
        } finally {
            LeavesXEventGate.close();
            first.join(2_000L);
            second.join(2_000L);
        }
    }

    @Test
    void interruptCannotReleaseWorldWhilePluginCallbackStillRuns() throws Exception {
        LeavesXEventGate.setEnabled(true);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread worker = worker(failure);
        final PluginManager manager = mock(PluginManager.class);
        doAnswer(invocation -> {
            worker.interrupt();
            worker.join(100L);
            assertTrue(worker.isAlive(), "世界必须等待已开始的插件事件结束");
            return null;
        }).when(manager).callEvent(any(Event.class));
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
            worker.start();
            awaitQueued(1);
            assertEquals(1, LeavesXEventGate.pump());
            worker.join(2_000L);
            assertFalse(worker.isAlive());
            assertTrue(failure.get() instanceof IllegalStateException);
            assertTrue(worker.isInterrupted());
        } finally {
            LeavesXEventGate.close();
            worker.join(2_000L);
        }
    }

    @Test
    void closingInsideCallbackCancelsUndispatchedBatchMembers() throws Exception {
        LeavesXEventGate.setEnabled(true);
        final AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        final AtomicReference<Throwable> secondFailure = new AtomicReference<>();
        final Thread first = worker(firstFailure);
        final Thread second = worker(secondFailure);
        final PluginManager manager = mock(PluginManager.class);
        final AtomicInteger callbacks = new AtomicInteger();
        doAnswer(invocation -> {
            callbacks.incrementAndGet();
            LeavesXEventGate.close();
            return null;
        }).when(manager).callEvent(any(Event.class));
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
            first.start();
            awaitQueued(1);
            second.start();
            awaitQueued(2);
            assertEquals(1, LeavesXEventGate.pump());
            first.join(2_000L);
            second.join(2_000L);
            assertFalse(first.isAlive());
            assertFalse(second.isAlive());
            assertNull(firstFailure.get());
            assertTrue(secondFailure.get() instanceof IllegalStateException);
            assertEquals(1, callbacks.get(), "关闭后不能派发批次中剩余的事件");
        } finally {
            LeavesXEventGate.close();
            first.join(2_000L);
            second.join(2_000L);
        }
    }

    private static Thread worker(final AtomicReference<Throwable> failure) {
        final Thread worker = new Thread(() -> {
            LeavesXEventGate.enterWorldThread();
            try {
                LeavesXEventGate.deferFromWorldThread(new LeavesXEventGateTest.MarkerEvent());
            } catch (final Throwable thrown) {
                failure.set(thrown);
            } finally {
                LeavesXEventGate.exitWorldThread();
            }
        });
        worker.setDaemon(true);
        return worker;
    }

    private static void awaitQueued(final int count) {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (LeavesXEventGate.pendingCount() < count && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertEquals(count, LeavesXEventGate.pendingCount());
    }
}
