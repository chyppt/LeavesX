package org.leavesx.leavesx.runtime;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.IncludeClassNamePatterns;
import org.junit.platform.suite.api.Suite;

/** 专项检查追踪器生命周期、计算完成和 GC 诊断准确性。 */
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
