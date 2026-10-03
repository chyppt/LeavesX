package org.leavesx.leavesx.config;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.Locale;
import net.minecraft.network.CompressionEncoder;
import net.minecraft.network.Utf8String;
import org.spongepowered.configurate.CommentedConfigurationNode;

/** 独立微基准：测量当前线程的分配和耗时，不作为整服 TPS 或网卡带宽承诺。 */
public final class NetworkBufferBenchmark {
    private static volatile int sink;
    private static final com.sun.management.ThreadMXBean ALLOCATIONS =
        (com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();

    public static void main(final String[] args) throws Exception {
        ALLOCATIONS.setThreadAllocatedMemoryEnabled(true);
        final int order = args.length == 0 ? 0 : Integer.parseInt(args[0]);
        for (int round = 0; round < 2; round++) {
            final boolean enabled = (round + order) % 2 == 1;
            final var node = CommentedConfigurationNode.root();
            node.node("performance", "network", "buffer-optimizations").raw(enabled);
            LeavesXRuntime.publish(LeavesXConfigLoader.fromNode(node, Path.of("benchmark.yml")));
            final String value = "LeavesX 中文区块同步 / 0123456789 ".repeat(16);
            final ByteBuf output = Unpooled.buffer(131072);
            final ByteBuf input = Unpooled.buffer(65536);
            try (final Encoder encoder = new Encoder()) {
                input.writeZero(65536);
                measure("utf8", enabled, 20000, () -> {
                    output.clear();
                    Utf8String.write(output, value, 32767);
                    sink = output.writerIndex();
                });
                measure("java-zlib-64k", enabled, 3000, () -> {
                    input.readerIndex(0);
                    output.clear();
                    encoder.write(input, output);
                    sink = output.writerIndex();
                });
            } finally {
                input.release();
                output.release();
            }
        }
        System.out.println("NETWORK_BENCH_DONE " + sink);
    }

    private static void measure(final String name, final boolean enabled, final int iterations,
                                final Operation operation) throws Exception {
        for (int index = 0; index < iterations; index++) operation.run();
        final long thread = Thread.currentThread().threadId();
        final long bytes = ALLOCATIONS.getThreadAllocatedBytes(thread);
        final long start = System.nanoTime();
        for (int index = 0; index < iterations; index++) operation.run();
        final long elapsed = System.nanoTime() - start;
        final long allocated = ALLOCATIONS.getThreadAllocatedBytes(thread) - bytes;
        System.out.printf(Locale.ROOT, "NETWORK_BENCH %s enabled=%s ns/op=%.1f bytes/op=%.1f%n",
            name, enabled, (double)elapsed / iterations, (double)allocated / iterations);
    }

    @FunctionalInterface
    private interface Operation {
        void run() throws Exception;
    }

    private static final class Encoder extends CompressionEncoder implements AutoCloseable {
        private Encoder() { super(256); }
        private void write(final ByteBuf input, final ByteBuf output) throws Exception { super.encode(null, input, output); }
        @Override public void close() { this.handlerRemoved(null); }
    }
}
