package org.leavesx.probe.neko;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

@Suite
@SelectClasses({NekoProbeTest.class, WirePacketSummaryTest.class, MovementWireProbeTest.class, NekoFlagProbeTest.class})
@ConfigurationParameter(key = "TestSuite", value = "Normal")
public class NekoProbeTestSuite {}
