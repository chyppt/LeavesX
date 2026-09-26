package org.leavesx.leavesx.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import net.minecraft.world.level.levelgen.WorldgenRandom;

/** Independent feature seed, inspired by Leaf/Matter. Immutable and explicitly owned by each world. */
public final class SecureFeatureSeed {
    private static final int BYTES = 128;
    private static volatile boolean enableForNewWorlds;

    public static void configureNewWorlds(final boolean enabled) {
        enableForNewWorlds = enabled;
    }

    public static java.util.Optional<SecureFeatureSeed> forNewWorld() {
        return enableForNewWorlds ? java.util.Optional.of(create()) : java.util.Optional.empty();
    }
    public static final Codec<SecureFeatureSeed> CODEC = Codec.STRING.comapFlatMap(text -> {
        try {
            return DataResult.success(parse(text));
        } catch (IllegalArgumentException failure) {
            return DataResult.error(() -> "Invalid LeavesX secure seed; expected 256 hexadecimal characters");
        }
    }, SecureFeatureSeed::encode);

    private final byte[] key;

    private SecureFeatureSeed(final byte[] key) {
        this.key = key.clone();
    }

    public static SecureFeatureSeed create() {
        final byte[] bytes = new byte[BYTES];
        new SecureRandom().nextBytes(bytes);
        return new SecureFeatureSeed(bytes);
    }

    public static SecureFeatureSeed parse(final String text) {
        if (text.length() != BYTES * 2) throw new IllegalArgumentException("Incorrect secure seed length");
        return new SecureFeatureSeed(HexFormat.of().parseHex(text));
    }

    public String encode() {
        return HexFormat.of().formatHex(this.key);
    }

    public Context forDimension(final String dimension) {
        return new Context(hash(this.key, ("LeavesX/feature/v1/" + dimension).getBytes(StandardCharsets.UTF_8)));
    }

    static Mac mac(final byte[] key) {
        try {
            final Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac;
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("JVM lacks HmacSHA256", failure);
        }
    }

    static byte[] hash(final byte[] key, final byte[] message) {
        return mac(key).doFinal(message);
    }

    /** Never logs or exposes the secret; each request receives a separate mutable random stream. */
    public static final class Context {
        private final byte[] key;

        private Context(final byte[] key) {
            this.key = key;
        }

        public WorldgenRandom random(final String purpose, final int x, final int z, final long salt) {
            final byte[] purposeBytes = purpose.getBytes(StandardCharsets.UTF_8);
            final byte[] message = ByteBuffer.allocate(purposeBytes.length + 16)
                .put(purposeBytes).putInt(x).putInt(z).putLong(salt).array();
            return new WorldgenRandom(new SecureFeatureRandom(hash(this.key, message)));
        }

        public boolean isSlimeChunk(final int x, final int z, final long salt) {
            return this.random("slime", x, z, salt).nextInt(10) == 0;
        }
    }
}
