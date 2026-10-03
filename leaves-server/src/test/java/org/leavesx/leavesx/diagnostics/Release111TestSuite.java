package org.leavesx.leavesx.diagnostics;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.ExcludeTags;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;

/** 运行 LeavesX 普通环境回归测试，包括发布诊断和配置重载。 */
@Suite(failIfNoTests = true)
@SelectPackages("org.leavesx.leavesx")
@ExcludeTags("VanillaFeature")
@ConfigurationParameter(key = "TestSuite", value = "Normal")
public class Release111TestSuite {}
