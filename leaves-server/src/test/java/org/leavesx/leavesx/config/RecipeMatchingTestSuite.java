package org.leavesx.leavesx.config;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

@Suite(failIfNoTests = false)
@SelectClasses(RecipeMatchingRegressionTest.class)
@ConfigurationParameter(key = "TestSuite", value = "VanillaFeature")
public class RecipeMatchingTestSuite {
}
