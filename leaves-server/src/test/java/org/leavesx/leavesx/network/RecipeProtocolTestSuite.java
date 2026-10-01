package org.leavesx.leavesx.network;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

@Suite
@SelectClasses({ReiPacketTransformerTest.class, ReiTransferSafetyTest.class, ReiReloadSafetyTest.class,
    JeiProtocolTest.class, ProtocolDispatchTest.class, ReiRequestValidationTest.class,
    org.leavesx.leavesx.config.JeiConfigurationTest.class})
@ConfigurationParameter(key = "TestSuite", value = "Normal")
public class RecipeProtocolTestSuite {}
