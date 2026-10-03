package org.leavesx.leavesx.support;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeTags;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

/** 物品组件需要真实注册表值，遵循 Paper 测试环境的约定。 */
@Suite(failIfNoTests = false)
@SuiteDisplayName("LeavesX item and registry integration tests")
@IncludeTags("VanillaFeature")
@SelectPackages("org.leavesx")
@ConfigurationParameter(key = "TestSuite", value = "VanillaFeature")
public class LeavesXVanillaFeatureTestSuite {
}
