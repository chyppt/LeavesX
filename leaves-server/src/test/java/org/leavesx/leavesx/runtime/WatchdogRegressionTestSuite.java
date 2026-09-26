package org.leavesx.leavesx.runtime;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.IncludeClassNamePatterns;
import org.junit.platform.suite.api.Suite;

/** Focused checks for tracker lifetime, compute completion and GC diagnostic accuracy. */
@Suite
@SelectPackages("org.leavesx.leavesx")
@IncludeClassNamePatterns({
    ".*EntityTrackerLifetimeTest",
    ".*LeavesXGcDiagnosticsTest",
    ".*LeavesXComputeExecutorTest",
    ".*ComputeCompletionSafetyTest",
    ".*WorldTickSafetyRegressionTest",
    ".*ChunkPackingIsolationTest"
})
@ConfigurationParameter(key = "TestSuite", value = "Normal")
public class WatchdogRegressionTestSuite {
}
