package org.leavesx.leavesx.config;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

@Suite
@SelectClasses(TripwireHookDuplicationTest.class)
@ConfigurationParameter(key = "TestSuite", value = "Normal")
public class TripwireHookTestSuite {
}
