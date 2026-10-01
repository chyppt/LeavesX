package org.leavesx.leavesx.network;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeManager;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;
import org.leavesmc.leaves.protocol.rei.REIServerProtocol;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Normal
class ReiRequestValidationTest {
    @Test
    void playerAndMenuAliasesCannotReferToTheSameInventorySlotTwice() throws Exception {
        final ServerPlayer player = mock(ServerPlayer.class);
        final Inventory inventory = new Inventory(player, new EntityEquipment());
        when(player.getInventory()).thenReturn(inventory);
        final var menu = new CraftingMenu(1, inventory);
        final var slots = new ListTag();
        slots.add(slot("vanilla", 10));
        slots.add(slot("player", menu.getSlot(10).getContainerSlot()));
        assertRejected(menu, player, slots, false);
    }

    @Test
    void furnaceOutputCannotBeClearedAsAnInputWithoutItsTakeCallback() throws Exception {
        final ServerPlayer player = mock(ServerPlayer.class);
        final ServerLevel level = mock(ServerLevel.class);
        when(player.level()).thenReturn(level);
        when(level.recipeAccess()).thenReturn(mock(RecipeManager.class));
        final Inventory inventory = new Inventory(player, new EntityEquipment());
        when(player.getInventory()).thenReturn(inventory);
        final var menu = new FurnaceMenu(1, inventory);
        menu.getSlot(2).set(new ItemStack(Items.IRON_INGOT, 8));
        final var slots = new ListTag();
        slots.add(slot("vanilla", 2));
        assertRejected(menu, player, slots, true);
        assertEquals(8, menu.getSlot(2).getItem().getCount());
    }

    private static CompoundTag slot(String kind, int index) {
        final var tag = new CompoundTag();
        tag.putString("id", "roughlyenoughitems:" + kind);
        tag.putInt("Slot", index);
        return tag;
    }

    private static void assertRejected(AbstractContainerMenu menu, ServerPlayer player, ListTag slots, boolean input) throws Exception {
        final Method read = REIServerProtocol.class.getDeclaredMethod("readSlots", AbstractContainerMenu.class,
            ServerPlayer.class, ListTag.class, Set.class, boolean.class);
        read.setAccessible(true);
        final var failure = assertThrows(InvocationTargetException.class,
            () -> read.invoke(null, menu, player, slots, new HashSet<>(), input));
        assertInstanceOf(IllegalStateException.class, failure.getCause());
    }
}
