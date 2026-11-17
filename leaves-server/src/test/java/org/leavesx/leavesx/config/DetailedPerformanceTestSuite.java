package org.leavesx.leavesx.config;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

/** 验证完整配置、迁移保值及启动检查与进服提醒的边界。 */
@Suite
@SelectClasses(names = {"org.leavesx.leavesx.config.LeavesXConfigLoaderTest",
    "org.leavesx.leavesx.config.LeafConfigurationLayoutTest",
    "org.leavesx.leavesx.config.LeafIntegrationTest",
    "org.leavesx.leavesx.config.LeafCompatibilityConfigurationTest",
    "org.leavesx.leavesx.config.LeavesXConfigInspectionTest",
    "org.leavesx.leavesx.config.RestartRequiredChangesTest",
    "org.leavesx.leavesx.update.LeavesXUpdateServiceTest",
    "org.leavesx.leavesx.update.StartupUpdateTest",
    "org.leavesx.leavesx.update.UpdateNotifierTest"})
@ConfigurationParameter(key = "TestSuite", value = "Normal")
public class DetailedPerformanceTestSuite {}
