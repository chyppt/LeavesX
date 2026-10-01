package org.leavesx.leavesx.network;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.leavesx.leavesx.runtime.LeavesXAsyncRuntime;

/** Publishes complete detached section buffers without changing Paper's packet queue ordering. */
public final class DeferredChunkSections {
    // Guarded by this; release palette copies and the worker future once publication is terminal.
    private ChunkSectionSnapshot snapshot;
    private CompletableFuture<byte[]> completion;
    private boolean resolving;
    private final byte[] destination;
    private final CompletableFuture<byte[]> publication = new CompletableFuture<>();

    public DeferredChunkSections(final ChunkSectionSnapshot snapshot) {
        this(snapshot, null);
    }

    public DeferredChunkSections(final ChunkSectionSnapshot snapshot, final byte[] destination) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.destination = destination;
        LeavesXNetworkMetrics.recordSubmitted();
        this.completion = LeavesXAsyncRuntime.submitValueOrRun(LeavesXAsyncRuntime.Workload.CHUNK_SEND, snapshot::encode);
    }

    public boolean isReady() {
        // Publish into the original packet buffer before reporting readiness to packet inspectors. A terminal
        // failure is also ready so the regular packet encoder handles it in the usual connection path.
        this.resolve(false);
        return this.publication.isDone();
    }

    public byte[] bytes() {
        this.resolve(true);
        try {
            return this.publication.join();
        } catch (final CompletionException failure) {
            if (failure.getCause() instanceof RuntimeException exception) throw exception;
            if (failure.getCause() instanceof Error error) throw error;
            throw failure;
        }
    }

    private void resolve(final boolean waitForCompletion) {
        final CompletableFuture<byte[]> pending;
        final ChunkSectionSnapshot detached;
        synchronized (this) {
            if (this.publication.isDone() || this.resolving) return;
            if (!waitForCompletion && !this.completion.isDone()) return;
            this.resolving = true;
            pending = this.completion;
            detached = this.snapshot;
        }

        try {
            byte[] encoded;
            try {
                // Waiting happens outside the monitor so a packet inspector cannot block another connection's
                // readiness poll. Concurrent readers await the same publication and never repeat the retry.
                encoded = pending.join();
            } catch (final CompletionException | CancellationException failure) {
                // Do not turn resource exhaustion into another allocation-heavy encoding attempt.
                if (failure.getCause() instanceof Error error) throw error;
                LeavesXNetworkMetrics.recordFallback();
                encoded = detached.encode();
            }
            Objects.requireNonNull(encoded, "Encoded chunk section data");
            if (this.destination != null) {
                if (this.destination.length != encoded.length) {
                    throw new IllegalStateException("Chunk section snapshot size changed");
                }
                System.arraycopy(encoded, 0, this.destination, 0, encoded.length);
                encoded = this.destination;
            }
            LeavesXNetworkMetrics.recordCompleted();
            this.publication.complete(encoded);
        } catch (final RuntimeException | Error failure) {
            LeavesXNetworkMetrics.recordFailed();
            this.publication.completeExceptionally(failure);
            if (failure instanceof Error error) throw error;
        } finally {
            synchronized (this) {
                this.snapshot = null;
                this.completion = null;
                this.resolving = false;
            }
        }
    }
}
