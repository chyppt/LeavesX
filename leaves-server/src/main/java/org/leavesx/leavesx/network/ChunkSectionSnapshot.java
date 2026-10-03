package org.leavesx.leavesx.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * 用于数据包编码的私有区段调色板。捕获必须在区块所有者线程执行。
 * 世界、区块、实体和光照引擎引用都不会跨越工作线程边界。
 *
 * <p>这里只覆盖区段字节；反 X 光、光照和方块实体数据包仍需在所有者线程捕获，并按顺序发布后才能异步发送。</p>
 */
public final class ChunkSectionSnapshot {
    private final LevelChunkSection[] sections;

    private ChunkSectionSnapshot(final LevelChunkSection[] sections) {
        this.sections = sections;
    }

    public static ChunkSectionSnapshot capture(final LevelChunkSection[] source) {
        final LevelChunkSection[] sections = new LevelChunkSection[source.length];
        for (int index = 0; index < source.length; index++) {
            // copy() 同时分离调色板、生物群系存储和区段计数器。
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
