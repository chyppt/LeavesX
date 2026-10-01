package org.leavesx.leavesx.diagnostics;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.ExcludeTags;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;

/** Runs LeavesX normal-environment regression tests, including release diagnostics and configuration reload. */
@Suite(failIfNoTests = true)
@SelectPackages("org.leavesx.leavesx")
@ExcludeTags("VanillaFeature")
@ConfigurationParameter(key = "TestSuite", value = "Normal")
public class Release111TestSuite {}
