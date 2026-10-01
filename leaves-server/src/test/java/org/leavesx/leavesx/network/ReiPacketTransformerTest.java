package org.leavesx.leavesx.network;

import io.netty.buffer.Unpooled;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.support.RegistryHelper;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;
import org.leavesmc.leaves.protocol.rei.PacketTransformer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Normal
class ReiPacketTransformerTest {
    private static final Identifier CHANNEL = Identifier.parse("roughlyenoughitems:move_items_new");

    @Test
    void orphanFragmentDoesNotReleaseBorrowedBuffer() {
        final var transformer = new PacketTransformer();
        final var packet = frame(1, 0);
        try {
            transformer.inbound(CHANNEL, packet, player(), (id, data) -> fail("Orphan fragment delivered"));
            assertEquals(1, packet.refCnt());
        } finally {
            if (packet.refCnt() > 0) packet.release();
        }
    }

    @Test
    void replacingStartDoesNotRetainAbandonedNetworkBuffer() {
        final var transformer = new PacketTransformer();
        final var player = player();
        final var first = frame(0, 2);
        final var second = frame(0, 2);
        try {
            transformer.inbound(CHANNEL, first, player, (id, data) -> fail());
            transformer.inbound(CHANNEL, second, player, (id, data) -> fail());
            assertEquals(1, first.refCnt(), "Fragment storage must not retain Netty input buffers");
            assertEquals(1, second.refCnt());
        } finally {
            while (first.refCnt() > 0) first.release();
            while (second.refCnt() > 0) second.release();
        }
    }

    @Test
    void invalidOuterLengthIsNotDelivered() {
        final var packet = buffer();
        packet.writeVarInt(100).writeByte(3).writeByte(42);
        try {
            new PacketTransformer().inbound(CHANNEL, packet, player(), (id, data) -> fail("Invalid length delivered"));
        } finally {
            packet.release();
        }
    }

    @Test
    void oversizedLengthVarIntDoesNotReachConsumerOrReleaseInput() {
        final var packet = buffer();
        packet.writeBytes(new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80,
            (byte) 0x80, (byte) 0x80, 0x01});
        try {
            assertDoesNotThrow(() -> new PacketTransformer().inbound(CHANNEL, packet, player(),
                (id, data) -> fail("Malformed length delivered")));
            assertEquals(1, packet.refCnt());
        } finally {
            packet.release();
        }
    }

    @Test
    void consumerFailureClearsCompletedFragments() {
        final var transformer = new PacketTransformer();
        final var player = player();
        final var start = frame(0, 2);
        final var end = frame(2, 0);
        final var replay = frame(2, 0);
        try {
            transformer.inbound(CHANNEL, start, player, (id, data) -> fail());
            assertThrows(IllegalArgumentException.class, () -> transformer.inbound(CHANNEL, end, player,
                (id, data) -> { throw new IllegalArgumentException("Injected consumer failure"); }));
            transformer.inbound(CHANNEL, replay, player, (id, data) -> fail("Completed transfer replayed"));
            assertEquals(1, end.refCnt());
        } finally {
            for (var packet : new RegistryFriendlyByteBuf[] {start, end, replay}) {
                while (packet.refCnt() > 0) packet.release();
            }
        }
    }

    @Test
    void outboundRoundTripPreservesBytesAndInputOwnership() {
        for (int size : new int[] {16, 1_100_000}) {
            final var transformer = new PacketTransformer();
            final var player = player();
            final var source = buffer();
            final byte[] expected = new byte[size];
            new java.util.Random(17).nextBytes(expected);
            source.writeBytes(expected);
            final var deliveries = new AtomicInteger();
            try {
                transformer.outbound(CHANNEL, source, (id, split) -> {
                    final var wrapped = PacketTransformer.wrapRei(id, split);
                    final var inbound = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(wrapped.data()), RegistryHelper.registryAccess());
                    try {
                        transformer.inbound(id, inbound, player, (wholeId, whole) -> {
                            byte[] actual = new byte[whole.readableBytes()];
                            whole.readBytes(actual);
                            assertArrayEquals(expected, actual);
                            deliveries.incrementAndGet();
                        });
                    } finally {
                        inbound.release();
                    }
                });
                assertEquals(1, deliveries.get());
                assertEquals(1, source.refCnt());
                assertEquals(0, source.readerIndex());
            } finally {
                while (source.refCnt() > 0) source.release();
            }
        }
    }

    private static ServerPlayer player() {
        final var player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(UUID.randomUUID());
        return player;
    }

    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryHelper.registryAccess());
    }

    private static RegistryFriendlyByteBuf frame(int type, int parts) {
        final var payload = buffer();
        payload.writeByte(type);
        if (type == 0) payload.writeInt(parts);
        payload.writeByte(42);
        final byte[] bytes = io.netty.buffer.ByteBufUtil.getBytes(payload);
        payload.release();
        final var packet = buffer();
        packet.writeByteArray(bytes);
        return packet;
    }
}
