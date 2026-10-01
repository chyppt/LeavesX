package org.leavesx.leavesx.diagnostics;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Normal
class LeavesXHealthReportTest {
    @TempDir Path directory;

    @Test
    void unusedStartupSlotsDoNotHideLongTicksOrMutateTheSource() {
        final long[] ticks = {0, 100_000_000, 40_000_000, 50_000_000, -1};
        final long[] original = ticks.clone();
        final var summary = LeavesXHealthReport.summarize(ticks);
        assertEquals(3, summary.samples());
        assertEquals(100, summary.p95Millis());
        assertEquals(100, summary.p99Millis());
        assertEquals(100, summary.maximumMillis());
        assertEquals(1, summary.overBudget());
        assertArrayEquals(original, ticks);
        assertEquals(0, LeavesXHealthReport.summarize(new long[100]).samples());
    }

    @Test
    void percentilesUseNearestRankAndDoNotConflateP95WithP99() {
        final long[] samples = java.util.stream.LongStream.rangeClosed(1, 100).map(value -> value * 1_000_000L).toArray();
        final var summary = LeavesXHealthReport.summarize(samples);
        assertEquals(95, summary.p95Millis());
        assertEquals(99, summary.p99Millis());
        assertEquals(100, summary.maximumMillis());
        assertEquals(50, summary.overBudget());
    }

    @Test
    void consecutiveReportsAreUtf8AndDoNotOverwriteEachOther() throws Exception {
        final var lines = List.of("诊断报告", "没有玩家数据");
        final Path first = LeavesXHealthReport.write(directory, lines);
        final Path second = LeavesXHealthReport.write(directory, lines);
        assertNotEquals(first, second);
        assertEquals(lines, Files.readAllLines(first));
        assertEquals(lines, Files.readAllLines(second));
    }
}
