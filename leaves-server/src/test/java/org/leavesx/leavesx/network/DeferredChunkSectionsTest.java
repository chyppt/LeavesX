package org.leavesx.leavesx.network;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.leavesx.leavesx.config.LeavesXConfig;
import org.leavesx.leavesx.runtime.LeavesXAsyncRuntime;

@Normal
class DeferredChunkSectionsTest {
    private static void configure() {
        LeavesXAsyncRuntime.configure(new LeavesXConfig.AsyncSettings(
            true, 64, false, false, 0, 64, 30, false, 64, false, 0, 64));
    }

    @AfterEach
    void shutdown() {
        LeavesXAsyncRuntime.shutdown();
    }

    @Test
    void packetStaysUnreadyUntilWholeSnapshotHasCompleted() throws Exception {
        configure();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicReference<Thread> worker = new AtomicReference<>();
        final ChunkSectionSnapshot snapshot = mock(ChunkSectionSnapshot.class);
        when(snapshot.encode()).thenAnswer(invocation -> {
            worker.set(Thread.currentThread());
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test release timed out");
            return new byte[] {1, 2, 3};
        });
        final DeferredChunkSections pending = new DeferredChunkSections(snapshot);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertFalse(pending.isReady());
            assertNotSame(Thread.currentThread(), worker.get());
        } finally {
            release.countDown();
        }
        assertArrayEquals(new byte[] {1, 2, 3}, pending.bytes());
        assertTrue(pending.isReady());
        verify(snapshot, times(1)).encode();
    }

    @Test
    void encodingFailureRetriesCompleteSnapshotAndCachesOnlySuccess() {
        LeavesXAsyncRuntime.shutdown();
        final ChunkSectionSnapshot snapshot = mock(ChunkSectionSnapshot.class);
        when(snapshot.encode()).thenThrow(new IllegalStateException("Injected encoding failure"))
            .thenReturn(new byte[] {4, 5});
        final DeferredChunkSections pending = new DeferredChunkSections(snapshot);
        assertTrue(pending.isReady());
        assertArrayEquals(new byte[] {4, 5}, pending.bytes());
        assertSame(pending.bytes(), pending.bytes());
        verify(snapshot, times(2)).encode();
    }

    @Test
    void repeatedFailureDoesNotRetryOrPublishPartialData() {
        LeavesXAsyncRuntime.shutdown();
        final ChunkSectionSnapshot snapshot = mock(ChunkSectionSnapshot.class);
        when(snapshot.encode()).thenThrow(new IllegalStateException("Injected permanent encoding failure"));
        final byte[] destination = {9};
        final long failedBefore = LeavesXNetworkMetrics.snapshot().failed();
        final DeferredChunkSections pending = new DeferredChunkSections(snapshot, destination);

        assertTrue(pending.isReady(), "A failed packet must leave the connection queue for encoder error handling");
        assertThrows(IllegalStateException.class, pending::bytes);
        assertTrue(pending.isReady());
        assertThrows(IllegalStateException.class, pending::bytes);
        assertArrayEquals(new byte[] {9}, destination);
        assertEquals(failedBefore + 1, LeavesXNetworkMetrics.snapshot().failed());
        verify(snapshot, times(2)).encode();
    }

    @Test
    void wrongEncodedLengthIsTerminalAndNeverChangesPacketBuffer() {
        LeavesXAsyncRuntime.shutdown();
        final ChunkSectionSnapshot snapshot = mock(ChunkSectionSnapshot.class);
        when(snapshot.encode()).thenReturn(new byte[] {1, 2});
        final byte[] destination = {8};
        final DeferredChunkSections pending = new DeferredChunkSections(snapshot, destination);

        assertTrue(pending.isReady());
        assertThrows(IllegalStateException.class, pending::bytes);
        assertArrayEquals(new byte[] {8}, destination);
        verify(snapshot, times(1)).encode();
    }

    @Test
    void readinessDoesNotBlockWhileAnotherCallerRetriesEncoding() throws Exception {
        LeavesXAsyncRuntime.shutdown();
        final CountDownLatch retryStarted = new CountDownLatch(1);
        final CountDownLatch releaseRetry = new CountDownLatch(1);
        final ChunkSectionSnapshot snapshot = mock(ChunkSectionSnapshot.class);
        when(snapshot.encode()).thenThrow(new IllegalStateException("First encoding failed")).thenAnswer(invocation -> {
            retryStarted.countDown();
            if (!releaseRetry.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Retry release timed out");
            return new byte[] {4};
        });
        // 对象返回时，内联执行的首次尝试已经失败。
        final DeferredChunkSections pending = new DeferredChunkSections(snapshot);
        final long completedBefore = LeavesXNetworkMetrics.snapshot().completed();
        try (final var reader = java.util.concurrent.Executors.newFixedThreadPool(3)) {
            final var result = reader.submit(pending::bytes);
            final var secondResult = reader.submit(pending::bytes);
            try {
                assertTrue(retryStarted.await(5, TimeUnit.SECONDS));
                assertFalse(reader.submit(pending::isReady).get(1, TimeUnit.SECONDS));
            } finally {
                releaseRetry.countDown();
            }
            final byte[] encoded = result.get(5, TimeUnit.SECONDS);
            assertArrayEquals(new byte[] {4}, encoded);
            assertSame(encoded, secondResult.get(5, TimeUnit.SECONDS));
            assertTrue(pending.isReady());
            assertEquals(completedBefore + 1, LeavesXNetworkMetrics.snapshot().completed());
            verify(snapshot, times(2)).encode();
        }
    }

    @Test
    void fatalEncodingErrorIsNotRetriedAndIsReleased() throws Exception {
        LeavesXAsyncRuntime.shutdown();
        final ChunkSectionSnapshot snapshot = mock(ChunkSectionSnapshot.class);
        final OutOfMemoryError fatal = new OutOfMemoryError("Injected failure, no allocation pressure");
        when(snapshot.encode()).thenThrow(fatal);
        final DeferredChunkSections pending = new DeferredChunkSections(snapshot);
        final long fallbackBefore = LeavesXNetworkMetrics.snapshot().fallback();

        assertSame(fatal, assertThrows(OutOfMemoryError.class, pending::isReady));
        assertTrue(pending.isReady());
        assertSame(fatal, assertThrows(OutOfMemoryError.class, pending::bytes));
        assertEquals(fallbackBefore, LeavesXNetworkMetrics.snapshot().fallback());
        verify(snapshot, times(1)).encode();
        assertReleased(pending);
    }

    @Test
    void terminalSuccessAndFailureReleaseDetachedSnapshot() throws Exception {
        LeavesXAsyncRuntime.shutdown();
        final ChunkSectionSnapshot success = mock(ChunkSectionSnapshot.class);
        when(success.encode()).thenReturn(new byte[] {1});
        final DeferredChunkSections completed = new DeferredChunkSections(success);
        completed.bytes();
        assertReleased(completed);

        final ChunkSectionSnapshot failure = mock(ChunkSectionSnapshot.class);
        when(failure.encode()).thenThrow(new IllegalStateException("Injected failure"));
        final DeferredChunkSections failed = new DeferredChunkSections(failure);
        assertTrue(failed.isReady());
        assertReleased(failed);
    }

    @Test
    void cancelledEncodingRetriesWholeSnapshotOnce() {
        final ChunkSectionSnapshot snapshot = mock(ChunkSectionSnapshot.class);
        when(snapshot.encode()).thenReturn(new byte[] {6});
        final CompletableFuture<byte[]> cancelled = new CompletableFuture<>();
        cancelled.cancel(false);
        try (final var runtime = mockStatic(LeavesXAsyncRuntime.class)) {
            runtime.when(() -> LeavesXAsyncRuntime.submitValueOrRun(eq(LeavesXAsyncRuntime.Workload.CHUNK_SEND), any()))
                .thenReturn(cancelled);
            final DeferredChunkSections pending = new DeferredChunkSections(snapshot);
            assertTrue(pending.isReady());
            assertArrayEquals(new byte[] {6}, pending.bytes());
            verify(snapshot, times(1)).encode();
        }
    }

    private static void assertReleased(final DeferredChunkSections pending) throws Exception {
        for (final String name : new String[] {"snapshot", "completion"}) {
            final var field = DeferredChunkSections.class.getDeclaredField(name);
            field.setAccessible(true);
            assertNull(field.get(pending), "Terminal publication must release " + name);
        }
    }

    @Test
    void readinessPublishesIntoOriginalPacketBufferOnlyOnce() {
        LeavesXAsyncRuntime.shutdown();
        final ChunkSectionSnapshot snapshot = mock(ChunkSectionSnapshot.class);
        when(snapshot.encode()).thenReturn(new byte[] {2, 4});
        final byte[] packetBuffer = new byte[2];
        final DeferredChunkSections pending = new DeferredChunkSections(snapshot, packetBuffer);
        assertTrue(pending.isReady());
        assertSame(packetBuffer, pending.bytes());
        assertArrayEquals(new byte[] {2, 4}, packetBuffer);
        packetBuffer[0] = 8; // A packet listener's later edits must not be overwritten by another readiness poll.
        assertTrue(pending.isReady());
        assertEquals(8, pending.bytes()[0]);
        verify(snapshot, times(1)).encode();
    }

    @Test
    void fullQueueFallsBackWithoutLeavingAnUnreadyPacket() throws Exception {
        configure();
        final CountDownLatch release = new CountDownLatch(1);
        final var jobs = new ArrayList<CompletableFuture<Boolean>>();
        try {
            // 填满队列前先让所有核心工作线程执行任务；预启动线程可能还在等待首次任务，过早拒绝不代表稳定饱和。
            final var executors = LeavesXAsyncRuntime.class.getDeclaredField("executors");
            executors.setAccessible(true);
            final Object state = ((java.util.Map<?, ?>) executors.get(null)).get(LeavesXAsyncRuntime.Workload.CHUNK_SEND);
            final var executorField = state.getClass().getDeclaredField("executor");
            executorField.setAccessible(true);
            final var executor = (java.util.concurrent.ThreadPoolExecutor) executorField.get(state);
            final CountDownLatch started = new CountDownLatch(executor.getCorePoolSize());
            for (int index = 0; index < executor.getCorePoolSize(); index++) {
                jobs.add(LeavesXAsyncRuntime.trySubmitValue(LeavesXAsyncRuntime.Workload.CHUNK_SEND, () -> {
                    started.countDown();
                    try { return release.await(10, TimeUnit.SECONDS); }
                    catch (final InterruptedException interruption) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interruption);
                    }
                }));
            }
            assertTrue(started.await(5, TimeUnit.SECONDS), "All workers must hold a task before queue saturation");
            while (jobs.size() < 100) {
                final var job = LeavesXAsyncRuntime.trySubmitValue(LeavesXAsyncRuntime.Workload.CHUNK_SEND, () -> {
                    try { return release.await(10, TimeUnit.SECONDS); }
                    catch (final InterruptedException interruption) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interruption);
                    }
                });
                if (job == null) break;
                jobs.add(job);
            }
            assertTrue(jobs.size() < 100, "Bounded queue should reject excess work");
            final Thread caller = Thread.currentThread();
            final ChunkSectionSnapshot snapshot = mock(ChunkSectionSnapshot.class);
            when(snapshot.encode()).thenAnswer(invocation -> {
                assertSame(caller, Thread.currentThread());
                return new byte[] {7};
            });
            final DeferredChunkSections pending = new DeferredChunkSections(snapshot);
            assertTrue(pending.isReady());
            assertArrayEquals(new byte[] {7}, pending.bytes());
        } finally {
            release.countDown();
            CompletableFuture.allOf(jobs.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
        }
    }
}
