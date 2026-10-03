package org.leavesx.leavesx.performance;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

/** 使用真实方块注册表验证潜影贝附着判定。 */
@Suite
@SelectClasses(ShulkerAttachmentTest.class)
@ConfigurationParameter(key = "TestSuite", value = "VanillaFeature")
public class ShulkerSafetyTestSuite {
}
