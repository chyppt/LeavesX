package org.leavesx.leavesx.network;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.leavesx.leavesx.runtime.LeavesXAsyncRuntime;

/** 发布完整的脱离区段缓冲区，不改变 Paper 数据包队列顺序。 */
public final class DeferredChunkSections {
    // 由当前对象保护；发布进入终态后释放调色板副本和工作任务。
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
        // 先写入原始数据包缓冲区，再向数据包检查器报告就绪。终态失败同样视为就绪，交给普通连接路径编码。
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
                // 等待发生在监视器之外，避免一个数据包检查器阻塞其他连接的就绪轮询。并发读取者等待同一次发布，不重复重试。
                encoded = pending.join();
            } catch (final CompletionException | CancellationException failure) {
                // 资源耗尽时不要再次发起高分配量的编码尝试。
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
