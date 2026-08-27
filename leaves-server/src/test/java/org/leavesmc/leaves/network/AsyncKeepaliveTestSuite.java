package org.leavesmc.leaves.network;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeTags;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

/** Runs Leaves async keepalive compatibility tests through the server's explicit suite filter. */
@Suite(failIfNoTests = false)
@SuiteDisplayName("Leaves async keepalive compatibility tests")
@IncludeTags("Normal")
@SelectClasses(AsyncKeepaliveManagerTest.class)
@ConfigurationParameter(key = "TestSuite", value = "Normal")
public final class AsyncKeepaliveTestSuite {
}
