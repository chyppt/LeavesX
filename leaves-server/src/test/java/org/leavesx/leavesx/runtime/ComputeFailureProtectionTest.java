package org.leavesx.leavesx.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.leavesx.leavesx.config.LeavesXConfig.ComputeFailureSettings;

/** 故障保护不丢结果、不影响其他类别，重载也不重建正在使用的计算池。 */
@Normal
public class ComputeFailureProtectionTest {
    private static final LeavesXComputeExecutor.Workload WORKLOAD = LeavesXComputeExecutor.Workload.AI_TARGET_SELECT;

    @BeforeEach
    void startPool() {
        LeavesXComputeExecutor.configure(2, 16, true);
    }

    @AfterEach
    void stopPool() {
        LeavesXComputeExecutor.shutdown();
    }

    @Test
    void configuredThresholdAndCooldownOnlyPauseTheFailedCategory() {
        LeavesXComputeExecutor.configureFailureProtection(new ComputeFailureSettings(true, 2, 60));
        failInvocation();
        assertTrue(admitted());
        failInvocation();
        assertFalse(admitted());
        final var metrics = workloadMetrics();
        assertEquals(2L, metrics.failedInvocations());
        assertTrue(metrics.circuitRemainingMillis() > 0L);
        assertTrue(metrics.circuitRemainingMillis() <= 60_000L);
        assertTrue(LeavesXComputeExecutor.shouldAttempt(LeavesXComputeExecutor.Workload.ENTITY_TRACKING, 256, 1, 16));
    }

    @Test
    void disabledPauseStillRequestsCompleteRecomputationForEveryFailure() {
        LeavesXComputeExecutor.configureFailureProtection(new ComputeFailureSettings(false, 1, 30));
        for (int attempt = 0; attempt < 4; attempt++) failInvocation();
        assertTrue(admitted());
        assertEquals(4L, LeavesXComputeExecutor.metrics().fullRecomputationRequests());
        assertEquals(0L, workloadMetrics().circuitRemainingMillis());
    }

    @Test
    void successfulInvocationBreaksTheConsecutiveFailureSequence() {
        LeavesXComputeExecutor.configureFailureProtection(new ComputeFailureSettings(true, 2, 30));
        failInvocation();
        assertTrue(LeavesXComputeExecutor.invokeRangesWithCount(WORKLOAD, 256, 1, 16, (part, start, end) -> {}) > 0);
        failInvocation();
        assertTrue(admitted());
        failInvocation();
        assertFalse(admitted());
    }

    @Test
    void ordinaryPolicyReloadKeepsCountersAndExistingPause() {
        LeavesXComputeExecutor.configureFailureProtection(new ComputeFailureSettings(true, 1, 30));
        failInvocation();
        LeavesXComputeExecutor.configureFailureProtection(new ComputeFailureSettings(true, 5, 60));
        assertFalse(admitted());
        assertEquals(1L, workloadMetrics().failedInvocations());
        assertTrue(workloadMetrics().circuitRemainingMillis() <= 30_000L);
        // 关闭暂停策略不会关闭单次回退；再次启用时保留尚未到期的保护窗口。
        LeavesXComputeExecutor.configureFailureProtection(new ComputeFailureSettings(false, 5, 60));
        assertTrue(admitted());
        assertEquals(0L, workloadMetrics().circuitRemainingMillis());
        LeavesXComputeExecutor.configureFailureProtection(new ComputeFailureSettings(true, 5, 60));
        assertFalse(admitted());
    }

    @Test
    void expiredPauseAllowsRetryWithoutSleepingOrResettingFailureMetrics() throws Exception {
        LeavesXComputeExecutor.configureFailureProtection(new ComputeFailureSettings(true, 1, 30));
        failInvocation();
        final var countersField = LeavesXComputeExecutor.class.getDeclaredField("WORKLOAD_COUNTERS");
        countersField.setAccessible(true);
        final Object counters = ((Object[]) countersField.get(null))[WORKLOAD.ordinal()];
        final var deadlineField = counters.getClass().getDeclaredField("circuitOpenUntil");
        deadlineField.setAccessible(true);
        ((AtomicLong) deadlineField.get(counters)).set(System.nanoTime() - 1L);
        assertEquals(0L, workloadMetrics().circuitRemainingMillis());
        assertTrue(admitted());
        assertTrue(LeavesXComputeExecutor.invokeRangesWithCount(WORKLOAD, 256, 1, 16, (part, start, end) -> {}) > 0);
        assertEquals(1L, workloadMetrics().failedInvocations());
    }

    private static void failInvocation() {
        assertEquals(0, LeavesXComputeExecutor.invokeRangesWithCount(WORKLOAD, 256, 1, 16,
            (part, start, end) -> { if (part == 0) throw new IllegalStateException("injected protection failure"); }));
    }

    private static boolean admitted() {
        return LeavesXComputeExecutor.shouldAttempt(WORKLOAD, 256, 1, 16);
    }

    private static LeavesXComputeExecutor.WorkloadMetrics workloadMetrics() {
        return LeavesXComputeExecutor.metrics().workloads().stream()
            .filter(metrics -> metrics.workload() == WORKLOAD).findFirst().orElseThrow();
    }
}
