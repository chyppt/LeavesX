package org.leavesx.leavesx.network;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

/** 网络缓冲区优化及完整回退的专项入口。 */
@Suite
@SelectClasses(NetworkBufferOptimizationTest.class)
@ConfigurationParameter(key = "TestSuite", value = "Normal")
public class NetworkBufferOptimizationTestSuite {}
