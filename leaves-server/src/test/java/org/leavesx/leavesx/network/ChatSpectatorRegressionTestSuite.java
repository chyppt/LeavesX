package org.leavesx.leavesx.network;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

@Suite
@SelectClasses(names = {"org.leavesx.leavesx.chunk.SpectatorChunkLoadingPolicyTest", "org.leavesx.leavesx.network.ChatPacketConversionTest"})
@ConfigurationParameter(key = "TestSuite", value = "Normal")
public class ChatSpectatorRegressionTestSuite {
}
