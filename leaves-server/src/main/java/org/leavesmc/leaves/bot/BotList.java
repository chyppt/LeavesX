package org.leavesmc.leaves.bot;

import com.google.common.collect.Maps;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.logging.LogUtils;
import io.papermc.paper.adventure.PaperAdventure;
import io.papermc.paper.profile.MutablePropertyMap;
import io.papermc.paper.util.MCUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.leavesmc.leaves.LeavesConfig;
import org.leavesmc.leaves.event.bot.BotCreateEvent;
import org.leavesmc.leaves.event.bot.BotJoinEvent;
import org.leavesmc.leaves.event.bot.BotLoadEvent;
import org.leavesmc.leaves.event.bot.BotRemoveEvent;
import org.leavesmc.leaves.event.bot.BotSpawnLocationEvent;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

public class BotList {

    public static BotList INSTANCE;

    private static final Logger LOGGER = LogUtils.getLogger();

    private final MinecraftServer server;

    public final List<ServerBot> bots = new CopyOnWriteArrayList<>();
    private final BotDataStorage manualSaveDataStorage;
    private final BotDataStorage resumeDataStorage;

    private final Map<UUID, ServerBot> botsByUUID = Maps.newHashMap();
    private final Map<String, ServerBot> botsByLowerName = Maps.newHashMap();
    private final Map<String, Set<String>> botsNameByWorldUuid = Maps.newHashMap();
    private final Map<String, Set<String>> legacyBotsNameByWorldUuid = Maps.newHashMap();
    /**
     * Startup-only resident bot queue. WorldLoadEvent fires before the first server tick, so restoring every bot in
     * that callback can create a single large entity and chunk-ticket burst. The queue is drained after the server is
     * ready, one bot per tick, while runtime world loads retain their original immediate behavior.
     */
    private final ArrayDeque<PendingResumeBot> pendingResumeBots = new ArrayDeque<>();
    private final Set<String> pendingResumeKeys = new HashSet<>();

    public BotList(@NotNull MinecraftServer server) {
        this.server = server;
        this.manualSaveDataStorage = new BotDataStorage(server.storageSource, "fakeplayerdata", "fakeplayer.dat");
        this.resumeDataStorage = new BotDataStorage(server.storageSource, "resume_fakeplayerdata", "resume_fakeplayer.dat");
        INSTANCE = this;
    }

    public void saveAllResumeBots(final int interval) {
        MCUtil.ensureMain("Save Bots", () -> {
            final long now = MinecraftServer.currentTick;
            for (ServerBot bot : bots) {
                if (interval == -1 || now - bot.lastSave >= interval) {
                    this.resumeDataStorage.save(bot);
                    bot.lastSave = MinecraftServer.currentTick;
                }
            }
            return null;
        });
    }

    public void saveAllResumeBots() {
        if (!LeavesConfig.modify.fakeplayer.enable || !LeavesConfig.modify.fakeplayer.canResident) {
            return;
        }
        for (ServerBot bot : this.bots) {
            this.resumeDataStorage.save(bot);
        }
    }

    public ServerBot createNewBot(@NotNull BotCreateState state) {
        BotCreateEvent event = new BotCreateEvent(state.fullName(), state.skinName(), state.location(), state.createReason(), state.creator());
        event.setCancelled(!BotUtil.isCreateLegal(state.fullName()));
        this.server.server.getPluginManager().callEvent(event);

        if (event.isCancelled()) {
            return null;
        }

        Location location = event.getCreateLocation();
        ServerLevel world = ((CraftWorld) location.getWorld()).getHandle();

        GameProfile profile = createBotProfile(BotUtil.getBotUUID(state), state.fullName(), state.skin());
        ServerBot bot = new ServerBot(this.server, world, profile);
        bot.createState = state;
        if (event.getCreator() instanceof org.bukkit.entity.Player player) {
            bot.createPlayer = player.getUniqueId();
        }

        return this.placeNewBot(bot, world, location, null);
    }

    public ServerBot loadNewManualSavedBot(String fullName) {
        return this.loadNewBot(fullName, this.manualSaveDataStorage);
    }

    public ServerBot loadNewResumeBot(String fullName) {
        return this.loadNewBot(fullName, this.resumeDataStorage);
    }

    public ServerBot loadNewBot(String inputName, BotDataStorage storage) {
        String lowerName = inputName.toLowerCase(Locale.ROOT);
        if (botsByLowerName.containsKey(lowerName)) {
            return null;
        }
        try {
            if (!storage.getSavedBotList().contains(lowerName)) {
                return null;
            }
            // Older Leaves records may omit the denormalised name or UUID fields. The list key and the original
            // name-derived UUID are safe fallbacks; neither case justifies deleting the resident registration.
            String name = storage.getSavedBotList().getCompoundOrEmpty(lowerName).getStringOr("name", lowerName);
            UUID uuid = storage.findUUID(lowerName).orElseGet(() -> BotUtil.getBotUUID(name));
            BotLoadEvent event = new BotLoadEvent(name, uuid);
            this.server.server.getPluginManager().callEvent(event);
            if (event.isCancelled()) {
                return null;
            }

            ServerLevel initialWorld = this.findInitialWorld();
            if (initialWorld == null) {
                LOGGER.debug("Deferring fakeplayer {} because no server world is available yet", name);
                return null;
            }

            ServerBot bot = new ServerBot(this.server, initialWorld, new GameProfile(uuid, name));
            bot.connection = new ServerBotPacketListenerImpl(this.server, bot);
            Optional<ValueInput> optional;
            try (ProblemReporter.ScopedCollector scopedCollector = new ProblemReporter.ScopedCollector(bot.problemPath(), LOGGER)) {
                optional = storage.load(bot, scopedCollector);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            if (optional.isEmpty()) {
                return null;
            }
            ValueInput nbt = optional.get();

            Optional<Long> worldUuidMost = nbt.getLong("WorldUUIDMost");
            Optional<Long> worldUuidLeast = nbt.getLong("WorldUUIDLeast");
            if (worldUuidMost.isEmpty() || worldUuidLeast.isEmpty()) {
                LOGGER.debug("Deferring fakeplayer {} because its world metadata is incomplete", name);
                return null;
            }

            UUID worldUuid = new UUID(worldUuidMost.get(), worldUuidLeast.get());
            ServerLevel world = this.findLoadedWorld(worldUuid);
            if (world == null) {
                // WorldLoadEvent can arrive while Bukkit is still publishing its world map. Keep the data file and
                // let the next world-load/startup drain retry it instead of consuming a valid resident bot.
                LOGGER.debug("Deferring fakeplayer {} because world {} is not loaded yet", name, worldUuid);
                return null;
            }

            ServerBot loaded = this.placeNewBot(bot, world, bot.getLocation(), nbt);
            if (loaded != null) {
                // Storage is consumed only after the entity is fully registered. Any earlier return path preserves the
                // list entry and .dat file for a later retry.
                storage.consumeLoadedData(name, uuid);
            }
            return loaded;
        } catch (Exception e) {
            LOGGER.error("Failed to load bot {}", inputName, e);
            return null;
        }
    }

    /** Finds any world available during the early startup window for constructing the temporary bot instance. */
    @Nullable
    private ServerLevel findInitialWorld() {
        ServerLevel overworld = this.server.getLevel(Level.OVERWORLD);
        if (overworld != null) {
            return overworld;
        }
        for (ServerLevel level : this.server.getAllLevels()) {
            return level;
        }
        return null;
    }

    /** Resolves a saved world without depending solely on Bukkit's still-initialising world registry. */
    @Nullable
    private ServerLevel findLoadedWorld(@NotNull UUID worldUuid) {
        for (ServerLevel level : this.server.getAllLevels()) {
            if (worldUuid.equals(level.uuid)) {
                return level;
            }
        }
        org.bukkit.World bukkitWorld = Bukkit.getServer().getWorld(worldUuid);
        return bukkitWorld instanceof CraftWorld craftWorld ? craftWorld.getHandle() : null;
    }

    public ServerBot placeNewBot(@NotNull ServerBot bot, ServerLevel world, Location location, ValueInput save) {
        Optional<ValueInput> optional = Optional.ofNullable(save);

        bot.isRealPlayer = true;
        bot.loginTime = System.currentTimeMillis();
        bot.connection = new ServerBotPacketListenerImpl(this.server, bot);
        bot.setServerLevel(world);

        BotSpawnLocationEvent event = new BotSpawnLocationEvent(bot.getBukkitEntity(), location);
        this.server.server.getPluginManager().callEvent(event);
        location = event.getSpawnLocation();

        bot.gameMode.setLevel(bot.level());

        bot.setPosRaw(location.getX(), location.getY(), location.getZ());
        bot.setRot(location.getYaw(), location.getPitch());

        bot.connection.teleport(bot.getX(), bot.getY(), bot.getZ(), bot.getYRot(), bot.getXRot());

        this.bots.add(bot);
        this.botsByLowerName.put(bot.getScoreboardName().toLowerCase(Locale.ROOT), bot);
        this.botsByUUID.put(bot.getUUID(), bot);
        bot.applyLeavesXPresentation();

        bot.suppressTrackerForLogin = true;
        world.addNewPlayer(bot);
        optional.ifPresent(nbt -> {
            bot.loadAndSpawnEnderPearls(nbt);
            bot.loadAndSpawnParentVehicle(nbt);
        });

        final net.kyori.adventure.text.Component defaultJoinMessage =
            org.leavesx.leavesx.presentation.LeavesXPlayerPresentation.defaultJoinMessage(bot.getScoreboardName());
        BotJoinEvent event1 = new BotJoinEvent(
            bot.getBukkitEntity(),
            org.leavesx.leavesx.presentation.LeavesXPlayerPresentation.joinMessage(
                bot.getScoreboardName(),
                bot.getBukkitEntity().displayName(),
                defaultJoinMessage
            )
        );
        this.server.server.getPluginManager().callEvent(event1);

        net.kyori.adventure.text.Component joinMessage = event1.joinMessage();
        if (joinMessage != null && !joinMessage.equals(net.kyori.adventure.text.Component.empty())) {
            this.server.getPlayerList().broadcastSystemMessage(PaperAdventure.asVanilla(joinMessage), false);
        }

        bot.renderInfo();
        bot.suppressTrackerForLogin = false;

        bot.level().getChunkSource().addEntity(bot);
        bot.renderData();
        bot.initInventoryMenu();
        botsNameByWorldUuid
            .computeIfAbsent(bot.level().uuid.toString(), (k) -> new HashSet<>())
            .add(bot.getBukkitEntity().getName());
        BotList.LOGGER.info("{}[{}] logged in with entity id {} at ([{}]{}, {}, {})", bot.getName().getString(), "Local", bot.getId(), bot.level().serverLevelData.getLevelName(), bot.getX(), bot.getY(), bot.getZ());
        return bot;
    }

    public boolean removeBot(@NotNull ServerBot bot, @NotNull BotRemoveEvent.RemoveReason reason, @Nullable CommandSender remover, boolean save, boolean resume) {
        final net.kyori.adventure.text.Component defaultQuitMessage =
            org.leavesx.leavesx.presentation.LeavesXPlayerPresentation.defaultQuitMessage(bot.getScoreboardName());
        BotRemoveEvent event = new BotRemoveEvent(
            bot.getBukkitEntity(),
            reason,
            remover,
            org.leavesx.leavesx.presentation.LeavesXPlayerPresentation.quitMessage(
                bot.getScoreboardName(),
                bot.getBukkitEntity().displayName(),
                defaultQuitMessage
            ),
            save
        );
        this.server.server.getPluginManager().callEvent(event);

        if (event.isCancelled() && event.getReason() != BotRemoveEvent.RemoveReason.INTERNAL) {
            return false;
        }

        if (bot.removeTaskId != -1) {
            Bukkit.getScheduler().cancelTask(bot.removeTaskId);
            bot.removeTaskId = -1;
        }

        bot.disconnect();

        this.resumeDataStorage.removeSavedData(bot.nameAndId().name());
        if (event.shouldSave()) {
            if (resume) {
                this.resumeDataStorage.save(bot);
            } else {
                this.manualSaveDataStorage.save(bot);
            }
        } else {
            bot.dropExperience();
            bot.dropAll(true);
            botsNameByWorldUuid.getOrDefault(bot.level().uuid.toString(), new HashSet<>()).remove(bot.getBukkitEntity().getName());
        }

        if (bot.isPassenger() && event.shouldSave()) {
            Entity entity = bot.getRootVehicle();
            if (entity.hasExactlyOnePlayerPassenger()) {
                bot.stopRiding();
                entity.getPassengersAndSelf().forEach((entity1) -> {
                    if (!org.leavesmc.leaves.LeavesConfig.modify.oldMC.voidTrade && entity1 instanceof AbstractVillager villager) {
                        final Player human = villager.getTradingPlayer();
                        if (human != null) {
                            villager.setTradingPlayer(null);
                        }
                    }
                    entity1.setRemoved(Entity.RemovalReason.UNLOADED_WITH_PLAYER);
                });
            }
        }

        bot.unRide();
        for (ThrownEnderpearl thrownEnderpearl : bot.getEnderPearls()) {
            if (!thrownEnderpearl.level().paperConfig().misc.legacyEnderPearlBehavior) {
                thrownEnderpearl.setRemoved(Entity.RemovalReason.UNLOADED_WITH_PLAYER, EntityRemoveEvent.Cause.PLAYER_QUIT);
            } else {
                thrownEnderpearl.setOwner(null);
            }
        }

        bot.level().removePlayerImmediately(bot, Entity.RemovalReason.UNLOADED_WITH_PLAYER);
        this.bots.remove(bot);
        this.botsByLowerName.remove(bot.getScoreboardName().toLowerCase(Locale.ROOT));

        UUID uuid = bot.getUUID();
        ServerBot bot1 = this.botsByUUID.get(uuid);
        if (bot1 == bot) {
            this.botsByUUID.remove(uuid);
        }

        bot.removeTab();
        ClientboundRemoveEntitiesPacket packet = new ClientboundRemoveEntitiesPacket(bot.getId());
        for (ServerPlayer player : bot.level().players()) {
            if (!(player instanceof ServerBot)) {
                player.connection.send(packet);
            }
        }

        net.kyori.adventure.text.Component removeMessage = event.removeMessage();
        if (removeMessage != null && !removeMessage.equals(net.kyori.adventure.text.Component.empty())) {
            this.server.getPlayerList().broadcastSystemMessage(PaperAdventure.asVanilla(removeMessage), false);
        }
        return true;
    }

    public void removeAllIn(String worldUuid) {
        this.removePendingStartupResume(worldUuid);
        for (String fullName : this.botsNameByWorldUuid.getOrDefault(worldUuid, new HashSet<>())) {
            ServerBot bot = this.getBotByName(fullName);
            if (bot != null) {
                this.removeBot(bot, BotRemoveEvent.RemoveReason.INTERNAL, null, LeavesConfig.modify.fakeplayer.canResident, LeavesConfig.modify.fakeplayer.canResident);
            }
        }
    }

    public void removeAll() {
        this.pendingResumeBots.clear();
        this.pendingResumeKeys.clear();
        for (ServerBot bot : this.bots) {
            bot.resume = LeavesConfig.modify.fakeplayer.canResident;
            this.removeBot(bot, BotRemoveEvent.RemoveReason.INTERNAL, null, LeavesConfig.modify.fakeplayer.canResident, LeavesConfig.modify.fakeplayer.canResident);
        }
    }

    public void loadResumeBotInfo() {
        if (!LeavesConfig.modify.fakeplayer.enable || !LeavesConfig.modify.fakeplayer.canResident) {
            return;
        }
        CompoundTag savedBotList = this.getResumeBotList().copy();
        final List<String> invalidEntries = new ArrayList<>();
        for (Map.Entry<String, Tag> entry : savedBotList.entrySet()) {
            String lowerName = entry.getKey();
            if (!(entry.getValue() instanceof CompoundTag record)) {
                if (org.leavesx.leavesx.config.LeavesXRuntime.cleanInvalidResidentBots()
                    && this.resumeDataStorage.isDataFileCorrupt(lowerName)) {
                    invalidEntries.add(lowerName);
                }
                LOGGER.debug("Keeping fakeplayer record {} because its list entry is not a compound", lowerName);
                continue;
            }
            String fullName = record.getStringOr("name", lowerName);
            UUID levelUuid = BotUtil.getBotLevel(fullName, this.resumeDataStorage);
            if (levelUuid == null) {
                if (org.leavesx.leavesx.config.LeavesXRuntime.cleanInvalidResidentBots()
                    && this.resumeDataStorage.isDataFileCorrupt(fullName)) {
                    invalidEntries.add(lowerName);
                } else {
                    // Missing world metadata, an unavailable world, or a missing file is not proof of corruption. Keep
                    // the record so operators can restore it and so a later world load can retry it.
                    LOGGER.debug("Keeping resident fakeplayer {} until its world/data becomes available", fullName);
                }
                continue;
            }
            this.botsNameByWorldUuid
                .computeIfAbsent(levelUuid.toString(), (k) -> new HashSet<>())
                .add(fullName);
        }
        this.removeInvalidResidentEntries(this.resumeDataStorage, invalidEntries);
        loadLegacyResumeBotInfo();

        // Depending on the Paper startup path, a world's WorldLoadEvent may have fired before Leaves loads the
        // resident index. Revisit worlds that are already present so those bots are queued just like later events.
        for (ServerLevel level : this.server.getAllLevels()) {
            this.loadResume(level.uuid.toString());
        }
    }

    private void loadLegacyResumeBotInfo() {
        CompoundTag savedBotList = this.getManualSavedBotList().copy();
        final List<String> invalidEntries = new ArrayList<>();
        for (String fullName : savedBotList.keySet()) {
            // Legacy format saved fullName as the key
            CompoundTag nbt = savedBotList.getCompound(fullName).orElse(null);
            if (nbt == null) {
                if (org.leavesx.leavesx.config.LeavesXRuntime.cleanInvalidResidentBots()
                    && this.manualSaveDataStorage.isDataFileCorrupt(fullName)) {
                    invalidEntries.add(fullName);
                }
                LOGGER.debug("Keeping legacy fakeplayer record {} because its list entry is not a compound", fullName);
                continue;
            }
            if (!nbt.getBoolean("resume").orElse(false)) {
                continue;
            }
            UUID levelUuid = BotUtil.getBotLevel(fullName, this.manualSaveDataStorage);
            if (levelUuid == null) {
                if (org.leavesx.leavesx.config.LeavesXRuntime.cleanInvalidResidentBots()
                    && this.manualSaveDataStorage.isDataFileCorrupt(fullName)) {
                    invalidEntries.add(fullName);
                } else {
                    LOGGER.debug("Keeping legacy resident fakeplayer {} until its world/data becomes available", fullName);
                }
                continue;
            }
            this.legacyBotsNameByWorldUuid
                .computeIfAbsent(levelUuid.toString(), (k) -> new HashSet<>())
                .add(fullName);
        }
        this.removeInvalidResidentEntries(this.manualSaveDataStorage, invalidEntries);
    }

    private void removeInvalidResidentEntries(final BotDataStorage storage, final List<String> invalidEntries) {
        if (invalidEntries.isEmpty()) {
            return;
        }
        final int removed = storage.removeSavedDataEntries(invalidEntries);
        if (removed != 0) {
            // Entity .dat files remain untouched so an operator can still recover or replace corrupt data manually.
            LOGGER.info("Removed {} resident bot registration(s) whose data file is definitely corrupt.", removed);
        }
    }

    /** Reapplies display-only settings to active bots after a LeavesX configuration reload. */
    public void refreshLeavesXPresentation() {
        this.bots.forEach(ServerBot::refreshLeavesXPresentation);
    }

    public void loadResume(String worldUuid) {
        if (!LeavesConfig.modify.fakeplayer.enable || !LeavesConfig.modify.fakeplayer.canResident) {
            return;
        }
        final Set<String> resumeNames = new HashSet<>(this.botsNameByWorldUuid.getOrDefault(worldUuid, Set.of()));
        final Set<String> legacyNames = new HashSet<>(this.legacyBotsNameByWorldUuid.getOrDefault(worldUuid, Set.of()));
        if (!this.server.isReady()) {
            resumeNames.forEach(name -> this.enqueueStartupResume(worldUuid, name, false));
            legacyNames.forEach(name -> this.enqueueStartupResume(worldUuid, name, true));
            return;
        }
        resumeNames.forEach(this::loadNewResumeBot);
        legacyNames.forEach(this::loadNewManualSavedBot);
    }

    public void updateBotLevel(@NotNull ServerBot bot, @NotNull ServerLevel level) {
        String prevUuid = bot.level().uuid.toString();
        String newUuid = level.uuid.toString();
        this.botsNameByWorldUuid
            .computeIfAbsent(newUuid, (k) -> new HashSet<>())
            .add(bot.getBukkitEntity().getName());
        this.botsNameByWorldUuid
            .computeIfAbsent(prevUuid, (k) -> new HashSet<>())
            .remove(bot.getBukkitEntity().getName());
    }

    public void networkTick() {
        this.drainStartupResumeQueue();
        this.bots.forEach(ServerBot::networkTick);
    }

    private void enqueueStartupResume(final String worldUuid, final String name, final boolean legacy) {
        final String key = (legacy ? "legacy:" : "resume:") + worldUuid + ':' + name.toLowerCase(Locale.ROOT);
        if (this.pendingResumeKeys.add(key)) {
            this.pendingResumeBots.addLast(new PendingResumeBot(name, legacy, key));
        }
    }

    private void drainStartupResumeQueue() {
        if (!this.server.isReady()
            || !LeavesConfig.modify.fakeplayer.enable
            || !LeavesConfig.modify.fakeplayer.canResident) {
            return;
        }
        final int budget = org.leavesx.leavesx.config.LeavesXRuntime.startupResidentBots()
            ? org.leavesx.leavesx.config.LeavesXRuntime.startupResidentBotsPerTick()
            : Integer.MAX_VALUE;
        for (int loaded = 0; loaded < budget; loaded++) {
            final PendingResumeBot pending = this.pendingResumeBots.pollFirst();
            if (pending == null) {
                return;
            }
            this.pendingResumeKeys.remove(pending.key());
            if (pending.legacy()) {
                this.loadNewManualSavedBot(pending.name());
            } else {
                this.loadNewResumeBot(pending.name());
            }
        }
    }

    private void removePendingStartupResume(final String worldUuid) {
        this.pendingResumeBots.removeIf(pending -> {
            final String marker = ':' + worldUuid + ':';
            final boolean matches = pending.key().contains(marker);
            if (matches) {
                this.pendingResumeKeys.remove(pending.key());
            }
            return matches;
        });
    }

    private record PendingResumeBot(String name, boolean legacy, String key) {
    }

    @Nullable
    public ServerBot getBot(@NotNull UUID uuid) {
        return this.botsByUUID.get(uuid);
    }

    @Nullable
    public ServerBot getBotByName(@NotNull String name) {
        return this.botsByLowerName.get(name.toLowerCase(Locale.ROOT));
    }

    public CompoundTag getManualSavedBotList() {
        return this.getSavedBotList(this.manualSaveDataStorage);
    }

    public CompoundTag getResumeBotList() {
        return this.getSavedBotList(this.resumeDataStorage);
    }

    public CompoundTag getSavedBotList(@NotNull BotDataStorage storage) {
        return storage.getSavedBotList();
    }

    @Contract("_, _, _ -> new")
    public static @NotNull GameProfile createBotProfile(UUID uuid, String name, String[] skin) {
        GameProfile profile = new GameProfile(uuid, name, new MutablePropertyMap());
        profile.properties().put("is_bot", new Property("is_bot", "true"));
        if (skin != null) {
            profile.properties().put("textures", new Property("textures", skin[0], skin[1]));
        }
        return profile;
    }
}
