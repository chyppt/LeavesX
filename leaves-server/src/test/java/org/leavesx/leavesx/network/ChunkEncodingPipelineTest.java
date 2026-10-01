package org.leavesx.leavesx.network;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.papermc.paper.util.MCUtil;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.PacketListener;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.server.MinecraftServer;
import org.bukkit.support.RegistryHelper;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.leavesx.leavesx.config.LeavesXConfig;
import org.leavesx.leavesx.runtime.LeavesXAsyncRuntime;

@Normal
class ChunkEncodingPipelineTest {
    @AfterEach
    void shutdown() {
        LeavesXAsyncRuntime.shutdown();
    }

    @Test
    void permanentFailureUsesConnectionEncoderErrorPathWithoutSendingPartialChunk() throws Exception {
        LeavesXAsyncRuntime.shutdown();
        final ChunkSectionSnapshot snapshot = mock(ChunkSectionSnapshot.class);
        when(snapshot.encode()).thenThrow(new IllegalStateException("Injected permanent chunk encoding failure"));
        final var packet = packet(new DeferredChunkSections(snapshot));
        final Connection connection = connection();
        final var protocol = GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(RegistryHelper.registryAccess()));
        final EmbeddedChannel channel = new EmbeddedChannel(new PacketEncoder<>(protocol), connection);
        try (final var mainThread = mockStatic(MCUtil.class, CALLS_REAL_METHODS);
             final var server = mockStatic(MinecraftServer.class, CALLS_REAL_METHODS)) {
            mainThread.when(MCUtil::isMainThread).thenReturn(true);
            server.when(MinecraftServer::getServer).thenReturn(mock(MinecraftServer.class));
            assertTrue(packet.isReady());
            assertDoesNotThrow(() -> connection.send(packet), "Ordinary encoding failure must not escape the tick caller");
            channel.runPendingTasks();
            assertFalse(channel.isOpen(), "The normal connection failure handler must disconnect this client");
            final ByteBuf outbound = channel.readOutbound();
            assertNotNull(outbound, "The client should receive the normal disconnect packet, not chunk bytes");
            try {
                assertInstanceOf(net.minecraft.network.protocol.common.ClientboundDisconnectPacket.class, protocol.codec().decode(outbound));
            } finally {
                outbound.release();
            }
            assertNull(channel.readOutbound(), "No partially encoded chunk may reach the client");
            verify(snapshot, times(2)).encode();
        } finally {
            connection.clearPacketQueue();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void clearingOneConnectionDoesNotCancelSharedPacketForOtherConnection() throws Exception {
        LeavesXAsyncRuntime.configure(new LeavesXConfig.AsyncSettings(
            true, 64, false, false, 0, 64, 30, false, 64, false, 0, 64));
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final ChunkSectionSnapshot snapshot = mock(ChunkSectionSnapshot.class);
        when(snapshot.encode()).thenAnswer(invocation -> {
            started.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test release timeout");
            return new byte[] {7};
        });
        final DeferredChunkSections pending = new DeferredChunkSections(snapshot);
        final var packet = packet(pending);
        final Connection first = connection();
        final Connection second = connection();
        final EmbeddedChannel firstChannel = new EmbeddedChannel(first);
        final EmbeddedChannel secondChannel = new EmbeddedChannel(second);
        try (final var mainThread = mockStatic(MCUtil.class, CALLS_REAL_METHODS)) {
            mainThread.when(MCUtil::isMainThread).thenReturn(true);
            assertTrue(started.await(5, TimeUnit.SECONDS));
            first.send(packet);
            second.send(packet);
            assertNull(firstChannel.readOutbound());
            assertNull(secondChannel.readOutbound());
            first.clearPacketQueue();
            release.countDown();
            pending.bytes();
            final Method drain = Connection.class.getDeclaredMethod("processQueue");
            drain.setAccessible(true);
            assertEquals(true, drain.invoke(first));
            assertEquals(true, drain.invoke(second));
            firstChannel.runPendingTasks();
            secondChannel.runPendingTasks();
            assertNull(firstChannel.readOutbound());
            assertSame(packet, secondChannel.readOutbound());
            assertNull(secondChannel.readOutbound());
            verify(snapshot, times(1)).encode();
        } finally {
            release.countDown();
            first.clearPacketQueue();
            second.clearPacketQueue();
            firstChannel.finishAndReleaseAll();
            secondChannel.finishAndReleaseAll();
        }
    }

    private static Connection connection() throws Exception {
        final Connection connection = new Connection(PacketFlow.SERVERBOUND);
        final PacketListener listener = mock(PacketListener.class);
        when(listener.protocol()).thenReturn(ConnectionProtocol.PLAY);
        when(listener.createDisconnectionInfo(any(), any())).thenAnswer(invocation -> new DisconnectionDetails(invocation.getArgument(0)));
        setField(Connection.class, connection, "packetListener", listener);
        setField(Connection.class, connection, "sendLoginDisconnect", false);
        connection.isPending = false;
        return connection;
    }

    private static ClientboundLevelChunkWithLightPacket packet(final DeferredChunkSections pending) throws Exception {
        // Decode a real minimal packet, then replace only the detached job. This exercises production readiness,
        // packet codec and Connection error handling without requiring a live world or changing production hooks.
        final RegistryFriendlyByteBuf input = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryHelper.registryAccess());
        try {
            input.writeInt(0);
            input.writeInt(0);
            input.writeVarInt(0); // Heightmaps.
            input.writeVarInt(0); // Section byte array.
            input.writeVarInt(0); // Block entities.
            for (int index = 0; index < 6; index++) input.writeVarInt(0); // Four light masks, two light-update lists.
            final var packet = ClientboundLevelChunkWithLightPacket.STREAM_CODEC.decode(input);
            setField(ClientboundLevelChunkPacketData.class, packet.getChunkData(), "leavesX$sections", pending);
            packet.setReady(true);
            return packet;
        } finally {
            input.release();
        }
    }

    private static void setField(final Class<?> owner, final Object target, final String name, final Object value) throws Exception {
        final Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
