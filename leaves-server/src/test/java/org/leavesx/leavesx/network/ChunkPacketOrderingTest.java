package org.leavesx.leavesx.network;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.netty.channel.embedded.EmbeddedChannel;
import io.papermc.paper.util.MCUtil;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;
import org.leavesx.leavesx.config.LeavesXConfig;
import org.leavesx.leavesx.runtime.LeavesXAsyncRuntime;

@Normal
class ChunkPacketOrderingTest {
    @Test
    void blockUpdatesAndUnloadCannotOvertakePendingChunk() throws Exception {
        verifyQueue(false);
    }

    @Test
    void disconnectedQueueDoesNotResurrectCompletedChunks() throws Exception {
        verifyQueue(true);
    }

    private static void verifyQueue(final boolean discard) throws Exception {
        LeavesXAsyncRuntime.configure(new LeavesXConfig.AsyncSettings(
            true, 64, false, false, 0, 64, 30, false, 64, false, 0, 64));
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch started = new CountDownLatch(1);
        final Connection connection = new Connection(PacketFlow.SERVERBOUND);
        final PacketListener listener = mock(PacketListener.class);
        when(listener.protocol()).thenReturn(ConnectionProtocol.PLAY);
        final var listenerField = Connection.class.getDeclaredField("packetListener");
        listenerField.setAccessible(true);
        listenerField.set(connection, listener);
        connection.isPending = false;
        final EmbeddedChannel channel = new EmbeddedChannel(connection);
        // 这里没有运行中的服务端单例；只模拟所有权，验证真实队列。
        try (final var mainThread = mockStatic(MCUtil.class, CALLS_REAL_METHODS)) {
            mainThread.when(MCUtil::isMainThread).thenReturn(true);
            final ChunkSectionSnapshot snapshot = mock(ChunkSectionSnapshot.class);
            when(snapshot.encode()).thenAnswer(invocation -> {
                started.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Release timeout");
                return new byte[] {1};
            });
            final DeferredChunkSections pending = new DeferredChunkSections(snapshot);
            assertTrue(started.await(5, TimeUnit.SECONDS));
            final Packet<?> chunk = mock(Packet.class);
            when(chunk.isReady()).thenAnswer(invocation -> pending.isReady());
            final Packet<?> block = mock(Packet.class);
            final Packet<?> unload = mock(Packet.class);
            when(block.isReady()).thenReturn(true);
            when(unload.isReady()).thenReturn(true);
            connection.send(chunk);
            connection.send(block);
            connection.send(unload);
            final Method drain = Connection.class.getDeclaredMethod("processQueue");
            drain.setAccessible(true);
            assertEquals(false, drain.invoke(connection));
            assertNull(channel.readOutbound(), "No update can overtake the unready chunk");
            if (discard) connection.clearPacketQueue();
            release.countDown();
            assertArrayEquals(new byte[] {1}, pending.bytes());
            assertEquals(true, drain.invoke(connection));
            channel.runPendingTasks();
            if (!discard) {
                assertSame(chunk, channel.readOutbound());
                assertSame(block, channel.readOutbound());
                assertSame(unload, channel.readOutbound());
            }
            assertNull(channel.readOutbound());
        } finally {
            release.countDown();
            connection.clearPacketQueue();
            channel.finishAndReleaseAll();
            LeavesXAsyncRuntime.shutdown();
        }
    }
}
