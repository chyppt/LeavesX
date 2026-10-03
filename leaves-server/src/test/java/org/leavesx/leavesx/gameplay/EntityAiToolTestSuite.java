package org.leavesx.leavesx.gameplay;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

/** 验证管理员工具的标记、权限、村民专用切换和反馈。 */
@Suite
@SelectClasses(EntityAiToolTest.class)
@ConfigurationParameter(key = "TestSuite", value = "VanillaFeature")
public class EntityAiToolTestSuite {
}
