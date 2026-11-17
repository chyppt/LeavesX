package org.leavesx.leavesx.diagnostics;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

/** 经验诊断的事件边界、生成与拾取回归。 */
@Suite(failIfNoTests = true)
@SelectClasses({ExperienceDiagnosticsTest.class, ExperienceAwardDiagnosticsTest.class, ExperienceVisibilityTest.class,
    net.minecraft.world.entity.ExperienceDeathRulesTest.class,
    org.leavesx.leavesx.runtime.ExperiencePickupRegressionTest.class})
@ConfigurationParameter(key = "TestSuite", value = "Normal")
public class ExperienceDiagnosticsTestSuite {}
