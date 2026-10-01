package org.leavesmc.leaves.protocol.rei;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/** REI split frames. All method arguments and callback buffers are borrowed, never transferred. */
public class PacketTransformer {
    private static final byte START = 0;
    private static final byte PART = 1;
    private static final byte END = 2;
    private static final byte ONLY = 3;
    private static final int MAX_PARTS = 256;
    private static final int MAX_INBOUND_BYTES = 2 * 1024 * 1024;

    // Detached bytes avoid retaining Netty buffers when transfers are abandoned, replaced or expired.
    private final Cache<UUID, PartData> cache = CacheBuilder.newBuilder()
        .maximumSize(32).expireAfterWrite(30, TimeUnit.SECONDS).build();

    public static DiscardedPayload wrapRei(Identifier location, FriendlyByteBuf buf) {
        final FriendlyByteBuf wrapped = new FriendlyByteBuf(Unpooled.buffer());
        try {
            wrapped.writeByteArray(ByteBufUtil.getBytes(buf));
            return new DiscardedPayload(location, ByteBufUtil.getBytes(wrapped));
        } finally {
            wrapped.release();
        }
    }

    public void clear(UUID player) {
        cache.invalidate(player);
    }

    public void clear() {
        cache.invalidateAll();
    }

    public synchronized void inbound(Identifier id, RegistryFriendlyByteBuf buf, ServerPlayer player,
                                     BiConsumer<Identifier, RegistryFriendlyByteBuf> consumer) {
        final UUID key = player.getUUID();
        final byte[] complete;
        try {
            final int length = buf.readVarInt();
            if (length != buf.readableBytes() || length < 1 || length > MAX_INBOUND_BYTES) {
                cache.invalidate(key);
                return;
            }
            final byte state = buf.readByte();
            if (state == ONLY) {
                cache.invalidate(key);
                complete = ByteBufUtil.getBytes(buf);
            } else if (state == START) {
                cache.invalidate(key);
                final int count = buf.readInt();
                if (count < 2 || count > MAX_PARTS) return;
                final PartData data = new PartData(id, count);
                data.append(buf);
                cache.put(key, data);
                return;
            } else if (state == PART || state == END) {
                final PartData data = cache.getIfPresent(key);
                if (data == null || !data.id.equals(id)
                    || buf.readableBytes() > MAX_INBOUND_BYTES - data.bytes.size()
                    || (state == END ? data.received != data.expected - 1 : data.received >= data.expected - 1)) {
                    cache.invalidate(key);
                    return;
                }
                data.append(buf);
                if (state == PART) return;
                // Remove before invoking application code, including if the recipe decoder throws.
                cache.invalidate(key);
                complete = data.bytes.toByteArray();
            } else {
                cache.invalidate(key);
                return;
            }
        } catch (RuntimeException malformed) {
            // Covers truncated input and vanilla's untyped oversized-VarInt exception.
            // Application callbacks remain outside this decoding boundary.
            cache.invalidate(key);
            return;
        }
        final RegistryFriendlyByteBuf assembled = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(complete), buf.registryAccess());
        try {
            consumer.accept(id, assembled);
        } finally {
            assembled.release();
        }
    }

    public void outbound(Identifier id, RegistryFriendlyByteBuf buf, BiConsumer<Identifier, RegistryFriendlyByteBuf> consumer) {
        final int maxSize = 1048576 - 1 - 20 - id.toString().getBytes(StandardCharsets.UTF_8).length;
        final int size = buf.readableBytes();
        final int partSize = size <= maxSize ? maxSize : maxSize - Integer.BYTES;
        final int parts = Math.max(1, (int) ((size + (long) partSize - 1) / partSize));
        int offset = buf.readerIndex();
        for (int index = 0; index < parts; index++) {
            final int length = Math.min(buf.writerIndex() - offset, partSize);
            final RegistryFriendlyByteBuf packet = new RegistryFriendlyByteBuf(Unpooled.buffer(length + 5), buf.registryAccess());
            try {
                packet.writeByte(parts == 1 ? ONLY : index == 0 ? START : index == parts - 1 ? END : PART);
                if (parts > 1 && index == 0) packet.writeInt(parts);
                packet.writeBytes(buf, offset, length);
                consumer.accept(id, packet);
            } finally {
                packet.release();
            }
            offset += length;
        }
    }

    private static final class PartData {
        private final Identifier id;
        private final int expected;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private int received;

        private PartData(Identifier id, int expected) {
            this.id = id;
            this.expected = expected;
        }

        private void append(RegistryFriendlyByteBuf buf) {
            bytes.writeBytes(ByteBufUtil.getBytes(buf));
            received++;
        }
    }
}
