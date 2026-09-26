package org.leavesx.leavesx.support;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeTags;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

/** Item components need real registry values, following Paper's test-environment convention. */
@Suite(failIfNoTests = false)
@SuiteDisplayName("LeavesX item and registry integration tests")
@IncludeTags("VanillaFeature")
@SelectPackages("org.leavesx")
@ConfigurationParameter(key = "TestSuite", value = "VanillaFeature")
public class LeavesXVanillaFeatureTestSuite {
}
