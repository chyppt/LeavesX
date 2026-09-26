package org.leavesx.leavesx.runtime;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

@Suite(failIfNoTests = false)
@SelectClasses(WorldTickSafetyRegressionTest.class)
@ConfigurationParameter(key = "TestSuite", value = "Normal")
public class WorldTickSafetyTestSuite {
}
