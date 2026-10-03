package org.leavesx.leavesx.runtime;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

@Suite
@SelectClasses({OwnedLaneBarrierTest.class, AsyncTaskBreakdownTest.class, OwnedLaneShutdownTest.class,
    OwnedLaneFairnessTest.class, PlayerCompressionTest.class})
@ConfigurationParameter(key = "TestSuite", value = "Normal")
public class OwnedLaneBarrierTestSuite {
}
