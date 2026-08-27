package org.leavesmc.leaves.bot;

import com.mojang.logging.LogUtils;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.util.TagUtil;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class BotDataStorage {

    private static final Logger LOGGER = LogUtils.getLogger();
    private final File botDir;
    private final File botListFile;

    private final CompoundTag savedBotList;

    public BotDataStorage(LevelStorageSource.@NotNull LevelStorageAccess session, String dataDir, String listFileName) {
        this.botDir = session.getLevelPath(new LevelResource(dataDir)).toFile();
        this.botListFile = session.getLevelPath(new LevelResource(listFileName)).toFile();
        this.botDir.mkdirs();

        this.savedBotList = new CompoundTag();
        if (this.botListFile.exists() && this.botListFile.isFile()) {
            try {
                Optional.of(NbtIo.readCompressed(this.botListFile.toPath(), NbtAccounter.unlimitedHeap())).ifPresent(tag -> {
                    for (Map.Entry<String, Tag> entry : tag.entrySet()) {
                        savedBotList.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
                    }
                });
            } catch (Exception exception) {
                BotDataStorage.LOGGER.warn("Failed to load player data list");
            }
        }
    }

    public void save(Player player) {
        try {
            CompoundTag nbt = TagUtil.saveEntityWithoutId(player);
            File file = new File(this.botDir, player.getStringUUID() + ".dat");

            if (file.exists() && file.isFile()) {
                if (!file.delete()) {
                    throw new IOException("Failed to delete file: " + file);
                }
            }
            if (!file.createNewFile()) {
                throw new IOException("Failed to create nbt file: " + file);
            }
            NbtIo.writeCompressed(nbt, file.toPath());
        } catch (Exception exception) {
            BotDataStorage.LOGGER.warn("Failed to save fakeplayer data for {}", player.getScoreboardName(), exception);
            return;
        }

        if (player instanceof ServerBot bot) {
            CompoundTag nbt = new CompoundTag();
            nbt.putString("name", bot.createState.fullName());
            nbt.store("uuid", UUIDUtil.CODEC, bot.getUUID());
            nbt.putBoolean("resume", bot.resume);
            this.savedBotList.put(bot.createState.fullName().toLowerCase(Locale.ROOT), nbt);
            this.saveBotList();
        }
    }

    public Optional<ValueInput> load(@NotNull ServerBot bot, ProblemReporter reporter) {
        return this.load(bot.nameAndId().name(), bot.nameAndId().id().toString()).map(nbt -> {
            ValueInput valueInput = TagValueInput.create(reporter, bot.registryAccess(), nbt);
            bot.load(valueInput);
            return valueInput;
        });
    }

    /**
     * Consumes a data file after the corresponding bot has been placed successfully.
     *
     * <p>Loading is intentionally two-phase. A world-load callback can run before every world is visible to Bukkit,
     * and plugins may cancel the load event. Deleting the file before those checks loses a perfectly valid resident
     * bot. Callers must invoke this method only after {@code placeNewBot} has completed.</p>
     *
     * @param name saved bot name
     * @param uuid saved bot UUID
     */
    public void consumeLoadedData(@NotNull String name, @NotNull UUID uuid) {
        File file = new File(this.botDir, uuid + ".dat");
        if (file.exists() && file.isFile() && !file.delete()) {
            // Keep the list entry when the file cannot be removed. The next periodic save can repair it, and the
            // operator still has the original data available instead of silently losing it.
            LOGGER.warn("Failed to consume fakeplayer data for {}, keeping the resident record", name);
            return;
        }
        this.removeSavedData(name);
    }

    public void removeSavedData(String name) {
        this.savedBotList.remove(name.toLowerCase(Locale.ROOT));
        this.saveBotList();
    }

    /**
     * Removes stale list registrations in one write while leaving their entity data files available for recovery.
     *
     * @param names saved bot names or lowercase list keys
     * @return number of registrations removed
     */
    public int removeSavedDataEntries(Collection<String> names) {
        int removed = 0;
        for (String name : names) {
            final String key = name.toLowerCase(Locale.ROOT);
            if (this.savedBotList.contains(key)) {
                this.savedBotList.remove(key);
                removed++;
            }
        }
        if (removed != 0) {
            this.saveBotList();
        }
        return removed;
    }

    private Optional<CompoundTag> load(String name, String uuid) {
        File file = new File(this.botDir, uuid + ".dat");
        if (!file.exists() || !file.isFile()) {
            LOGGER.warn("Failed to load bot {}, the file {} DOES NOT EXIST!", name, file);
            return Optional.empty();
        }
        try {
            // Do not mutate storage here. The caller may still discover a missing world, a cancelled event, or a
            // placement failure after reading the entity. Consumption happens explicitly after successful placement.
            return Optional.of(NbtIo.readCompressed(file.toPath(), NbtAccounter.unlimitedHeap()));
        } catch (Exception exception) {
            BotDataStorage.LOGGER.warn("Failed to load fakeplayer data for {}", name);
        }
        return Optional.empty();
    }

    /**
     * Returns whether an existing data file is definitely unreadable.
     *
     * <p>Missing files are deliberately not classified as corrupt: they can be restored from a backup or recreated by
     * a later save. Likewise, missing world metadata is a recoverability problem, not proof that the record is broken.</p>
     */
    public boolean isDataFileCorrupt(@NotNull String name) {
        final UUID uuid = this.findUUID(name).orElseGet(() -> BotUtil.getBotUUID(name));
        final File file = new File(this.botDir, uuid + ".dat");
        if (!file.exists() || !file.isFile()) {
            return false;
        }
        try {
            NbtIo.readCompressed(file.toPath(), NbtAccounter.unlimitedHeap());
            return false;
        } catch (Exception exception) {
            return true;
        }
    }

    public Optional<CompoundTag> read(String uuid) {
        File file = new File(this.botDir, uuid + ".dat");
        if (file.exists() && file.isFile()) {
            try {
                return Optional.of(NbtIo.readCompressed(file.toPath(), NbtAccounter.unlimitedHeap()));
            } catch (Exception exception) {
                BotDataStorage.LOGGER.warn("Failed to read fakeplayer data for {}", uuid);
            }
        }
        return Optional.empty();
    }

    private void saveBotList() {
        try {
            if (this.botListFile.exists() && this.botListFile.isFile()) {
                if (!this.botListFile.delete()) {
                    throw new IOException("Failed to delete file: " + this.botListFile);
                }
            }
            if (!this.botListFile.createNewFile()) {
                throw new IOException("Failed to create nbt file: " + this.botListFile);
            }
            NbtIo.writeCompressed(this.savedBotList, this.botListFile.toPath());
        } catch (Exception exception) {
            BotDataStorage.LOGGER.warn("Failed to save player data list");
        }
    }

    public CompoundTag getSavedBotList() {
        return savedBotList;
    }

    public UUID getUUIDFromLower(String lowerName) {
        return savedBotList.getCompoundOrEmpty(lowerName).read("uuid", UUIDUtil.CODEC).orElseThrow();
    }

    /** Returns the UUID stored in the list entry without deriving it again from the current configured name. */
    public Optional<UUID> findUUID(String name) {
        return savedBotList.getCompoundOrEmpty(name.toLowerCase(Locale.ROOT)).read("uuid", UUIDUtil.CODEC);
    }

    public String getNameFromLower(String lowerName) {
        return savedBotList.getCompoundOrEmpty(lowerName).getString("name").orElseThrow();
    }
}
