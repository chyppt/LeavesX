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

    private static void invalidate() {
        PAYLOADS.clear();
        FAILED.clear();
        SENT.clear();
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
                    payload = JeiRecipeSync.encode(channel, server.getRecipeManager().getRecipes(), server.registryAccess());
                    PAYLOADS.put(channel, payload);
                } catch (RuntimeException failure) {
                    FAILED.add(channel);
                    LeavesLogger.LOGGER.warn("Unable to encode Just Enough Items recipes for {}; synchronization will retry after recipe reload", channel, failure);
                    continue;
                }
            }
            ProtocolUtils.sendPayloadPacket(player, payload);
            if (channel.equals(JeiRecipeSync.NEOFORGE)) {
                player.connection.send(new ClientboundUpdateTagsPacket(TagNetworkSerialization.serializeTagsToNetwork(server.registries())));
            }
            SENT.put(player.getUUID(), channel);
        }
    }
}
