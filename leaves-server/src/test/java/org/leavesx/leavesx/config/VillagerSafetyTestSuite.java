package org.leavesx.leavesx.config;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

/** 覆盖村民感知间隔、Brain 保护和非活跃 AI；实际刷铁仍需服务器场景验证。 */
@Suite
@SelectClasses(VillagerCompatibilityContextTest.class)
@ConfigurationParameter(key = "TestSuite", value = "Normal")
public class VillagerSafetyTestSuite {
}
