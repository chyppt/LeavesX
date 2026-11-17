package org.leavesx.leavesx.worldgen;

import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.jspecify.annotations.Nullable;
import org.leavesx.leavesx.config.LeavesXRuntime;
import org.leavesx.leavesx.runtime.LeavesXComputeExecutor;

/** 生成线程顺序采集密度图输入，共享计算池仅处理独立的噪声采样。 */
public final class ParallelNoiseSampler {
    private static final int MINIMUM_SAMPLES = 128;
    private static final int SAMPLES_PER_TASK = 64;
    private static final int MAXIMUM_SAMPLES = 8_192;
    private static final ThreadLocal<Buffer> BUFFERS = ThreadLocal.withInitial(Buffer::new);

    private ParallelNoiseSampler() {}

    public static boolean tryFill(
        final double[] output, final DensityFunction.ContextProvider provider,
        final DensityFunction.NoiseHolder noise, final double xzScale, final double yScale,
        final @Nullable DensityFunction shiftX, final @Nullable DensityFunction shiftY,
        final @Nullable DensityFunction shiftZ
    ) {
        // 自定义提供器可能把前一次输出作为下一次输入；只接收已核对的原版单元遍历。
        if (provider.getClass() != NoiseChunk.class || noise.noise() == null
            || noise.noise().getClass() != net.minecraft.world.level.levelgen.synth.NormalNoise.class
            || output.length > MAXIMUM_SAMPLES || !LeavesXRuntime.parallelNoiseSampling()
            || !LeavesXComputeExecutor.shouldAttempt(LeavesXComputeExecutor.Workload.WORLDGEN_NOISE,
                output.length, MINIMUM_SAMPLES, SAMPLES_PER_TASK)) return false;

        final Buffer cached = BUFFERS.get();
        final Buffer buffer = cached.inUse ? new Buffer() : cached;
        buffer.inUse = true;
        try {
            buffer.ensureCapacity(output.length);
            final double[] coordinates = buffer.coordinates;
            // 保留 fillAllDirectly 的上下文更新次数、XYZ 顺序及结束时的 arrayIndex。
            // 偏移密度函数可能使用插值缓存，必须在生成任务的原线程按 X/Y/Z 顺序求值。
            provider.fillAllDirectly(output, new CoordinateCapture(coordinates, xzScale, yScale, shiftX, shiftY, shiftZ));
            final int completed = LeavesXComputeExecutor.invokeRangesWithCount(
                LeavesXComputeExecutor.Workload.WORLDGEN_NOISE, output.length, MINIMUM_SAMPLES, SAMPLES_PER_TASK,
                (partition, start, end) -> sample(noise, coordinates, output, start, end));
            // 执行器保证失败分片已结束；仅重算纯噪声，不能重放带状态的密度图采集。
            if (completed == 0) sample(noise, coordinates, output, 0, output.length);
            return true;
        } finally {
            buffer.inUse = false;
            if (buffer == cached && buffer.coordinates.length > 4_096 * 3) BUFFERS.remove();
        }
    }

    private static void sample(final DensityFunction.NoiseHolder noise, final double[] coordinates,
                               final double[] output, final int start, final int end) {
        for (int index = start; index < end; index++) {
            final int offset = index * 3;
            output[index] = noise.getValue(coordinates[offset], coordinates[offset + 1], coordinates[offset + 2]);
        }
    }

    /** 仅在所有者线程使用；任何偏移函数和可变上下文都不会进入工作线程。 */
    private static final class CoordinateCapture implements DensityFunction.SimpleFunction {
        private final double[] coordinates;
        private final double xzScale, yScale;
        private final @Nullable DensityFunction shiftX, shiftY, shiftZ;
        private int offset;

        private CoordinateCapture(final double[] coordinates, final double xzScale, final double yScale,
                                  final @Nullable DensityFunction shiftX, final @Nullable DensityFunction shiftY,
                                  final @Nullable DensityFunction shiftZ) {
            this.coordinates = coordinates;
            this.xzScale = xzScale;
            this.yScale = yScale;
            this.shiftX = shiftX;
            this.shiftY = shiftY;
            this.shiftZ = shiftZ;
        }

        @Override
        public double compute(final DensityFunction.FunctionContext context) {
            // 不把乘法与加法融合；与 Noise / ShiftedNoise 的浮点运算顺序保持一致。
            final double x = context.blockX() * this.xzScale;
            this.coordinates[this.offset++] = this.shiftX == null ? x : x + this.shiftX.compute(context);
            final double y = context.blockY() * this.yScale;
            this.coordinates[this.offset++] = this.shiftY == null ? y : y + this.shiftY.compute(context);
            final double z = context.blockZ() * this.xzScale;
            this.coordinates[this.offset++] = this.shiftZ == null ? z : z + this.shiftZ.compute(context);
            return 0.0;
        }

        @Override public double minValue() { return 0.0; }
        @Override public double maxValue() { return 0.0; }
        @Override public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            throw new UnsupportedOperationException("Coordinate capture is not a serializable density function");
        }
    }

    private static final class Buffer {
        private double[] coordinates = new double[0];
        private boolean inUse;

        private void ensureCapacity(final int samples) {
            if (this.coordinates.length < samples * 3) this.coordinates = new double[samples * 3];
        }
    }
}
