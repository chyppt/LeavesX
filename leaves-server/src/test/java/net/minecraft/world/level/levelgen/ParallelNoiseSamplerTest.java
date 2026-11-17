package net.minecraft.world.level.levelgen;

import static org.junit.jupiter.api.Assertions.*;
import static org.leavesx.leavesx.config.ConfigurationTestSupport.publish;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.leavesx.leavesx.config.LeavesXConfig;
import org.leavesx.leavesx.runtime.LeavesXComputeExecutor;

/** 对照原始遍历逐位比较噪声，并验证采集顺序和提供器结束状态。 */
@Normal
public class ParallelNoiseSamplerTest {
    @AfterEach
    void restore() {
        LeavesXComputeExecutor.shutdown();
        publish(LeavesXConfig.safeDefaults());
    }

    @Test
    void plainNoiseIsBitExactAndLeavesProviderInItsOriginalState() throws Exception {
        publish(LeavesXConfig.safeDefaults());
        LeavesXComputeExecutor.configure(2, 64, true, false);
        for (final long seed : new long[]{0L, -1L, Long.MAX_VALUE}) {
            final var function = new DensityFunctions.Noise(noise(seed), 0.03125, 0.0625);
            compare(function);
            compare(function, 4, 8);
        }
        assertTrue(LeavesXComputeExecutor.metrics().workerItems() > 0);
    }

    @Test
    void shiftedInputsStayOnCallerAndKeepTheirOriginalOrder() throws Exception {
        publish(LeavesXConfig.safeDefaults());
        LeavesXComputeExecutor.configure(2, 64, true, false);
        final Thread caller = Thread.currentThread();
        final List<Integer> visits = new ArrayList<>();
        final DensityFunction[] shifts = new DensityFunction[3];
        for (int axis = 0; axis < shifts.length; axis++) {
            final int selectedAxis = axis;
            shifts[axis] = mock(DensityFunction.class);
            when(shifts[axis].compute(any())).thenAnswer(invocation -> {
                assertSame(caller, Thread.currentThread());
                visits.add(selectedAxis);
                final DensityFunction.FunctionContext context = invocation.getArgument(0);
                return (context.blockX() + context.blockY() - context.blockZ()) * (selectedAxis + 1) * 0.125;
            });
        }
        compare(new DensityFunctions.ShiftedNoise(shifts[0], shifts[1], shifts[2], 0.25, 0.125, noise(42L)));
        assertEquals(512 * 3 * 2, visits.size());
        for (int index = 0; index < visits.size(); index++) assertEquals(index % 3, visits.get(index).intValue());
    }

    @Test
    void poolShutdownAfterCaptureRecomputesNoiseWithoutRepeatingTheShiftGraph() throws Exception {
        publish(LeavesXConfig.safeDefaults());
        LeavesXComputeExecutor.configure(2, 64, true, false);
        final DensityFunction shift = mock(DensityFunction.class);
        final int[] calls = {0};
        when(shift.compute(any())).thenAnswer(invocation -> {
            if (++calls[0] == 513) LeavesXComputeExecutor.shutdown();
            return 0.125;
        });
        compare(new DensityFunctions.ShiftedNoise(shift, DensityFunctions.zero(), DensityFunctions.zero(),
            0.25, 0.125, noise(-42L)));
        assertEquals(512 * 2, calls[0]);
    }

    @Test
    void unavailablePoolUsesTheOriginalProviderLoop() throws Exception {
        publish(LeavesXConfig.safeDefaults());
        // 先制造已完成的后台工作；shutdown 保留累计诊断，测试必须比较本次增量。
        LeavesXComputeExecutor.configure(2, 64, true, false);
        assertTrue(LeavesXComputeExecutor.invokeRanges(512, 128, (partition, start, end) -> {}));
        LeavesXComputeExecutor.shutdown();
        final long previousWorkerItems = LeavesXComputeExecutor.metrics().workerItems();
        assertTrue(previousWorkerItems > 0);
        compare(new DensityFunctions.Noise(noise(17L), 1.0, 0.5));
        assertEquals(previousWorkerItems, LeavesXComputeExecutor.metrics().workerItems());
    }

    @Test
    void customProvidersRemainSynchronous() {
        publish(LeavesXConfig.safeDefaults());
        LeavesXComputeExecutor.configure(2, 64, true, false);
        final Thread caller = Thread.currentThread();
        final DensityFunction.ContextProvider provider = new DensityFunction.ContextProvider() {
            @Override public DensityFunction.FunctionContext forIndex(final int index) {
                assertSame(caller, Thread.currentThread());
                return new DensityFunction.SinglePointContext(index - 128, -64, index);
            }
            @Override public void fillAllDirectly(final double[] output, final DensityFunction function) {
                for (int index = 0; index < output.length; index++) output[index] = function.compute(this.forIndex(index));
            }
        };
        final var function = new DensityFunctions.Noise(noise(99L), 0.25, 0.5);
        final double[] actual = new double[512];
        function.fillArray(actual, provider);
        for (int index = 0; index < actual.length; index++) {
            assertEquals(Double.doubleToRawLongBits(function.compute(provider.forIndex(index))),
                Double.doubleToRawLongBits(actual[index]));
        }
        assertEquals(0, LeavesXComputeExecutor.metrics().workerItems());
    }

    private static void compare(final DensityFunction function) throws Exception {
        compare(function, 8, 8);
    }

    private static void compare(final DensityFunction function, final int width, final int height) throws Exception {
        final NoiseChunk expectedProvider = provider(width, height);
        final NoiseChunk actualProvider = provider(width, height);
        final double[] expected = new double[width * width * height];
        final double[] actual = new double[expected.length];
        // 绕过新 fillArray 入口，保留原版逐点计算作为独立对照。
        expectedProvider.fillAllDirectly(expected, function);
        function.fillArray(actual, actualProvider);
        for (int index = 0; index < actual.length; index++) {
            assertEquals(Double.doubleToRawLongBits(expected[index]), Double.doubleToRawLongBits(actual[index]), "sample " + index);
        }
        for (final String name : List.of("arrayIndex", "inCellX", "inCellY", "inCellZ", "interpolationCounter")) {
            final var field = NoiseChunk.class.getDeclaredField(name);
            field.setAccessible(true);
            assertEquals(field.get(expectedProvider), field.get(actualProvider), name);
        }
    }

    private static NoiseChunk provider(final int width, final int height) throws Exception {
        final NoiseChunk provider = mock(NoiseChunk.class, CALLS_REAL_METHODS);
        final String[] fields = {"cellWidth", "cellHeight", "cellStartBlockX", "cellStartBlockY", "cellStartBlockZ"};
        final int[] values = {width, height, -30_000_000, -64, 29_999_992};
        for (int index = 0; index < fields.length; index++) {
            final var field = NoiseChunk.class.getDeclaredField(fields[index]);
            field.setAccessible(true);
            field.setInt(provider, values[index]);
        }
        return provider;
    }

    private static DensityFunction.NoiseHolder noise(final long seed) {
        final var parameters = new NormalNoise.NoiseParameters(-7, 1.0, 0.5, 1.0);
        return new DensityFunction.NoiseHolder(Holder.direct(parameters), NormalNoise.create(new LegacyRandomSource(seed), parameters));
    }
}
