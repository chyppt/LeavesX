package org.leavesmc.leaves.spark;

import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

@Suite
@SelectClasses({SparkSamplerProtectionTest.class, org.leavesx.leavesx.config.SparkProtectionConfigurationTest.class})
public class SparkSamplerProtectionTestSuite {}
