package org.leavesx.leavesx.runtime;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

/** 下载池并发边界、配置迁移与数据包语义的专项回归。 */
@Suite
@SelectClasses(names = {"org.leavesx.leavesx.runtime.LeavesXDownloadExecutorTest",
    "org.leavesx.leavesx.config.LeafCompatibilityConfigurationTest",
    "org.leavesx.leavesx.config.BlockDestructionPacketTest",
    "org.leavesx.leavesx.random.RandomTickCompatibilityTest"})
@ConfigurationParameter(key = "TestSuite", value = "Normal")
public class LeafCompatibilityTestSuite {}
