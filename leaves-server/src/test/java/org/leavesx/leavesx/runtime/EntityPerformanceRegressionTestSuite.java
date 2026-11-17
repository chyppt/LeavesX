package org.leavesx.leavesx.runtime;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

/** 实体计算改动的机制与协议回归入口，包含实际追踪提交和故障回退。 */
@Suite(failIfNoTests = true)
@SelectClasses(names = {
    "org.leavesx.leavesx.runtime.Parallel132TestSuite",
    "org.leavesx.leavesx.config.VillagerSafetyTestSuite",
    "org.leavesx.leavesx.config.TechnicalMechanicsCompatibilityTest",
    "org.leavesx.leavesx.runtime.WorldTickSafetyTestSuite",
    "org.leavesx.leavesx.runtime.SleepStatusRegressionTest",
    "org.leavesx.leavesx.network.ChatSpectatorRegressionTestSuite",
    "org.leavesx.leavesx.network.NetworkBufferOptimizationTestSuite",
    "org.leavesx.leavesx.diagnostics.ExperienceDiagnosticsTestSuite",
    "org.leavesx.leavesx.network.RecipeProtocolTestSuite",
    "org.leavesx.leavesx.network.ChunkPacketOrderingTest",
    "org.leavesx.leavesx.network.BlockAcknowledgementOrderingTest",
    "org.leavesx.leavesx.network.ChunkEncodingPipelineTest",
    "org.leavesx.leavesx.runtime.LeafCompatibilityTestSuite",
    "org.leavesx.leavesx.diagnostics.WorldTickMetricsTestSuite"
})
@ConfigurationParameter(key = "TestSuite", value = "Normal")
public class EntityPerformanceRegressionTestSuite {}
