package org.leavesx.leavesx.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Privately owned section palettes for packet encoding. Capture must run on the chunk's owner thread.
 * No level, chunk, entity or light-engine reference crosses the worker boundary.
 *
 * <p>This covers section bytes only. Anti-Xray, light and block-entity packets still require their
 * own owner-thread capture and publication ordering before a complete packet can be sent asynchronously.</p>
 */
public final class ChunkSectionSnapshot {
    private final LevelChunkSection[] sections;

    private ChunkSectionSnapshot(final LevelChunkSection[] sections) {
        this.sections = sections;
    }

    public static ChunkSectionSnapshot capture(final LevelChunkSection[] source) {
        final LevelChunkSection[] sections = new LevelChunkSection[source.length];
        for (int index = 0; index < source.length; index++) {
            // copy() detaches palette storage as well as biome storage and section counters.
            sections[index] = source[index].copy();
        }
        return new ChunkSectionSnapshot(sections);
    }

    /** 仅在编码成功后返回完整字节数组。 */
    public byte[] encode() {
        int size = 0;
        for (final LevelChunkSection section : this.sections) {
            size = Math.addExact(size, section.getSerializedSize());
        }
        final byte[] result = new byte[size];
        final ByteBuf bytes = Unpooled.wrappedBuffer(result);
        try {
            bytes.writerIndex(0);
            final FriendlyByteBuf output = new FriendlyByteBuf(bytes);
            for (int index = 0; index < this.sections.length; index++) {
                // 快照编码不读取实时反 X 光状态。
                this.sections[index].write(output, null, index);
            }
            if (output.writerIndex() != size) {
                throw new IllegalStateException("Chunk section snapshot encoded an unexpected number of bytes");
            }
            return result;
        } finally {
            bytes.release();
        }
    }
}
