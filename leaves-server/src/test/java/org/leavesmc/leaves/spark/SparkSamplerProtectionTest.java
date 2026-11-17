package org.leavesmc.leaves.spark;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SparkSamplerProtectionTest {
    @Test
    void startUsesJavaWithoutMutatingCallerArguments() {
        String[] input = {"profiler", "start", "--timeout", "30"};
        assertArrayEquals(new String[]{"profiler", "start", "--timeout", "30", "--force-java-sampler"},
            SparkSamplerProtection.javaSamplerArguments(input));
        assertEquals(4, input.length);
    }

    @Test
    void samplerAliasIsProtectedAndFlagIsNotDuplicated() {
        assertEquals("--force-java-sampler", SparkSamplerProtection.javaSamplerArguments(new String[]{"sampler", "start"})[2]);
        String[] input = {"profiler", "start", "--force-java-sampler"};
        assertSame(input, SparkSamplerProtection.javaSamplerArguments(input));
    }

    @Test
    void readOnlyAndStopCommandsRemainUnchanged() {
        for (String action : new String[]{"stop", "cancel", "info", "open"}) {
            String[] input = {"profiler", action};
            assertSame(input, SparkSamplerProtection.javaSamplerArguments(input));
        }
        String[] input = {"healthreport"};
        assertSame(input, SparkSamplerProtection.javaSamplerArguments(input));
    }

    @Test
    void allocationFlagsAreRecognised() {
        assertTrue(SparkSamplerProtection.hasAllocationFlag(new String[]{"profiler", "start", "--alloc"}));
        assertTrue(SparkSamplerProtection.hasAllocationFlag(new String[]{"profiler", "start", "--alloc=true"}));
        assertFalse(SparkSamplerProtection.hasAllocationFlag(new String[]{"profiler", "start", "--timeout", "30"}));
    }

    @Test
    void legacyAndImplicitStartsAreProtected() {
        assertTrue(SparkSamplerProtection.startsProfiler(new String[]{"profiler", "--start"}));
        assertTrue(SparkSamplerProtection.startsProfiler(new String[]{"profiler", "--timeout", "30"}));
        assertEquals("--force-java-sampler", SparkSamplerProtection.javaSamplerArguments(new String[]{"profiler", "--start"})[2]);
        assertFalse(SparkSamplerProtection.startsProfiler(new String[]{"profiler", "--stop"}));
    }
}
