package org.leavesx.leavesx.diagnostics;

import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;
import org.leavesx.leavesx.config.LeavesXConfig;
import org.leavesx.leavesx.runtime.LeavesXRegionTicking;
import org.mockito.Mockito;
import static org.junit.jupiter.api.Assertions.*;

@Normal
class WorldTickMetricsTest {
    @Test
    void displayFollowsConfiguredModeEvenDuringCompatibilityFallback() {
        try (var regionTicking = Mockito.mockStatic(LeavesXRegionTicking.class)) {
            regionTicking.when(LeavesXRegionTicking::mode).thenReturn(LeavesXConfig.RegionTickingSettings.Mode.OFF);
            assertFalse(LeavesXWorldTickMetrics.displayWorldMetrics());
            regionTicking.when(LeavesXRegionTicking::mode).thenReturn(LeavesXConfig.RegionTickingSettings.Mode.WORLDS);
            regionTicking.when(LeavesXRegionTicking::effectiveMode).thenReturn(LeavesXConfig.RegionTickingSettings.Mode.OFF);
            assertTrue(LeavesXWorldTickMetrics.displayWorldMetrics());
        }
    }

    @Test
    void unloadingWorldDropsItsSamples() throws ReflectiveOperationException {
        final var world = Mockito.mock(net.minecraft.server.level.ServerLevel.class);
        final var uuid = net.minecraft.server.level.ServerLevel.class.getField("uuid");
        uuid.setAccessible(true);
        uuid.set(world, java.util.UUID.randomUUID());
        try {
            LeavesXWorldTickMetrics.record(world, System.nanoTime());
            LeavesXWorldTickMetrics.record(world, System.nanoTime());
            assertTrue(LeavesXWorldTickMetrics.snapshot(world).available());
        } finally {
            LeavesXWorldTickMetrics.remove(world);
        }
        assertFalse(LeavesXWorldTickMetrics.snapshot(world).available());
    }

    @Test
    void waitsForAnActualTickInterval() {
        final var samples = new LeavesXWorldTickMetrics.Samples();
        assertFalse(samples.snapshot(0).available());
        samples.record(0, 10_000_000);
        assertFalse(samples.snapshot(10_000_000).available());
        samples.record(50_000_000, 60_000_000);
        assertEquals(20.0, samples.snapshot(60_000_000).tps());
        assertEquals(10.0, samples.snapshot(60_000_000).mspt());
    }

    @Test
    void worldCostDoesNotDetermineWorldTickRate() {
        final var fast = new LeavesXWorldTickMetrics.Samples();
        final var slow = new LeavesXWorldTickMetrics.Samples();
        for (int tick = 0; tick < 10; tick++) {
            final long start = tick * 100_000_000L;
            fast.record(start, start + 5_000_000);
            slow.record(start, start + 80_000_000);
        }
        assertEquals(10.0, fast.snapshot(1_000_000_000).tps());
        assertEquals(10.0, slow.snapshot(1_000_000_000).tps());
        assertEquals(5.0, fast.snapshot(1_000_000_000).mspt());
        assertEquals(80.0, slow.snapshot(1_000_000_000).mspt());
    }

    @Test
    void ringBufferRetainsOnlyRecentHundredSamples() {
        final var samples = new LeavesXWorldTickMetrics.Samples();
        for (int tick = 0; tick < 200; tick++) {
            final long start = tick * 50_000_000L;
            samples.record(start, start + (tick < 100 ? 40_000_000 : 2_000_000));
        }
        final var snapshot = samples.snapshot(10_000_000_000L);
        assertEquals(100, snapshot.samples());
        assertEquals(2.0, snapshot.mspt());
        assertEquals(20.0, snapshot.tps());
    }

    @Test
    void staleSamplesDoNotReportAnOldHealthyWorld() {
        final var samples = new LeavesXWorldTickMetrics.Samples();
        samples.record(0, 10_000_000);
        samples.record(50_000_000, 60_000_000);
        assertFalse(samples.snapshot(6_000_000_000L).available());
        samples.record(6_000_000_000L, 6_010_000_000L);
        assertFalse(samples.snapshot(6_010_000_000L).available());
        samples.record(6_050_000_000L, 6_060_000_000L);
        assertEquals(20.0, samples.snapshot(6_060_000_000L).tps());
    }

    @Test
    void acceleratedTicksAreCappedAtTwenty() {
        final var samples = new LeavesXWorldTickMetrics.Samples();
        samples.record(0, 1_000_000);
        samples.record(2_000_000, 3_000_000);
        assertEquals(20.0, samples.snapshot(3_000_000).tps());
    }
}
