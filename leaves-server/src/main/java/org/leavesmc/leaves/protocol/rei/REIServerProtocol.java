package org.leavesmc.leaves.protocol.rei;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.netty.buffer.Unpooled;
import net.minecraft.ChatFormatting;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import org.bukkit.Bukkit;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.LeavesConfig;
import org.leavesmc.leaves.LeavesLogger;
import org.leavesmc.leaves.plugin.MinecraftInternalPlugin;
import org.leavesmc.leaves.protocol.core.LeavesProtocol;
import org.leavesmc.leaves.protocol.core.ProtocolHandler;
import org.leavesmc.leaves.protocol.core.ProtocolUtils;
import org.leavesmc.leaves.protocol.rei.display.BlastingDisplay;
import org.leavesmc.leaves.protocol.rei.display.CampfireDisplay;
import org.leavesmc.leaves.protocol.rei.display.Display;
import org.leavesmc.leaves.protocol.rei.display.ShapedDisplay;
import org.leavesmc.leaves.protocol.rei.display.ShapelessDisplay;
import org.leavesmc.leaves.protocol.rei.display.SmeltingDisplay;
import org.leavesmc.leaves.protocol.rei.display.SmokingDisplay;
import org.leavesmc.leaves.protocol.rei.display.StoneCuttingDisplay;
import org.leavesmc.leaves.protocol.rei.payload.DisplaySyncPayload;
import org.leavesmc.leaves.protocol.rei.transfer.InputSlotCrafter;
import org.leavesmc.leaves.protocol.rei.transfer.NewInputSlotCrafter;
import org.leavesmc.leaves.protocol.rei.transfer.slot.SlotAccessor;
import org.leavesmc.leaves.protocol.rei.transfer.slot.VanillaSlotAccessor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

@LeavesProtocol.Register(namespace = REIServerProtocol.PROTOCOL_ID)
public class REIServerProtocol implements LeavesProtocol {

    public static final String PROTOCOL_ID = "roughlyenoughitems";
    public static final String CHEAT_PERMISSION = "leaves.protocol.rei.cheat";
    public static final Identifier DELETE_ITEMS_PACKET = Identifier.fromNamespaceAndPath("roughlyenoughitems", "delete_item");
    public static final Identifier CREATE_ITEMS_PACKET = Identifier.fromNamespaceAndPath("roughlyenoughitems", "create_item");
    public static final Identifier CREATE_ITEMS_HOTBAR_PACKET = Identifier.fromNamespaceAndPath("roughlyenoughitems", "create_item_hotbar");
    public static final Identifier CREATE_ITEMS_GRAB_PACKET = Identifier.fromNamespaceAndPath("roughlyenoughitems", "create_item_grab");
    public static final Identifier CREATE_ITEMS_MESSAGE_PACKET = Identifier.fromNamespaceAndPath("roughlyenoughitems", "ci_msg");
    public static final Identifier MOVE_ITEMS_NEW_PACKET = Identifier.fromNamespaceAndPath("roughlyenoughitems", "move_items_new");
    public static final Identifier NOT_ENOUGH_ITEMS_PACKET = Identifier.fromNamespaceAndPath("roughlyenoughitems", "og_not_enough"); // this pack is under to-do at rei-client, so we don't handle it
    public static final Identifier SYNC_DISPLAYS_PACKET = Identifier.fromNamespaceAndPath("roughlyenoughitems", "sync_displays");

    public static final Map<Identifier, PacketTransformer> TRANSFORMERS = Util.make(() -> {
        ImmutableMap.Builder<Identifier, PacketTransformer> builder = ImmutableMap.builder();
        builder.put(SYNC_DISPLAYS_PACKET, new PacketTransformer());
        builder.put(DELETE_ITEMS_PACKET, new PacketTransformer());
        builder.put(CREATE_ITEMS_PACKET, new PacketTransformer());
        builder.put(CREATE_ITEMS_GRAB_PACKET, new PacketTransformer());
        builder.put(CREATE_ITEMS_HOTBAR_PACKET, new PacketTransformer());
        builder.put(MOVE_ITEMS_NEW_PACKET, new PacketTransformer());
        return builder.build();
    });
    private static final Set<ServerPlayer> enabledPlayers = new HashSet<>();
    private static final Executor executor = new ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(1),
        runnable -> {
            Thread thread = new Thread(runnable, "LeavesX-REI-Encoder");
            thread.setDaemon(true);
            return thread;
        },
        new ThreadPoolExecutor.DiscardOldestPolicy()
    );
    private static int minecraftRecipeVer = 0;
    private static int nextReiRecipeVer = -1;
    private static ImmutableList<CustomPacketPayload> cachedPayloads;

    @ProtocolHandler.ReloadDataPack
    public static void onRecipeReload() {
        minecraftRecipeVer++;
        cachedPayloads = null;
    }

    @Contract("_ -> new")
    public static Identifier id(String path) {
        return Identifier.tryBuild(PROTOCOL_ID, path);
    }

    public static void onConfigModify(boolean enabled) {
        PluginManager pluginManager = Bukkit.getServer().getPluginManager();
        if (enabled) {
            if (pluginManager.getPermission(CHEAT_PERMISSION) == null) {
                pluginManager.addPermission(new Permission(CHEAT_PERMISSION, PermissionDefault.OP));
            }
        } else {
            pluginManager.removePermission(CHEAT_PERMISSION);
            enabledPlayers.clear();
            TRANSFORMERS.values().forEach(PacketTransformer::clear);
            cachedPayloads = null;
            minecraftRecipeVer++;
        }
    }

    @ProtocolHandler.PlayerLeave
    public static void onPlayerLoggedOut(@NotNull ServerPlayer player) {
        enabledPlayers.remove(player);
        TRANSFORMERS.values().forEach(transformer -> transformer.clear(player.getUUID()));
    }

    @ProtocolHandler.Ticker
    public static void tick() {
        if (minecraftRecipeVer != nextReiRecipeVer) {
            nextReiRecipeVer = minecraftRecipeVer;
            // Snapshot recipes/displays on the server thread. Only encoding the detached display goes to the worker.
            try {
                reloadRecipe(nextReiRecipeVer);
            } catch (RuntimeException failure) {
                LeavesLogger.LOGGER.warn("Unable to prepare Roughly Enough Items recipes; retrying after the next recipe reload", failure);
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void reloadRecipe(int reiRecipeVer) {
        ImmutableList.Builder<Display> builder = ImmutableList.builder();
        MinecraftServer server = MinecraftServer.getServer();
        RecipeMap recipeMap = server.getRecipeManager().recipes;
        recipeMap.byType(RecipeType.CRAFTING).forEach(holder -> {
            switch (holder.value()) {
                case ShapedRecipe ignored -> builder.add(new ShapedDisplay((RecipeHolder) holder));
                case ShapelessRecipe ignored -> builder.add(new ShapelessDisplay((RecipeHolder) holder));
                // map_cloning is a generic TransmuteRecipe; match it by id before the generic case so it gets a shapeless-style display
                case TransmuteRecipe ignored when "minecraft:map_cloning".equals(holder.id().identifier().toString()) -> builder.addAll(Display.ofMapCloningRecipe(holder));
                case TransmuteRecipe ignored -> builder.addAll(Display.ofTransmuteRecipe((RecipeHolder) holder));
                case FireworkRocketRecipe ignored -> builder.addAll(Display.ofFireworkRocketRecipe((RecipeHolder) holder));
                // tipped_arrow is the only ImbueRecipe, so a plain type match routes it
                case ImbueRecipe ignored -> builder.addAll(Display.ofTippedArrowRecipe(holder));
                // ignore ArmorDyeRecipe, BannerDuplicateRecipe, BookCloningRecipe, ShieldDecorationRecipe, RepairItemRecipe
                default -> {
                }
            }
        });
        recipeMap.byType(RecipeType.STONECUTTING).forEach(holder -> builder.add(new StoneCuttingDisplay(holder)));
        recipeMap.byType(RecipeType.SMELTING).forEach(holder -> builder.add(new SmeltingDisplay(holder)));
        recipeMap.byType(RecipeType.BLASTING).forEach(holder -> builder.add(new BlastingDisplay(holder)));
        recipeMap.byType(RecipeType.SMOKING).forEach(holder -> builder.add(new SmokingDisplay(holder)));
        recipeMap.byType(RecipeType.CAMPFIRE_COOKING).forEach(holder -> builder.add(new CampfireDisplay(holder)));
        recipeMap.byType(RecipeType.SMITHING).forEach(holder -> {
            switch (holder.value()) {
                case SmithingTrimRecipe ignored -> builder.addAll(Display.ofSmithingTrimRecipe((RecipeHolder) holder));
                case SmithingTransformRecipe ignored -> builder.add(Display.ofTransforming((RecipeHolder) holder));
                default -> {
                }
            }
        });

        DisplaySyncPayload displaySyncPayload = new DisplaySyncPayload(
            DisplaySyncPayload.SyncType.SET,
            builder.build(),
            reiRecipeVer
        );

        final RegistryAccess registries = server.registryAccess();
        executor.execute(() -> encodeAndPublish(displaySyncPayload, registries, reiRecipeVer));
    }

    private static void encodeAndPublish(DisplaySyncPayload displaySyncPayload, RegistryAccess registries, int version) {
        RegistryFriendlyByteBuf s2cBuf = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
        ImmutableList.Builder<CustomPacketPayload> listBuilder = ImmutableList.builder();
        try {
            DisplaySyncPayload.STREAM_CODEC.encode(s2cBuf, displaySyncPayload);
            outboundTransform(s2cBuf, (id, splitBuf) -> listBuilder.add(PacketTransformer.wrapRei(id, splitBuf)));
        } catch (RuntimeException failure) {
            LeavesLogger.LOGGER.warn("Unable to encode Roughly Enough Items recipes; retrying after the next recipe reload", failure);
            return;
        } finally {
            s2cBuf.release();
        }

        final ImmutableList<CustomPacketPayload> completed = listBuilder.build();
        Bukkit.getGlobalRegionScheduler().run(MinecraftInternalPlugin.INSTANCE, (task) -> {
            if (!LeavesConfig.protocol.reiServerProtocol || minecraftRecipeVer != version) return;
            cachedPayloads = completed;
            for (ServerPlayer player : enabledPlayers) {
                for (CustomPacketPayload payload : completed) {
                    ProtocolUtils.sendPayloadPacket(player, payload);
                }
            }
        });
    }

    @ProtocolHandler.MinecraftRegister(onlyNamespace = true, stage = ProtocolHandler.Stage.GAME)
    public static void onPlayerSubscribed(@NotNull ServerPlayer player, Identifier location) {
        String channel = location.getPath();
        if (channel.equals("sync_displays")) {
            enabledPlayers.add(player);
            if (cachedPayloads != null) {
                cachedPayloads.forEach(payload -> ProtocolUtils.sendPayloadPacket(player, payload));
            }
        } else if (channel.equals("ci_msg")) {
            // cheat rei-client into using "delete_item" packet
            if (!MinecraftServer.getServer().getProfilePermissions(player.nameAndId()).level().isEqualOrHigherThan(PermissionLevel.MODERATORS)) {
                player.getBukkitEntity().sendOpLevel((byte) 1);
            }
        }
    }

    @ProtocolHandler.BytebufReceiver(key = "delete_item")
    public static void handleDeleteItem(ServerPlayer player, RegistryFriendlyByteBuf buf) {
        if (!hasCheatPermission(player)) {
            return;
        }

        inboundTransform(player, DELETE_ITEMS_PACKET, buf, (id, wholeBuf) -> {
            AbstractContainerMenu menu = player.containerMenu;
            if (!menu.getCarried().isEmpty()) {
                menu.setCarried(ItemStack.EMPTY);
                menu.broadcastChanges();
            }
        });
    }

    @ProtocolHandler.BytebufReceiver(key = "create_item")
    public static void handleCreateItem(ServerPlayer player, RegistryFriendlyByteBuf buf) {
        if (!hasCheatPermission(player)) {
            return;
        }
        BiConsumer<Identifier, RegistryFriendlyByteBuf> consumer = (ignored, c2sWholeBuf) -> {
            FriendlyByteBuf tmpBuf = new FriendlyByteBuf(Unpooled.wrappedBuffer(c2sWholeBuf.readByteArray()));
            try {
                ItemStack itemStack = tmpBuf.readLenientJsonWithCodec(ItemStack.OPTIONAL_CODEC);
                if (!player.getInventory().add(itemStack.copy())) {
                    player.sendSystemMessage(Component.translatable("text.rei.failed_cheat_items"), false);
                }
            } finally {
                tmpBuf.release();
            }
        };
        inboundTransform(player, CREATE_ITEMS_PACKET, buf, consumer);
    }

    @ProtocolHandler.BytebufReceiver(key = "create_item_grab")
    public static void handleCreateItemGrab(ServerPlayer player, RegistryFriendlyByteBuf buf) {
        if (!hasCheatPermission(player)) {
            return;
        }
        BiConsumer<Identifier, RegistryFriendlyByteBuf> consumer = (ignored, c2sWholeBuf) -> {
            FriendlyByteBuf tmpBuf = new FriendlyByteBuf(Unpooled.wrappedBuffer(c2sWholeBuf.readByteArray()));
            try {
                ItemStack stack = tmpBuf.readLenientJsonWithCodec(ItemStack.OPTIONAL_CODEC).copy();
                AbstractContainerMenu menu = player.containerMenu;
                if (!menu.getCarried().isEmpty() && ItemStack.isSameItemSameComponents(menu.getCarried(), stack)) {
                    stack.setCount(Mth.clamp(stack.getCount() + menu.getCarried().getCount(), 1, stack.getMaxStackSize()));
                } else if (!menu.getCarried().isEmpty()) {
                    return;
                }
                menu.setCarried(stack);
                menu.broadcastChanges();
            } finally {
                tmpBuf.release();
            }
        };
        inboundTransform(player, CREATE_ITEMS_GRAB_PACKET, buf, consumer);
    }

    @ProtocolHandler.BytebufReceiver(key = "create_item_hotbar")
    public static void handleCreateItemHotbar(ServerPlayer player, RegistryFriendlyByteBuf buf) {
        if (!hasCheatPermission(player)) {
            return;
        }
        BiConsumer<Identifier, RegistryFriendlyByteBuf> consumer = (ignored, c2sWholeBuf) -> {
            FriendlyByteBuf tmpBuf = new FriendlyByteBuf(Unpooled.wrappedBuffer(c2sWholeBuf.readByteArray()));
            try {
                ItemStack stack = tmpBuf.readLenientJsonWithCodec(ItemStack.OPTIONAL_CODEC);
                int hotbarSlotId = tmpBuf.readVarInt();
                if (hotbarSlotId >= 0 && hotbarSlotId < 9) {
                    player.getInventory().getNonEquipmentItems().set(hotbarSlotId, stack.copy());
                    player.containerMenu.broadcastChanges();
                } else {
                    player.sendSystemMessage(Component.translatable("text.rei.failed_cheat_items"), false);
                }
            } finally {
                tmpBuf.release();
            }
        };
        inboundTransform(player, CREATE_ITEMS_HOTBAR_PACKET, buf, consumer);
    }

    @ProtocolHandler.BytebufReceiver(key = "move_items_new")
    public static void handleMoveItem(ServerPlayer player, RegistryFriendlyByteBuf buf) {
        BiConsumer<Identifier, RegistryFriendlyByteBuf> consumer = (ignored, c2sWholeBuf) -> {
            FriendlyByteBuf tmpBuf = new FriendlyByteBuf(Unpooled.wrappedBuffer(c2sWholeBuf.readByteArray()));
            AbstractContainerMenu container = player.containerMenu;
            try {
                tmpBuf.readIdentifier();
                boolean shift = tmpBuf.readBoolean();
                try {
                    CompoundTag nbt = tmpBuf.readNbt();
                    if (nbt == null) {
                        throw new IllegalStateException("NBT data is null");
                    }
                    int version = nbt.getInt("Version").orElse(-1);
                    if (version != 1) {
                        throw new IllegalStateException("Server and client REI protocol version mismatch! Server: 1, Client: " + version);
                    }

                    Set<SlotKey> used = new HashSet<>();
                    List<SlotAccessor> input = readSlots(container, player, nbt.getListOrEmpty("InputSlots"), used, true);
                    List<SlotAccessor> inventory = readSlots(container, player, nbt.getListOrEmpty("InventorySlots"), used, false);
                    List<List<ItemStack>> recipes = readInputs(player.registryAccess(), nbt.getListOrEmpty("Inputs"), input.size());
                    NewInputSlotCrafter<AbstractContainerMenu> crafter = new NewInputSlotCrafter<>(container, input, inventory, recipes);
                    Bukkit.getScheduler().runTask(MinecraftInternalPlugin.INSTANCE, () -> {
                        try {
                            // Closing/replacing the menu invalidates this request even if the numeric container id is reused.
                            if (!LeavesConfig.protocol.reiServerProtocol || player.containerMenu != container
                                || player.isRemoved() || !player.isAlive() || player.isSpectator()
                                || !container.stillValid(player) || !container.getCarried().isEmpty()) return;
                            // Slot permissions may change between receipt and the scheduled task.
                            for (SlotAccessor slot : input) {
                                if (!slot.getItemStack().isEmpty() && !slot.allowModification(player)) return;
                            }
                            for (SlotAccessor slot : inventory) {
                                if (!slot.allowModification(player)) return;
                            }
                            crafter.fillInputSlots(player, shift);
                        } catch (InputSlotCrafter.NotEnoughMaterialsException ignored1) {
                        } catch (IllegalStateException e) {
                            player.sendSystemMessage(Component.translatable(e.getMessage()).withStyle(ChatFormatting.RED));
                        } catch (Exception e) {
                            player.sendSystemMessage(Component.translatable("error.rei.internal.error", e.getMessage()).withStyle(ChatFormatting.RED));
                            LeavesLogger.LOGGER.error("Failed to move items for player {}", player.getScoreboardName(), e);
                        }
                    });
                } catch (IllegalStateException e) {
                    player.sendSystemMessage(Component.translatable(e.getMessage()).withStyle(ChatFormatting.RED));
                } catch (Exception e) {
                    player.sendSystemMessage(Component.translatable("error.rei.internal.error", e.getMessage()).withStyle(ChatFormatting.RED));
                    LeavesLogger.LOGGER.error("Failed to move items for player {}", player.getScoreboardName(), e);
                }
            } catch (Exception e) {
                LeavesLogger.LOGGER.error("Failed to move items for player {}", player.getScoreboardName(), e);
            } finally {
                tmpBuf.release();
            }
        };
        inboundTransform(player, MOVE_ITEMS_NEW_PACKET, buf, consumer);
    }

    private static void inboundTransform(ServerPlayer player, Identifier id, RegistryFriendlyByteBuf buf, BiConsumer<Identifier, RegistryFriendlyByteBuf> consumer) {
        PacketTransformer transformer = TRANSFORMERS.get(id);
        if (transformer != null) {
            transformer.inbound(id, buf, player, consumer);
        } else {
            consumer.accept(id, buf);
        }
    }

    private static void outboundTransform(RegistryFriendlyByteBuf buf, BiConsumer<Identifier, RegistryFriendlyByteBuf> consumer) {
        PacketTransformer transformer = TRANSFORMERS.get(SYNC_DISPLAYS_PACKET);
        if (transformer != null) {
            transformer.outbound(SYNC_DISPLAYS_PACKET, buf, consumer);
        } else {
            consumer.accept(SYNC_DISPLAYS_PACKET, buf);
        }
    }

    private static boolean hasCheatPermission(ServerPlayer player) {
        if (player.getBukkitEntity().hasPermission(CHEAT_PERMISSION)) {
            return true;
        }
        player.sendSystemMessage(Component.translatable("text.rei.no_permission_cheat").withStyle(ChatFormatting.RED), false);
        return false;
    }

    @Override
    public boolean isActive() {
        return LeavesConfig.protocol.reiServerProtocol;
    }

    @Override
    public int tickerInterval(String tickerID) {
        return 200;
    }

    private static List<List<ItemStack>> readInputs(RegistryAccess registryAccess, ListTag tag, int slotCount) {
        if (tag.size() > slotCount) throw new IllegalStateException("Too many REI ingredients");
        List<List<ItemStack>> items = new ArrayList<>(java.util.Collections.nCopies(slotCount, List.of()));
        Set<Integer> used = new HashSet<>();
        for (Tag t : tag) {
            CompoundTag compoundTag = (CompoundTag) t;
            int index = compoundTag.getInt("Index").orElseThrow();
            if (index < 0 || index >= slotCount || !used.add(index)) throw new IllegalStateException("Invalid REI ingredient index");
            ListTag ingredientList = compoundTag.getListOrEmpty("Ingredient");
            if (ingredientList.size() > 256) throw new IllegalStateException("Too many REI ingredient alternatives");
            List<ItemStack> slotItems = new ArrayList<>();
            for (Tag ingredient : ingredientList) {
                CompoundTag ingredientTag = (CompoundTag) ingredient;
                ItemStack stack = ItemStack.OPTIONAL_CODEC.parse(
                    registryAccess.createSerializationContext(NbtOps.INSTANCE),
                    ingredientTag.get("value")
                ).getOrThrow();
                slotItems.add(stack);
            }
            items.set(index, slotItems);
        }
        return items;
    }

    private record SlotKey(net.minecraft.world.Container container, int slot) {}

    private static List<SlotAccessor> readSlots(AbstractContainerMenu menu, ServerPlayer player, ListTag tag,
                                               Set<SlotKey> used, boolean input) {
        if (tag.isEmpty() || tag.size() > (input ? 9 : 36)) throw new IllegalStateException("Invalid REI slot count");
        List<SlotAccessor> slots = new ArrayList<>();
        for (Tag t : tag) {
            CompoundTag compoundTag = (CompoundTag) t;
            String id = compoundTag.getString("id").orElseThrow();
            if (!id.startsWith(PROTOCOL_ID + ":")) {
                throw new IllegalStateException("Invalid slot id: " + id + ", expected to start with '" + PROTOCOL_ID + ":'");
            }
            id = id.substring((PROTOCOL_ID + ":").length());
            int slot = compoundTag.getInt("Slot").orElseThrow();
            net.minecraft.world.inventory.Slot actual = switch (id) {
                case "vanilla" -> slot < 0 || slot >= menu.slots.size() ? null : menu.slots.get(slot);
                case "player" -> menu.slots.stream().filter(candidate -> candidate.container == player.getInventory()
                    && candidate.getContainerSlot() == slot && slot >= 0 && slot < 36).findFirst().orElse(null);
                default -> throw new IllegalStateException("Unknown container id: " + id);
            };
            if (actual == null || actual.isFake() || !actual.mayPickup(player)
                || (input && !actual.getItem().isEmpty() && !actual.mayPlace(actual.getItem()))
                || (input ? actual.container == player.getInventory()
                          : actual.container != player.getInventory() || actual.getContainerSlot() < 0 || actual.getContainerSlot() >= 36)
                || !used.add(new SlotKey(actual.container, actual.getContainerSlot()))) {
                throw new IllegalStateException("Invalid or overlapping REI slots");
            }
            slots.add(new VanillaSlotAccessor(actual));
        }
        return slots;
    }
}
