package org.leavesx.leavesx.worldgen;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;

/** HMAC counter stream. Mutable state belongs to one generation task, never to a shared executor or world. */
final class SecureFeatureRandom extends LegacyRandomSource {
    private final byte[] key;
    private final Mac mac;
    private final byte[] message = new byte[16];
    private byte[] block = new byte[0];
    private int offset;
    private long counter;

    SecureFeatureRandom(final byte[] key) {
        super(0L);
        this.key = key.clone();
        this.mac = SecureFeatureSeed.mac(this.key);
        this.setSeed(0L);
    }

    @Override
    public void setSeed(final long seed) {
        super.setSeed(seed); // Reset the inherited Gaussian cache too.
        if (this.mac == null) return; // The superclass constructor calls this method.
        ByteBuffer.wrap(this.message).putLong(seed).putLong(0L);
        this.counter = 0;
        this.offset = this.block.length;
    }

    @Override
    public int next(final int bits) {
        if (bits < 1 || bits > 32) throw new IllegalArgumentException("bits must be in [1, 32]");
        if (this.offset == this.block.length) {
            ByteBuffer.wrap(this.message).putLong(8, this.counter++);
            this.block = this.mac.doFinal(this.message);
            this.offset = 0;
        }
        final int value = (this.block[this.offset] & 255) << 24 | (this.block[this.offset + 1] & 255) << 16
            | (this.block[this.offset + 2] & 255) << 8 | (this.block[this.offset + 3] & 255);
        this.offset += 4;
        return value >>> (32 - bits);
    }

    @Override
    public RandomSource fork() {
        return new SecureFeatureRandom(SecureFeatureSeed.hash(this.key, ByteBuffer.allocate(8).putLong(this.nextLong()).array()));
    }

    @Override
    public PositionalRandomFactory forkPositional() {
        final byte[] forkKey = SecureFeatureSeed.hash(this.key, ByteBuffer.allocate(8).putLong(this.nextLong()).array());
        return new PositionalRandomFactory() {
            @Override
            public RandomSource at(final int x, final int y, final int z) {
                return new SecureFeatureRandom(SecureFeatureSeed.hash(forkKey, ByteBuffer.allocate(12).putInt(x).putInt(y).putInt(z).array()));
            }

            @Override
            public RandomSource fromHashOf(final String name) {
                return new SecureFeatureRandom(SecureFeatureSeed.hash(forkKey, name.getBytes(StandardCharsets.UTF_8)));
            }

            @Override
            public RandomSource fromSeed(final long seed) {
                return new SecureFeatureRandom(SecureFeatureSeed.hash(forkKey, ByteBuffer.allocate(8).putLong(seed).array()));
            }

            @Override
            public void parityConfigString(final StringBuilder result) {
                result.append("LeavesXSecureFeatureRandom[v1]");
            }
        };
    }
}
