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

    @Test void disablingTheConfigDoesNotLetLoadOvertakeAnAlreadyQueuedSave() throws Exception {
        publish(LeavesXConfig.safeDefaults());
        LeavesXAsyncRuntime.configure(LeavesXConfig.AsyncSettings.safeDefaults());
        final var storage = this.storage();
        final UUID id = UUID.randomUUID();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch loading = new CountDownLatch(1);
        LeavesXAsyncRuntime.submitKeyedOrRun(LeavesXAsyncRuntime.Workload.PLAYER_DATA_SAVE, id, () -> {
            entered.countDown();
            try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("blocked lane timeout"); }
            catch (InterruptedException failure) { throw new AssertionError(failure); }
        });
        try (var reader = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                final CompoundTag data = new CompoundTag();
                data.putInt("DataVersion", ca.spottedleaf.dataconverter.minecraft.util.Version.getCurrentVersion());
                data.putInt("revision", 42);
                storage.save("probe", id, id.toString(), data);
                final var config = org.spongepowered.configurate.BasicConfigurationNode.root();
                config.node("async", "playerdata-save", "enabled").set(false);
                publish(config);
                final var loaded = reader.submit(() -> {
                    loading.countDown();
                    return storage.load(new net.minecraft.server.players.NameAndId(id, "probe"));
                });
                assertTrue(loading.await(5, TimeUnit.SECONDS));
                assertThrows(java.util.concurrent.TimeoutException.class, () -> loaded.get(100, TimeUnit.MILLISECONDS));
                release.countDown();
                assertEquals(42, loaded.get(5, TimeUnit.SECONDS).orElseThrow().getIntOr("revision", 0));
            } finally {
                // 先释放保存队列再关闭 reader；断言失败时也不能让 Executor.close 等待锁环。
                release.countDown();
            }
        }
    }

    @Test void shutdownFlushesTheLastPlayerSaveAndItsBackupBeforeReturning() throws Exception {
        publish(LeavesXConfig.safeDefaults());
        LeavesXAsyncRuntime.configure(LeavesXConfig.AsyncSettings.safeDefaults());
        final var storage = this.storage();
        final UUID id = UUID.randomUUID();
        // The server saves/removes players before shutting down the runtime. Exercise the
        // same order without a separate awaitKey barrier that could hide a shutdown bug.
        for (int revision = 1; revision <= 32; revision++) {
            final CompoundTag data = new CompoundTag();
            data.putInt("revision", revision);
            storage.save("probe", id, id.toString(), data);
        }
        LeavesXAsyncRuntime.shutdown();
        assertFalse(LeavesXAsyncRuntime.enabled(LeavesXAsyncRuntime.Workload.PLAYER_DATA_SAVE));
        assertEquals(32, NbtIo.readCompressed(this.directory.resolve(id + ".dat"), NbtAccounter.unlimitedHeap()).getIntOr("revision", 0));
        assertEquals(31, NbtIo.readCompressed(this.directory.resolve(id + ".dat_old"), NbtAccounter.unlimitedHeap()).getIntOr("revision", 0));
        try (var files = java.nio.file.Files.list(this.directory)) {
            assertEquals(2L, files.count(), "Successful shutdown must not leave temporary saves behind");
        }
    }

    @Test void onlineFallbackWaitsForTheOfflineUuidBeforeReadingAndRenaming() throws Exception {
        publish(LeavesXConfig.safeDefaults());
        LeavesXAsyncRuntime.configure(LeavesXConfig.AsyncSettings.safeDefaults());
        final var storage = this.storage();
        final String name = "probe";
        final UUID onlineId = UUID.randomUUID();
        final UUID offlineId = net.minecraft.core.UUIDUtil.createOfflinePlayerUUID(name);
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch loading = new CountDownLatch(1);
        LeavesXAsyncRuntime.submitKeyedOrRun(LeavesXAsyncRuntime.Workload.PLAYER_DATA_SAVE, offlineId, () -> {
            entered.countDown();
            try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("blocked lane timeout"); }
            catch (InterruptedException failure) { throw new AssertionError(failure); }
        });
        try (var reader = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                final CompoundTag data = new CompoundTag();
                data.putInt("DataVersion", ca.spottedleaf.dataconverter.minecraft.util.Version.getCurrentVersion());
                data.putInt("revision", 73);
                storage.save(name, offlineId, offlineId.toString(), data);
                final var loaded = reader.submit(() -> {
                    // Mockito 静态 mock 仅作用于执行读取的线程，不修改其他测试的服务器状态。
                    try (var bukkit = mockStatic(org.bukkit.Bukkit.class)) {
                        bukkit.when(org.bukkit.Bukkit::getOnlineMode).thenReturn(true);
                        loading.countDown();
                        return storage.load(new net.minecraft.server.players.NameAndId(onlineId, name));
                    }
                });
                assertTrue(loading.await(5, TimeUnit.SECONDS));
                assertThrows(java.util.concurrent.TimeoutException.class, () -> loaded.get(100, TimeUnit.MILLISECONDS));
                release.countDown();
                assertEquals(73, loaded.get(5, TimeUnit.SECONDS).orElseThrow().getIntOr("revision", 0));
                assertTrue(java.nio.file.Files.isRegularFile(this.directory.resolve(offlineId + ".dat.offline-read")));
            } finally {
                release.countDown();
            }
        }
    }

    @Test void failedReplacementIsCountedAndLaterSaveCanRecover() throws Exception {
        publish(LeavesXConfig.safeDefaults());
        LeavesXAsyncRuntime.configure(LeavesXConfig.AsyncSettings.safeDefaults());
        final var storage = this.storage();
        final UUID id = UUID.randomUUID();
        final CompoundTag first = new CompoundTag();
        first.putInt("revision", 1);
        storage.save("probe", id, id.toString(), first);
        LeavesXAsyncRuntime.awaitKey(LeavesXAsyncRuntime.Workload.PLAYER_DATA_SAVE, id);

        // A non-empty directory reliably prevents backup replacement on Windows and Unix.
        final Path backup = java.nio.file.Files.createDirectory(this.directory.resolve(id + ".dat_old"));
        final Path obstruction = java.nio.file.Files.writeString(backup.resolve("keep"), "test fixture");
        final long failures = LeavesXAsyncRuntime.metrics(LeavesXAsyncRuntime.Workload.PLAYER_DATA_SAVE).failedTasks();
        final CompoundTag second = new CompoundTag();
        second.putInt("revision", 2);
        storage.save("probe", id, id.toString(), second);
        LeavesXAsyncRuntime.awaitKey(LeavesXAsyncRuntime.Workload.PLAYER_DATA_SAVE, id);
        assertEquals(failures + 1, LeavesXAsyncRuntime.metrics(LeavesXAsyncRuntime.Workload.PLAYER_DATA_SAVE).failedTasks());
        assertEquals(1, NbtIo.readCompressed(this.directory.resolve(id + ".dat"), NbtAccounter.unlimitedHeap()).getIntOr("revision", 0));
        try (var files = java.nio.file.Files.list(this.directory)) {
            assertEquals(2L, files.count(), "Failed save must remove its temporary file");
        }

        java.nio.file.Files.delete(obstruction);
        java.nio.file.Files.delete(backup);
        storage.save("probe", id, id.toString(), second);
        LeavesXAsyncRuntime.awaitKey(LeavesXAsyncRuntime.Workload.PLAYER_DATA_SAVE, id);
        assertEquals(2, NbtIo.readCompressed(this.directory.resolve(id + ".dat"), NbtAccounter.unlimitedHeap()).getIntOr("revision", 0));
        assertEquals(1, NbtIo.readCompressed(backup, NbtAccounter.unlimitedHeap()).getIntOr("revision", 0));
    }
}
