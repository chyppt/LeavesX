package org.leavesx.leavesx.random;

import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.BitRandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;

/** JDK Xoroshiro adapter, following Leaf's FasterRandomSource by HaHaWTH. Each instance has one owner. */
public final class FasterRandomSource implements BitRandomSource {
    private static final RandomGeneratorFactory<RandomGenerator> FACTORY = RandomGeneratorFactory.of("Xoroshiro128PlusPlus");
    private RandomGenerator generator;

    public FasterRandomSource(final long seed) { this.setSeed(seed); }

    @Override public void setSeed(final long seed) { this.generator = FACTORY.create(seed); }
    @Override public int next(final int bits) { return (int)(this.generator.nextLong() >>> (64 - bits)); }
    @Override public int nextInt() { return this.generator.nextInt(); }
    @Override public int nextInt(final int bound) { return this.generator.nextInt(bound); }
    @Override public int nextInt(final int origin, final int bound) { return this.generator.nextInt(origin, bound); }
    @Override public long nextLong() { return this.generator.nextLong(); }
    @Override public boolean nextBoolean() { return this.generator.nextBoolean(); }
    @Override public float nextFloat() { return this.generator.nextFloat(); }
    @Override public double nextDouble() { return this.generator.nextDouble(); }
    @Override public double nextGaussian() { return this.generator.nextGaussian(); }
    @Override public RandomSource fork() { return new FasterRandomSource(this.nextLong()); }
    @Override public PositionalRandomFactory forkPositional() { return new Factory(this.nextLong()); }

    private record Factory(long seed) implements PositionalRandomFactory {
        @Override public RandomSource at(final int x, final int y, final int z) {
            return new FasterRandomSource(Mth.getSeed(x, y, z) ^ this.seed);
        }
        @Override public RandomSource fromHashOf(final String name) {
            return new FasterRandomSource((long)name.hashCode() ^ this.seed);
        }
        @Override public RandomSource fromSeed(final long value) { return new FasterRandomSource(value); }
        @Override public void parityConfigString(final StringBuilder result) {
            result.append("LeavesX Xoroshiro{").append(this.seed).append('}');
        }
    }
}
