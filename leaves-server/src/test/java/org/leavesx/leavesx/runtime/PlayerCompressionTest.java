package org.leavesx.leavesx.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.leavesx.leavesx.config.ConfigurationTestSupport.publish;

import com.mojang.datafixers.DataFixer;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PlayerDataStorage;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.leavesx.leavesx.config.LeavesXConfig;

@Normal
class PlayerCompressionTest {
    @TempDir Path directory;

    @AfterEach void cleanup() {
        LeavesXAsyncRuntime.shutdown();
        publish(LeavesXConfig.safeDefaults());
    }

    private PlayerDataStorage storage() {
        final var access = mock(LevelStorageSource.LevelStorageAccess.class);
        when(access.getLevelPath(LevelResource.PLAYER_DATA_DIR)).thenReturn(this.directory);
        return new PlayerDataStorage(access, mock(DataFixer.class));
    }

    @Test void nestedTagsAndArraysAreDetachedBeforeTheWorkerReadsThem() throws Exception {
        publish(LeavesXConfig.safeDefaults());
        LeavesXAsyncRuntime.configure(LeavesXConfig.AsyncSettings.safeDefaults());
        final var storage = this.storage();
        final UUID id = UUID.randomUUID();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        LeavesXAsyncRuntime.submitKeyedOrRun(LeavesXAsyncRuntime.Workload.PLAYER_DATA_SAVE, id, () -> {
            entered.countDown();
            try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("blocked lane timeout"); }
            catch (InterruptedException failure) { throw new AssertionError(failure); }
        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            final CompoundTag nested = new CompoundTag();
            nested.putString("name", "before");
            final byte[] payload = {1, 2, 3};
            final CompoundTag data = new CompoundTag();
            data.put("nested", nested);
            data.putByteArray("payload", payload);
            storage.save("probe", id, id.toString(), data);
            nested.putString("name", "after");
            payload[0] = 99;
            data.putInt("late", 7);
        } finally { release.countDown(); }
        LeavesXAsyncRuntime.awaitKey(LeavesXAsyncRuntime.Workload.PLAYER_DATA_SAVE, id);
        final CompoundTag saved = NbtIo.readCompressed(this.directory.resolve(id + ".dat"), NbtAccounter.unlimitedHeap());
        assertEquals("before", saved.getCompoundOrEmpty("nested").getStringOr("name", ""));
        assertArrayEquals(new byte[]{1, 2, 3}, saved.getByteArray("payload").orElseThrow());
        assertFalse(saved.contains("late"));
    }

    @Test void largeAndSmallSavesKeepTheirUuidOrderAndPreviousBackup() throws Exception {
        publish(LeavesXConfig.safeDefaults());
        LeavesXAsyncRuntime.configure(LeavesXConfig.AsyncSettings.safeDefaults());
        final var storage = this.storage();
        final UUID id = UUID.randomUUID();
        for (int revision = 1; revision <= 3; revision++) {
            final CompoundTag data = new CompoundTag();
            data.putInt("revision", revision);
            final byte[] payload = new byte[revision == 1 ? 1_048_576 : 16];
            new java.util.Random(revision).nextBytes(payload);
            data.putByteArray("payload", payload);
            storage.save("probe", id, id.toString(), data);
        }
        LeavesXAsyncRuntime.awaitKey(LeavesXAsyncRuntime.Workload.PLAYER_DATA_SAVE, id);
        assertEquals(3, NbtIo.readCompressed(this.directory.resolve(id + ".dat"), NbtAccounter.unlimitedHeap()).getIntOr("revision", 0));
        assertEquals(2, NbtIo.readCompressed(this.directory.resolve(id + ".dat_old"), NbtAccounter.unlimitedHeap()).getIntOr("revision", 0));
    }

    @Test void disabledExecutorStillWritesACompleteSaveSynchronously() throws Exception {
        LeavesXAsyncRuntime.shutdown();
        final UUID id = UUID.randomUUID();
        final CompoundTag data = new CompoundTag();
        data.putInt("revision", 9);
        this.storage().save("probe", id, id.toString(), data);
        assertEquals(9, NbtIo.readCompressed(this.directory.resolve(id + ".dat"), NbtAccounter.unlimitedHeap()).getIntOr("revision", 0));
    }
}
