package org.leavesmc.leaves.config;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeTags;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

/** Runs Leaves-owned configuration compatibility tests through the server's explicit suite filter. */
@Suite(failIfNoTests = false)
@SuiteDisplayName("Leaves configuration compatibility tests")
@IncludeTags("Normal")
@SelectClasses(GlobalConfigManagerTest.class)
@ConfigurationParameter(key = "TestSuite", value = "Normal")
public final class LeavesConfigCompatibilityTestSuite {
}
