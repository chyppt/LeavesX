package org.leavesx.leavesx.network;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.EncoderException;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.zip.Deflater;
import net.minecraft.network.CompressionEncoder;
import net.minecraft.network.Utf8String;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.leavesx.leavesx.config.ConfigurationTestSupport;
import org.leavesx.leavesx.config.LeavesXConfig;
import org.leavesx.leavesx.config.LeavesXRuntime;
import org.spongepowered.configurate.CommentedConfigurationNode;

/** 对照开关两侧的实际编码字节，不以解码成功替代协议等价检查。 */
@Normal
class NetworkBufferOptimizationTest {
    private LeavesXConfig previous;
    private LeavesXConfig original;
    private LeavesXConfig optimized;

    @BeforeEach
    void prepare() {
        this.previous = LeavesXRuntime.configuration();
        final var node = CommentedConfigurationNode.root();
        node.node("performance", "network", "buffer-optimizations").raw(false);
        ConfigurationTestSupport.publish(node);
        this.original = LeavesXRuntime.configuration();
        node.node("performance", "network", "buffer-optimizations").raw(true);
        ConfigurationTestSupport.publish(node);
        this.optimized = LeavesXRuntime.configuration();
    }

    @AfterEach
    void restore() {
        ConfigurationTestSupport.publish(this.previous);
    }

    @Test
    void stringsKeepIdenticalEncodingForAllBufferKinds() {
        for (final String value : List.of("", "LeavesX", "村民和潜影盒", "\uD83D\uDE00",
            "\uD800", "\uDC00", "a\uD800b\uDC00c", "中".repeat(20000))) {
            for (int kind = 0; kind < 3; kind++) {
                assertArrayEquals(stringBytes(value, kind, this.original), stringBytes(value, kind, this.optimized));
                assertArrayEquals(stringBytes(new StringBuilder(value), kind, this.original),
                    stringBytes(new StringBuilder(value), kind, this.optimized));
            }
        }
        final Random random = new Random(4182);
        for (int trial = 0; trial < 500; trial++) {
            final char[] text = new char[random.nextInt(256)];
            for (int index = 0; index < text.length; index++) text[index] = (char)random.nextInt(65536);
            final String value = new String(text);
            assertArrayEquals(stringBytes(value, trial % 3, this.original),
                stringBytes(value, trial % 3, this.optimized));
        }
    }

    @Test
    void oversizedStringDoesNotPartiallyWriteOutput() {
        for (final LeavesXConfig config : List.of(this.original, this.optimized)) {
            ConfigurationTestSupport.publish(config);
            final ByteBuf output = Unpooled.buffer();
            try {
                output.writeInt(0x12345678);
                assertThrows(EncoderException.class, () -> Utf8String.write(output, "太长了", 2));
                assertEquals(4, output.writerIndex());
                assertEquals(0x12345678, output.readInt());
            } finally {
                output.release();
            }
        }
    }

    @Test
    void compressionKeepsExactBytesForHeapDirectSlicedAndCompositeInputs() throws Exception {
        final Random random = new Random(26012);
        // 连续多包共用同一编码器，检查字典重置及大小包切换。
        try (final TestEncoder originalEncoder = new TestEncoder(256);
             final TestEncoder optimizedEncoder = new TestEncoder(256)) {
            for (final int size : new int[] {0, 1, 127, 255, 256, 257, 8192, 65536, 262144, 256}) {
                final byte[] payload = new byte[size];
                for (int pattern = 0; pattern < 2; pattern++) {
                    if (pattern == 1) random.nextBytes(payload);
                    for (int kind = 0; kind < 5; kind++) {
                        assertArrayEquals(compress(originalEncoder, payload, kind, this.original),
                            compress(optimizedEncoder, payload, kind, this.optimized),
                            "size=" + size + ", kind=" + kind + ", pattern=" + pattern);
                    }
                }
            }
        }
    }

    @Test
    void compressionResetsAfterOutputFailure() throws Exception {
        ConfigurationTestSupport.publish(this.optimized);
        try (final TestEncoder encoder = new TestEncoder(0)) {
            final ByteBuf input = input(new byte[96], 1);
            final ByteBuf tooSmall = Unpooled.buffer(1, 1);
            try {
                assertThrows(IndexOutOfBoundsException.class, () -> encoder.encodeDirect(input, tooSmall));
            } finally {
                input.release();
                tooSmall.release();
            }
            final byte[] payload = {4, 8, 15, 16, 23, 42};
            try (final TestEncoder originalEncoder = new TestEncoder(0)) {
                assertArrayEquals(compress(originalEncoder, payload, 0, this.original),
                    compress(encoder, payload, 1, this.optimized));
            }
        }
    }

    @Test
    void removingJavaEncoderReleasesItsNativeDeflater() throws Exception {
        final TestEncoder encoder = new TestEncoder(0);
        final var field = CompressionEncoder.class.getDeclaredField("deflater");
        field.setAccessible(true);
        final Deflater deflater = (Deflater)field.get(encoder);
        encoder.close();
        assertThrows(IllegalStateException.class, () -> deflater.deflate(new byte[32]));
        encoder.close();
    }

    @Test
    void networkBufferSettingReloadsWithoutAffectingOtherNetworkSettings() {
        ConfigurationTestSupport.publish(this.original);
        assertFalse(LeavesXRuntime.networkBufferOptimizations());
        ConfigurationTestSupport.publish(this.optimized);
        assertTrue(LeavesXRuntime.networkBufferOptimizations());
        assertEquals(this.original.optimizedVarInt(), this.optimized.optimizedVarInt());
        assertEquals(this.original.asyncSettings(), this.optimized.asyncSettings());
    }

    private static byte[] stringBytes(final CharSequence value, final int kind, final LeavesXConfig config) {
        ConfigurationTestSupport.publish(config);
        final ByteBuf output = switch (kind) {
            case 0 -> Unpooled.buffer();
            case 1 -> Unpooled.directBuffer();
            default -> Unpooled.compositeBuffer();
        };
        try {
            output.writeInt(0x12345678);
            Utf8String.write(output, value, 32767);
            return ByteBufUtil.getBytes(output);
        } finally {
            output.release();
        }
    }

    private static byte[] compress(final TestEncoder encoder, final byte[] payload, final int kind,
                                   final LeavesXConfig config) throws Exception {
        ConfigurationTestSupport.publish(config);
        final ByteBuf source = input(payload, kind);
        final ByteBuf output = Unpooled.buffer();
        try {
            encoder.encodeDirect(source, output);
            assertEquals(0, source.readableBytes());
            return ByteBufUtil.getBytes(output);
        } finally {
            output.release();
            source.release();
        }
    }

    private static ByteBuf input(final byte[] payload, final int kind) {
        if (kind == 4) {
            final var composite = Unpooled.compositeBuffer();
            composite.addComponents(true, Unpooled.wrappedBuffer(new byte[7]),
                Unpooled.wrappedBuffer(Arrays.copyOfRange(payload, 0, payload.length / 2)),
                Unpooled.wrappedBuffer(Arrays.copyOfRange(payload, payload.length / 2, payload.length)));
            return composite.skipBytes(7);
        }
        final ByteBuf buffer = kind == 1 ? Unpooled.directBuffer(payload.length + 7)
            : Unpooled.buffer(payload.length + 7);
        buffer.writeZero(7).writeBytes(payload).skipBytes(7);
        return switch (kind) {
            case 2 -> buffer.slice(3, payload.length + 4).skipBytes(4);
            case 3 -> buffer.asReadOnly();
            default -> buffer;
        };
    }

    private static final class TestEncoder extends CompressionEncoder implements AutoCloseable {
        private TestEncoder(final int threshold) {
            super(threshold);
        }

        private void encodeDirect(final ByteBuf input, final ByteBuf output) throws Exception {
            super.encode(null, input, output);
        }

        @Override
        public void close() {
            this.handlerRemoved(null);
        }
    }
}
