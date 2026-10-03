package org.leavesx.leavesx.protocol;

import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeSerializer;
import org.leavesmc.leaves.LeavesLogger;

/** Minecraft 26.1.2 的加载器配方同步布局；只使用两端都认识的已注册原版序列化器。 */
public final class JeiRecipeSync {
    public static final Identifier FABRIC = Identifier.parse("fabric:recipe_sync");
    public static final Identifier NEOFORGE = Identifier.parse("neoforge:recipe_content");
    private static final int MAX_BYTES = 1_048_000;

    private JeiRecipeSync() {}

    public static DiscardedPayload encode(Identifier channel, Collection<RecipeHolder<?>> recipes, RegistryAccess registries) {
        final boolean fabric = channel.equals(FABRIC);
        if (!fabric && !channel.equals(NEOFORGE)) throw new IllegalArgumentException("Unknown recipe sync channel");
        final Map<RecipeSerializer<?>, List<byte[]>> groups = new LinkedHashMap<>();
        int totalBytes = 0;
        int skipped = 0;
        for (RecipeHolder<?> holder : recipes) {
            final RecipeSerializer<?> serializer = holder.value().getSerializer();
            final Identifier name = BuiltInRegistries.RECIPE_SERIALIZER.getKey(serializer);
            if (name == null || !name.getNamespace().equals("minecraft") || BuiltInRegistries.RECIPE_SERIALIZER.getId(serializer) < 0) {
                skipped++;
                continue;
            }
            final RegistryFriendlyByteBuf entry = buffer(registries);
            try {
                if (fabric) {
                    entry.writeResourceKey(holder.id());
                    encodeValue(entry, holder);
                } else {
                    RecipeHolder.STREAM_CODEC.encode(entry, holder);
                }
                final byte[] bytes = ByteBufUtil.getBytes(entry);
                totalBytes += bytes.length;
                if (totalBytes > MAX_BYTES) throw new IllegalStateException("Recipe sync exceeds the client payload limit");
                groups.computeIfAbsent(serializer, ignored -> new ArrayList<>()).add(bytes);
            } catch (io.netty.handler.codec.EncoderException | ClassCastException incompatible) {
                // 插件配方（包括未注册的自定义序列化器）不能破坏整个数据包。
                skipped++;
            } finally {
                entry.release();
            }
        }
        final RegistryFriendlyByteBuf output = buffer(registries);
        try {
            if (fabric) {
                output.writeVarInt(groups.size());
                for (var group : groups.entrySet()) {
                    output.writeIdentifier(BuiltInRegistries.RECIPE_SERIALIZER.getKey(group.getKey()));
                    output.writeVarInt(group.getValue().size());
                    group.getValue().forEach(output::writeBytes);
                }
            } else {
                final var types = BuiltInRegistries.RECIPE_TYPE.stream().toList();
                output.writeVarInt(types.size());
                for (var type : types) ByteBufCodecs.registry(Registries.RECIPE_TYPE).encode(output, type);
                output.writeVarInt(groups.values().stream().mapToInt(List::size).sum());
                groups.values().forEach(entries -> entries.forEach(output::writeBytes));
            }
            if (skipped > 0) LeavesLogger.LOGGER.warn("Just Enough Items recipe sync skipped {} recipes with unsupported serializers ({})", skipped, channel);
            return new DiscardedPayload(channel, ByteBufUtil.getBytes(output));
        } finally {
            output.release();
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void encodeValue(RegistryFriendlyByteBuf buffer, RecipeHolder<?> holder) {
        ((net.minecraft.network.codec.StreamCodec) holder.value().getSerializer().streamCodec()).encode(buffer, holder.value());
    }

    private static RegistryFriendlyByteBuf buffer(RegistryAccess registries) {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(256, MAX_BYTES), registries);
    }
}
