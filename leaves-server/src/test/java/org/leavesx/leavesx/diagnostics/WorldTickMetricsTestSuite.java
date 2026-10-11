package org.leavesx.leavesx.diagnostics;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

@Suite
@SelectClasses(WorldTickMetricsTest.class)
@ConfigurationParameter(key = "TestSuite", value = "Normal")
public class WorldTickMetricsTestSuite {
}
