package org.leavesx.leavesx.diagnostics;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.spigotmc.SpigotWorldConfig;

/** 验证真实发包与接触入口，区分“已生成”“已交给连接”和“已拾取”。 */
@Normal
public class ExperienceVisibilityTest {
    @AfterEach
    void stop() { ExperienceDiagnostics.stop(); }

    @Test
    void actualPairingIncludesOrbSpawnAndMetadataBeforeReportingSubmission() {
        final var level = ExperienceDiagnosticsTest.level(Level.NETHER);
        final ExperienceOrb orb = orb(level);
        final SynchedEntityData data = mock(SynchedEntityData.class);
        when(orb.getEntityData()).thenReturn(data);
        when(data.getNonDefaultValues()).thenReturn(List.of(new SynchedEntityData.DataValue<>(8, EntityDataSerializers.INT, 5)));
        when(orb.trackingPosition()).thenReturn(Vec3.ZERO);
        when(orb.getDeltaMovement()).thenReturn(Vec3.ZERO);
        when(orb.getPassengers()).thenReturn(List.of());
        when(orb.getAddEntityPacket(any())).thenCallRealMethod();
        final var player = mock(ServerPlayer.class);
        player.connection = mock(ServerGamePacketListenerImpl.class);
        final List<Packet<?>> sent = new ArrayList<>();
        doAnswer(call -> { sent.add(call.getArgument(0)); return null; }).when(player.connection).send(any(Packet.class));
        final ServerEntity tracker = new ServerEntity(level, orb, 20, true, mock(ServerEntity.Synchronizer.class), new java.util.HashSet<>());
        ExperienceDiagnostics.start(Level.NETHER, 0, 0, 0, 60);
        tracker.addPairing(player);
        final ClientboundBundlePacket bundle = assertInstanceOf(ClientboundBundlePacket.class, sent.getFirst());
        final List<Packet<?>> packets = new ArrayList<>();
        bundle.subPackets().forEach(packets::add);
        assertInstanceOf(ClientboundAddEntityPacket.class, packets.getFirst());
        assertTrue(packets.stream().anyMatch(packet -> packet instanceof ClientboundSetEntityDataPacket));
        assertEquals(1, ExperienceDiagnosticsTest.row(ExperienceDiagnostics.Stage.PAIRING).calls());
        verify(orb).startSeenByPlayer(player);
    }

    @Test
    void cooldownContactIsVisibleWithoutChangingPickupRules() {
        ExperienceDiagnostics.start(Level.NETHER, 0, 0, 0, 60);
        final ExperienceOrb orb = orb(ExperienceDiagnosticsTest.level(Level.NETHER));
        final ServerPlayer player = mock(ServerPlayer.class);
        player.takeXpDelay = 40;
        doCallRealMethod().when(orb).playerTouch(player);
        orb.playerTouch(player);
        assertEquals(1, ExperienceDiagnosticsTest.row(ExperienceDiagnostics.Stage.TOUCH).calls());
        assertEquals(1, ExperienceDiagnosticsTest.row(ExperienceDiagnostics.Stage.COOLDOWN).calls());
        assertEquals(40, player.takeXpDelay);
        assertEquals(3, orb.count);
        verify(player, never()).giveExperiencePoints(anyInt());
    }

    @Test
    void snapshotKeepsStockSeparateFromAwardsAndRunsAtMostOncePerSecond() throws Exception {
        final AtomicLong clock = new AtomicLong();
        ExperienceDiagnostics.start(Level.NETHER, 0, 0, 0, 10, clock::get);
        final var level = ExperienceDiagnosticsTest.level(Level.NETHER);
        final var config = mock(SpigotWorldConfig.class);
        config.expMerge = 3.0;
        final var field = Level.class.getDeclaredField("spigotConfig");
        field.setAccessible(true);
        field.set(level, config);
        when(level.players()).thenReturn(List.of());
        final ExperienceOrb orb = orb(level);
        doAnswer(call -> { ((List<ExperienceOrb>) call.getArgument(3)).add(orb); return null; })
            .when(level).getEntities(any(net.minecraft.world.level.entity.EntityTypeTest.class), any(AABB.class), any(), anyList(), eq(4097));
        ExperienceDiagnostics.sampleWorld(level);
        ExperienceDiagnostics.sampleWorld(level);
        final var snapshot = ExperienceDiagnostics.report().snapshot();
        assertEquals(1, snapshot.orbs());
        assertEquals(15, snapshot.experience());
        assertEquals(1, snapshot.noTracker());
        assertEquals(1, snapshot.noHumanViewers());
        assertEquals(3.0, snapshot.mergeRadius());
        assertTrue(ExperienceDiagnostics.report().rows().isEmpty());
        verify(level, times(1)).getEntities(any(net.minecraft.world.level.entity.EntityTypeTest.class), any(AABB.class), any(), anyList(), eq(4097));
        clock.set(TimeUnit.SECONDS.toNanos(10));
        ExperienceDiagnostics.sampleWorld(level);
        verify(level, times(1)).getEntities(any(net.minecraft.world.level.entity.EntityTypeTest.class), any(AABB.class), any(), anyList(), eq(4097));
        assertEquals(snapshot, ExperienceDiagnostics.report().snapshot());
    }

    @Test
    void disabledVisibilityDiagnosticsDoNotReadLiveEntitiesOrWorlds() {
        ExperienceDiagnostics.stop();
        final ExperienceOrb orb = mock(ExperienceOrb.class);
        final ServerPlayer player = mock(ServerPlayer.class);
        final var level = mock(net.minecraft.server.level.ServerLevel.class);
        ExperienceDiagnostics.sampleWorld(level);
        ExperienceDiagnostics.touched(orb, player);
        ExperienceDiagnostics.pairingSubmitted(orb, player, List.of());
        ExperienceDiagnostics.trackingCancelled(orb, player);
        ExperienceDiagnostics.removed(orb, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED, null);
        ExperienceDiagnostics.mergedDuringSpawn(orb, orb, 5);
        verifyNoInteractions(orb, player, level);
    }

    @Test
    void failedConnectionSendIsNotReportedAsSubmitted() {
        ExperienceDiagnostics.start(Level.NETHER, 0, 0, 0, 60);
        final var level = ExperienceDiagnosticsTest.level(Level.NETHER);
        final ExperienceOrb orb = orb(level);
        when(orb.getEntityData()).thenReturn(mock(SynchedEntityData.class));
        when(orb.trackingPosition()).thenReturn(Vec3.ZERO);
        when(orb.getDeltaMovement()).thenReturn(Vec3.ZERO);
        final ServerPlayer player = mock(ServerPlayer.class);
        player.connection = mock(ServerGamePacketListenerImpl.class);
        doThrow(new IllegalStateException("测试连接发送失败")).when(player.connection).send(any(Packet.class));
        final ServerEntity tracker = new ServerEntity(level, orb, 20, true,
            mock(ServerEntity.Synchronizer.class), new java.util.HashSet<>());
        assertThrows(IllegalStateException.class, () -> tracker.addPairing(player));
        assertTrue(ExperienceDiagnostics.report().rows().isEmpty());
        verify(orb, never()).startSeenByPlayer(player);
    }

    @Test
    void snapshotCapsTraversalAndDoesNotScanOtherWorlds() throws Exception {
        ExperienceDiagnostics.start(Level.NETHER, 0, 0, 0, 60);
        final var other = ExperienceDiagnosticsTest.level(Level.OVERWORLD);
        ExperienceDiagnostics.sampleWorld(other);
        verify(other, never()).getEntities(any(EntityType.class), any(AABB.class), any(), anyList(), anyInt());
        assertNull(ExperienceDiagnostics.report().snapshot());

        final var level = ExperienceDiagnosticsTest.level(Level.NETHER);
        final var config = mock(SpigotWorldConfig.class);
        final var field = Level.class.getDeclaredField("spigotConfig");
        field.setAccessible(true);
        field.set(level, config);
        when(level.players()).thenReturn(List.of());
        final ExperienceOrb orb = orb(level);
        doAnswer(call -> {
            ((List<ExperienceOrb>) call.getArgument(3)).addAll(java.util.Collections.nCopies(4097, orb));
            return null;
        }).when(level).getEntities(any(net.minecraft.world.level.entity.EntityTypeTest.class), any(AABB.class), any(), anyList(), eq(4097));
        ExperienceDiagnostics.sampleWorld(level);
        final var snapshot = ExperienceDiagnostics.report().snapshot();
        assertTrue(snapshot.truncated());
        assertEquals(4096, snapshot.orbs());
        assertEquals(4096L * 15, snapshot.experience());
        assertEquals(5, snapshot.samples().size());
        verify(orb, times(4096)).moonrise$getTrackedEntity();
    }

    @Test
    void actualRemovalIsRecordedOnlyOnFirstAcceptedTransition() throws Exception {
        ExperienceDiagnostics.start(Level.NETHER, 0, 0, 0, 60);
        final var level = ExperienceDiagnosticsTest.level(Level.NETHER);
        final var lookup = mock(ca.spottedleaf.moonrise.patches.chunk_system.level.entity.server.ServerEntityLookup.class);
        when(level.moonrise$getEntityLookup()).thenReturn(lookup);
        when(lookup.canRemoveEntity(any())).thenReturn(true);
        final ExperienceOrb orb = orb(level);
        final var levelField = net.minecraft.world.entity.Entity.class.getDeclaredField("level");
        levelField.setAccessible(true);
        levelField.set(orb, level);
        final var callbackField = net.minecraft.world.entity.Entity.class.getDeclaredField("levelCallback");
        callbackField.setAccessible(true);
        final var callback = mock(net.minecraft.world.level.entity.EntityInLevelCallback.class);
        callbackField.set(orb, callback);
        final var reason = net.minecraft.world.entity.Entity.RemovalReason.DISCARDED;
        doCallRealMethod().when(orb).setRemoved(reason, null);
        when(orb.isRemoved()).thenCallRealMethod();
        when(orb.getRemovalReason()).thenCallRealMethod();
        try (var events = mockStatic(org.bukkit.craftbukkit.event.CraftEventFactory.class)) {
            orb.setRemoved(reason, null);
            orb.setRemoved(reason, null);
        }
        assertTrue(orb.isRemoved());
        assertEquals(reason, orb.getRemovalReason());
        assertEquals(1, ExperienceDiagnosticsTest.row(ExperienceDiagnostics.Stage.REMOVED).calls());
        assertEquals(15, ExperienceDiagnosticsTest.row(ExperienceDiagnostics.Stage.REMOVED).input());
        verify(callback, times(2)).onRemove(reason);
    }

    private static ExperienceOrb orb(final net.minecraft.server.level.ServerLevel level) {
        final ExperienceOrb orb = mock(ExperienceOrb.class);
        when(orb.level()).thenReturn(level);
        doReturn(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getValue(net.minecraft.resources.Identifier.withDefaultNamespace("experience_orb"))).when(orb).getType();
        when(orb.getUUID()).thenReturn(UUID.randomUUID());
        when(orb.getValue()).thenReturn(5);
        orb.count = 3;
        return orb;
    }
}
