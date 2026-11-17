package org.leavesx.leavesx.runtime;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

/** 本轮并行开关、单任务派发及快照提交的专项回归。 */
@Suite(failIfNoTests = true)
@SelectClasses({LowLoadComputeTest.class, EntityTrackerLifetimeTest.class,
    SnapshotBatchTest.class, SnapshotBatchReuseTest.class,
    PlayerCompressionTest.class,
    net.minecraft.world.level.levelgen.ParallelNoiseSamplerTest.class,
    org.leavesx.leavesx.worldgen.ParallelStructurePlacementTest.class,
    org.leavesx.leavesx.config.Parallel132ConfigurationTest.class,
    LeavesXComputeExecutorTest.class,
    ComputeFailureProtectionTest.class,
    ComputeSchedulingTest.class,
    net.minecraft.world.level.MobSpawnCountAccumulatorTest.class,
    net.minecraft.world.level.MobSpawnBatchLocalityTest.class,
    net.minecraft.world.level.MobSpawnMergeEquivalenceTest.class,
    io.papermc.paper.entity.activation.ActivationRangeSnapshotTest.class,
    io.papermc.paper.entity.activation.ActivationPipelineTest.class,
    org.leavesx.leavesx.performance.StableDistanceSorterTest.class,
    org.leavesx.leavesx.performance.SorterTypeCacheTest.class,
    org.leavesx.leavesx.performance.ParallelMobCollisionTest.class,
    org.leavesx.leavesx.performance.ItemIdentityFastPathTest.class,
    org.leavesx.leavesx.performance.TargetDistanceSnapshotTest.class,
    org.leavesx.leavesx.performance.TrackerPlayerIndexCacheTest.class,
    org.leavesx.leavesx.performance.AnimalTargetSnapshotTest.class,
    org.leavesx.leavesx.performance.AnimalGoalEquivalenceTest.class,
    org.leavesx.leavesx.performance.PoiCountingTest.class,
    org.leavesx.leavesx.performance.PoiVisitedMemoryTest.class,
    org.leavesx.leavesx.performance.NearbyEntityBufferTest.class,
    org.leavesx.leavesx.config.TrackingRowKernelTest.class,
    io.papermc.paper.entity.activation.ActivationBoundsPrefilterTest.class})
@ConfigurationParameter(key = "TestSuite", value = "Normal")
public class Parallel132TestSuite {}
