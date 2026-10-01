package org.leavesx.leavesx.network;

import io.netty.buffer.Unpooled;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.stats.ServerRecipeBook;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeSerializer;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.plugin.PluginManager;
import org.bukkit.support.RegistryHelper;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;
import org.leavesx.leavesx.config.LeavesXConfigLoader;
import org.leavesx.leavesx.config.LeavesXConfigWriter;
import org.leavesx.leavesx.protocol.JeiRecipeSync;
import org.leavesx.leavesx.protocol.JeiTransferRequest;
import org.leavesx.leavesx.protocol.RecipeBookTransfer;
import org.spongepowered.configurate.CommentedConfigurationNode;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Normal
class JeiProtocolTest {
    @Test
    void bothLoaderFormatsDecodeAllVanillaRecipesWithoutLosingIds() {
        final var recipes = RegistryHelper.context().datapack().getRecipeManager().getRecipes();
        final Set<Object> expected = new HashSet<>(recipes.stream().map(RecipeHolder::id).toList());
        for (Identifier channel : List.of(JeiRecipeSync.FABRIC, JeiRecipeSync.NEOFORGE)) {
            final var payload = JeiRecipeSync.encode(channel, recipes, RegistryHelper.registryAccess());
            final var buffer = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(payload.data()), RegistryHelper.registryAccess());
            final Set<Object> actual = new HashSet<>();
            try {
                if (channel.equals(JeiRecipeSync.FABRIC)) {
                    int groups = buffer.readVarInt();
                    for (int group = 0; group < groups; group++) {
                        final RecipeSerializer<?> serializer = BuiltInRegistries.RECIPE_SERIALIZER.getValue(buffer.readIdentifier());
                        assertNotNull(serializer);
                        final int size = buffer.readVarInt();
                        for (int index = 0; index < size; index++) {
                            assertTrue(actual.add(buffer.readResourceKey(Registries.RECIPE)), "No duplicate recipes");
                            assertNotNull(serializer.streamCodec().decode(buffer));
                        }
                    }
                } else {
                    final int types = buffer.readVarInt();
                    for (int index = 0; index < types; index++) assertNotNull(BuiltInRegistries.RECIPE_TYPE.byId(buffer.readVarInt()));
                    final int size = buffer.readVarInt();
                    for (int index = 0; index < size; index++) assertTrue(actual.add(RecipeHolder.STREAM_CODEC.decode(buffer).id()));
                }
                assertFalse(buffer.isReadable());
                assertEquals(expected, actual);
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void codecSupportsAllFourTransferFormatsAndRejectsTrailingBytes() {
        for (boolean counted : new boolean[] {false, true}) {
            for (boolean result : new boolean[] {false, true}) {
                final var buffer = buffer();
                try {
                    buffer.writeVarInt(1).writeVarInt(10).writeVarInt(1);
                    if (counted) buffer.writeVarInt(1);
                    buffer.writeVarInt(1).writeVarInt(1);
                    buffer.writeVarInt(1).writeVarInt(10);
                    buffer.writeBoolean(true).writeBoolean(true);
                    if (result) buffer.writeVarInt(123);
                    final int end = buffer.writerIndex();
                    final var request = JeiTransferRequest.read(buffer, counted, result);
                    assertEquals(new JeiTransferRequest.Operation(10, 1, 1), request.operations().getFirst());
                    assertEquals(result ? 123 : -1, request.transferId());
                    buffer.readerIndex(0);
                    buffer.writeByte(0);
                    assertThrows(IllegalArgumentException.class, () -> JeiTransferRequest.read(buffer, counted, result));
                    buffer.setIndex(0, end - 1);
                    assertThrows(RuntimeException.class, () -> JeiTransferRequest.read(buffer, counted, result));
                } finally {
                    buffer.release();
                }
            }
        }
    }

    @Test
    void unboundedOrDuplicateSlotListsAreRejected() {
        final var buffer = buffer();
        try {
            buffer.writeVarInt(Integer.MAX_VALUE);
            assertThrows(IllegalArgumentException.class, () -> JeiTransferRequest.read(buffer, false, false));
            buffer.clear();
            buffer.writeVarInt(1).writeVarInt(10).writeVarInt(1);
            buffer.writeVarInt(2).writeVarInt(1).writeVarInt(1);
            assertThrows(IllegalArgumentException.class, () -> JeiTransferRequest.read(buffer, false, false));
        } finally {
            buffer.release();
        }
    }

    @Test
    void ordinaryAndMaximumTransfersConserveIngredients() {
        for (boolean maximum : new boolean[] {false, true}) {
            final Fixture fixture = new Fixture();
            final PluginManager plugins = mock(PluginManager.class);
            try (var bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
                bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
                assertTrue(RecipeBookTransfer.transfer(fixture.player, fixture.request(maximum)));
                assertEquals(16, fixture.logCount());
                assertEquals(maximum ? 16 : 1, fixture.menu.getSlot(1).getItem().getCount());
                verify(plugins).callEvent(isA(com.destroystokyo.paper.event.player.PlayerRecipeBookClickEvent.class));
            }
        }
    }

    @Test
    void cancelledEventLeavesAllSlotsUntouched() {
        final Fixture fixture = new Fixture();
        final PluginManager plugins = mock(PluginManager.class);
        doAnswer(invocation -> {
            ((com.destroystokyo.paper.event.player.PlayerRecipeBookClickEvent) invocation.getArgument(0)).setCancelled(true);
            return null;
        }).when(plugins).callEvent(any());
        try (var bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
            assertFalse(RecipeBookTransfer.transfer(fixture.player, fixture.request(false)));
            assertEquals(16, fixture.menu.getSlot(10).getItem().getCount());
            assertTrue(fixture.menu.getSlot(1).getItem().isEmpty());
        }
    }

    @Test
    void pluginClosingMenuPreventsOldMenuPlacement() {
        final Fixture fixture = new Fixture();
        final PluginManager plugins = mock(PluginManager.class);
        doAnswer(invocation -> {
            fixture.player.containerMenu = new CraftingMenu(2, fixture.inventory);
            return null;
        }).when(plugins).callEvent(any());
        try (var bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
            assertFalse(RecipeBookTransfer.transfer(fixture.player, fixture.request(false)));
            assertEquals(16, fixture.menu.getSlot(10).getItem().getCount());
            assertTrue(fixture.menu.getSlot(1).getItem().isEmpty());
        }
    }

    @Test
    void forgedOutputSlotsAndEmptyIngredientSourcesAreRejected() {
        final Fixture fixture = new Fixture();
        final var request = fixture.request(false);
        assertFalse(RecipeBookTransfer.transfer(fixture.player, new JeiTransferRequest(
            List.of(new JeiTransferRequest.Operation(10, 0, 1)), List.of(0), request.inventorySlots(), false, true, -1)));
        assertFalse(RecipeBookTransfer.transfer(fixture.player, new JeiTransferRequest(
            List.of(new JeiTransferRequest.Operation(11, 1, 1)), request.craftingSlots(), request.inventorySlots(), false, true, -1)));
        assertEquals(16, fixture.logCount());
    }

    @Test
    void fullInventoryCannotDropOrDeleteExistingGridItems() {
        final Fixture fixture = new Fixture();
        for (int index = 11; index < 46; index++) fixture.menu.getSlot(index).set(new ItemStack(Items.STONE, 64));
        fixture.menu.getSlot(1).set(new ItemStack(Items.IRON_INGOT));
        final List<ItemStack> before = fixture.menu.slots.stream().map(slot -> slot.getItem().copy()).toList();
        try (var bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
            assertFalse(RecipeBookTransfer.transfer(fixture.player, fixture.request(true)));
            for (int index = 0; index < before.size(); index++) {
                assertTrue(ItemStack.matches(before.get(index), fixture.menu.getSlot(index).getItem()), "Changed slot " + index);
            }
        }
    }

    @Test
    void lockedRecipesAndPluginRedirectToLockedRecipesAreRejected() {
        final Fixture fixture = new Fixture();
        when(fixture.player.getRecipeBook().contains(any())).thenReturn(false);
        assertFalse(RecipeBookTransfer.transfer(fixture.player, fixture.request(false)));
        when(fixture.player.getRecipeBook().contains(any())).thenReturn(true, false);
        try (var bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
            assertFalse(RecipeBookTransfer.transfer(fixture.player, fixture.request(false)));
        }
        assertEquals(16, fixture.menu.getSlot(10).getItem().getCount());
        assertTrue(fixture.menu.getSlot(1).getItem().isEmpty());
    }

    @Test
    void malformedVarIntsAreRejectedByProtocolWithoutInventoryAccess() {
        final var player = mock(ServerPlayer.class);
        final var buffer = buffer();
        try {
            buffer.writeBytes(new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x01});
            assertDoesNotThrow(() -> org.leavesmc.leaves.protocol.jei.JEIServerProtocol.transfer(player, buffer));
            verifyNoInteractions(player);
            assertEquals(1, buffer.refCnt());
        } finally {
            buffer.release();
        }
    }

    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryHelper.registryAccess());
    }

    private static final class Fixture {
        final ServerPlayer player = mock(ServerPlayer.class);
        final Inventory inventory = new Inventory(player, new EntityEquipment());
        final CraftingMenu menu;

        Fixture() {
            final ServerLevel level = mock(ServerLevel.class);
            final MinecraftServer server = mock(MinecraftServer.class);
            when(player.level()).thenReturn(level);
            when(player.getInventory()).thenReturn(inventory);
            when(player.getBukkitEntity()).thenReturn(mock(CraftPlayer.class));
            when(player.isAlive()).thenReturn(true);
            when(level.getServer()).thenReturn(server);
            when(server.getRecipeManager()).thenReturn(RegistryHelper.context().datapack().getRecipeManager());
            final ServerRecipeBook book = mock(ServerRecipeBook.class);
            when(book.contains(any())).thenReturn(true);
            when(player.getRecipeBook()).thenReturn(book);
            menu = spy(new CraftingMenu(1, inventory));
            menu.checkReachable = false;
            // Only output broadcasting is stubbed. Inventory placement uses the real vanilla implementation.
            doNothing().when(menu).finishPlacingRecipe(any(), any());
            player.containerMenu = menu;
            menu.getSlot(10).set(new ItemStack(Items.OAK_LOG, 16));
        }

        JeiTransferRequest request(boolean maximum) {
            return new JeiTransferRequest(List.of(new JeiTransferRequest.Operation(10, 1, 1)),
                IntStream.rangeClosed(1, 9).boxed().toList(), IntStream.range(10, 46).boxed().toList(), maximum, true, -1);
        }

        int logCount() {
            return menu.slots.stream().map(slot -> slot.getItem()).filter(stack -> stack.is(Items.OAK_LOG)).mapToInt(ItemStack::getCount).sum();
        }
    }
}
