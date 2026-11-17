package org.leavesmc.leaves.protocol.jei;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagNetworkSerialization;
import org.leavesmc.leaves.LeavesLogger;
import org.leavesmc.leaves.protocol.core.LeavesProtocol;
import org.leavesmc.leaves.protocol.core.ProtocolHandler;
import org.leavesmc.leaves.protocol.core.ProtocolUtils;
import org.leavesx.leavesx.config.LeavesXRuntime;
import org.leavesx.leavesx.protocol.JeiRecipeSync;
import org.leavesmc.leaves.recipeviewer.RecipeViewerBridgeService;
import net.minecraft.world.item.crafting.RecipeHolder;

@LeavesProtocol.Register(namespace = "leavesx_recipe_sync")
public final class JEIRecipeSyncProtocol implements LeavesProtocol {
    private static final Map<Identifier, DiscardedPayload> PAYLOADS = new HashMap<>();
    private static final Set<Identifier> FAILED = new HashSet<>();
    private static final Map<UUID, Identifier> SENT = new HashMap<>();

    @Override
    public boolean isActive() {
        // Lifecycle callbacks must clear state even if the operator just disabled synchronization.
        return true;
    }

    @Override
    public int tickerInterval(String tickerId) {
        return 20;
    }

    @ProtocolHandler.ReloadDataPack
    public static void onDataPackReload() {
        invalidate();
    }

    @ProtocolHandler.ReloadServer
    public static void onServerReload() {
        invalidate();
    }

    public static void invalidate() {
        PAYLOADS.clear();
        FAILED.clear();
        SENT.clear();
    }

    /** 配方桥接 provider 发布新代时清理玩家和协议缓存。 */
    public static void onRecipeViewerProviderRefresh() {
        invalidate();
    }

    @ProtocolHandler.PlayerLeave
    public static void leave(ServerPlayer player) {
        SENT.remove(player.getUUID());
    }

    @ProtocolHandler.Ticker
    public static void tick() {
        if (!LeavesXRuntime.configuration().extensions().jeiRecipeSync()) {
            invalidate();
            return;
        }
        final MinecraftServer server = MinecraftServer.getServer();
        // Registration may arrive after PlayerJoinEvent. Inspect the negotiated channels, never guess by brand.
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            final Set<String> channels = player.getBukkitEntity().getListeningPluginChannels();
            final Identifier channel = channels.contains(JeiRecipeSync.FABRIC.toString()) ? JeiRecipeSync.FABRIC
                : channels.contains(JeiRecipeSync.NEOFORGE.toString()) ? JeiRecipeSync.NEOFORGE : null;
            if (channel == null || channel.equals(SENT.get(player.getUUID())) || FAILED.contains(channel)) continue;
            DiscardedPayload payload = PAYLOADS.get(channel);
            if (payload == null) {
                try {
                    final java.util.LinkedHashMap<Object, RecipeHolder<?>> recipes = new java.util.LinkedHashMap<>();
                    server.getRecipeManager().getRecipes().forEach(holder -> recipes.put(holder.id(), holder));
                    RecipeViewerBridgeService.INSTANCE.snapshots().forEach((providerId, snapshot) -> {
                        try {
                            RecipeViewerJeiSnapshot.parse(snapshot, providerId, server.registryAccess())
                                    .forEach(holder -> recipes.put(holder.id(), holder));
                        } catch (RuntimeException failure) {
                            LeavesLogger.LOGGER.warn("Skipping invalid JEI recipe provider {}", providerId, failure);
                        }
                    });
                    try {
                        payload = JeiRecipeSync.encode(channel, recipes.values(), server.registryAccess());
                    } catch (RuntimeException providerFailure) {
                        // A provider may exceed the loader's total packet budget. Preserve baseline synchronization.
                        LeavesLogger.LOGGER.warn("JEI provider payload failed for {}; retrying the original server recipes", channel, providerFailure);
                        payload = JeiRecipeSync.encode(channel, server.getRecipeManager().getRecipes(), server.registryAccess());
                    }
                    PAYLOADS.put(channel, payload);
                    if (!RecipeViewerBridgeService.INSTANCE.snapshots().isEmpty()) {
                        LeavesLogger.LOGGER.info("JEI recipe generation ready: channel={}, bytes={}", channel, payload.data().length);
                    }
                } catch (RuntimeException failure) {
                    FAILED.add(channel);
                    LeavesLogger.LOGGER.warn("Unable to encode Just Enough Items recipes for {}; synchronization will retry after recipe reload", channel, failure);
                    continue;
                }
            }
            ProtocolUtils.sendPayloadPacket(player, payload);
            if (channel.equals(JeiRecipeSync.NEOFORGE)) {
                player.connection.send(new ClientboundUpdateTagsPacket(TagNetworkSerialization.serializeTagsToNetwork(server.registries())));
            } else {
                // Fabric JEI caches content on recipe_sync, but rebuilds its GUI on handleUpdateRecipes.
                // Our negotiated-channel sync happens after login, so preserve this ordering on every generation.
                var manager = server.getRecipeManager();
                player.connection.send(new net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket(
                        manager.getSynchronizedItemProperties(), manager.getSynchronizedStonecutterRecipes()));
            }
            SENT.put(player.getUUID(), channel);
        }
    }
}
