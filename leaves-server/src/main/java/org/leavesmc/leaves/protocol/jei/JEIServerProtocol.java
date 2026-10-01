package org.leavesmc.leaves.protocol.jei;

import java.util.Map;
import java.util.UUID;
import com.google.common.cache.CacheBuilder;
import java.util.concurrent.TimeUnit;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import org.leavesmc.leaves.protocol.core.LeavesProtocol;
import org.leavesmc.leaves.protocol.core.ProtocolHandler;
import org.leavesmc.leaves.protocol.core.ProtocolUtils;
import org.leavesx.leavesx.config.LeavesXRuntime;
import org.leavesx.leavesx.protocol.JeiTransferRequest;
import org.leavesx.leavesx.protocol.RecipeBookTransfer;

@LeavesProtocol.Register(namespace = "jei")
public final class JEIServerProtocol implements LeavesProtocol {
    private static final Map<UUID, Long> LAST_REQUEST = CacheBuilder.newBuilder()
        .maximumSize(4096).expireAfterAccess(1, TimeUnit.MINUTES).<UUID, Long>build().asMap();

    @Override
    public boolean isActive() {
        return LeavesXRuntime.configuration().extensions().jeiRecipeTransfer();
    }

    @ProtocolHandler.BytebufReceiver(key = "recipe_transfer")
    public static void transfer(ServerPlayer player, RegistryFriendlyByteBuf buffer) {
        receive(player, buffer, false, false);
    }

    @ProtocolHandler.BytebufReceiver(key = "recipe_transfer_counted")
    public static void counted(ServerPlayer player, RegistryFriendlyByteBuf buffer) {
        receive(player, buffer, true, false);
    }

    @ProtocolHandler.BytebufReceiver(key = "recipe_transfer_with_result")
    public static void result(ServerPlayer player, RegistryFriendlyByteBuf buffer) {
        receive(player, buffer, false, true);
    }

    @ProtocolHandler.BytebufReceiver(key = "recipe_transfer_counted_with_result")
    public static void countedResult(ServerPlayer player, RegistryFriendlyByteBuf buffer) {
        receive(player, buffer, true, true);
    }

    // JEI uses this channel as its capability probe. Deliberately never delete or create items.
    @ProtocolHandler.BytebufReceiver(key = "delete_player_item")
    public static void denyDelete(ServerPlayer player, RegistryFriendlyByteBuf buffer) {}

    @ProtocolHandler.BytebufReceiver(key = "request_cheat_permission")
    public static void denyCheat(ServerPlayer player, RegistryFriendlyByteBuf buffer) {
        if (!admit(player)) return;
        ProtocolUtils.sendBytebufPacket(player, Identifier.parse("jei:cheat_permission"), reply -> {
            reply.writeBoolean(false);
            reply.writeVarInt(0);
        });
    }

    @ProtocolHandler.PlayerLeave
    public static void leave(ServerPlayer player) {
        LAST_REQUEST.remove(player.getUUID());
    }

    private static boolean admit(ServerPlayer player) {
        final long now = System.nanoTime();
        final Long previous = LAST_REQUEST.get(player.getUUID());
        if (previous != null && now - previous < 100_000_000L) return false;
        LAST_REQUEST.put(player.getUUID(), now);
        return true;
    }

    private static void receive(ServerPlayer player, RegistryFriendlyByteBuf buffer, boolean counted, boolean withResult) {
        final JeiTransferRequest request;
        try {
            request = JeiTransferRequest.read(buffer, counted, withResult);
        } catch (RuntimeException malformed) {
            // Parsing only: vanilla's oversized VarInt is an untyped RuntimeException.
            return;
        }
        final boolean accepted = admit(player) && RecipeBookTransfer.transfer(player, request);
        if (withResult) {
            ProtocolUtils.sendBytebufPacket(player, Identifier.parse("jei:recipe_transfer_result"), reply -> {
                reply.writeVarInt(request.transferId());
                reply.writeBoolean(accepted);
            });
        }
    }
}
