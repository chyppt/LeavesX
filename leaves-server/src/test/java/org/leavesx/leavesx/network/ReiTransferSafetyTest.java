package org.leavesx.leavesx.network;

import java.util.List;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;
import org.leavesmc.leaves.protocol.rei.transfer.NewInputSlotCrafter;
import org.leavesmc.leaves.protocol.rei.transfer.slot.SlotAccessor;
import static org.junit.jupiter.api.Assertions.*;

@Normal
class ReiTransferSafetyTest {
    @Test
    void rejectingDestinationDoesNotConsumeSource() {
        final var source = new Accessor(new ItemStack(Items.IRON_INGOT, 4), true);
        final var target = new Accessor(ItemStack.EMPTY, false);
        new Crafter(source).move(target);
        assertEquals(4, source.stack.getCount());
        assertTrue(target.stack.isEmpty());
    }

    @Test
    void fullDestinationDoesNotConsumeSource() {
        final var source = new Accessor(new ItemStack(Items.IRON_INGOT, 4), true);
        final var target = new Accessor(new ItemStack(Items.IRON_INGOT, 64), true);
        new Crafter(source).move(target);
        assertEquals(4, source.stack.getCount());
        assertEquals(64, target.stack.getCount());
    }

    @Test
    void differentDestinationDoesNotConvertItems() {
        final var source = new Accessor(new ItemStack(Items.IRON_INGOT, 4), true);
        final var target = new Accessor(new ItemStack(Items.DIAMOND, 1), true);
        new Crafter(source).move(target);
        assertEquals(4, source.stack.getCount());
        assertEquals(1, target.stack.getCount());
    }

    private static class Crafter extends NewInputSlotCrafter<AbstractContainerMenu> {
        private final Accessor source;
        Crafter(Accessor source) {
            super(null, List.of(), List.of(), List.of());
            this.source = source;
        }
        @Override public SlotAccessor takeInventoryStack(ItemStack stack) { return source; }
        void move(Accessor target) { fillInputSlot(target, source.stack); }
    }

    private static class Accessor implements SlotAccessor {
        ItemStack stack;
        final boolean accepts;
        Accessor(ItemStack stack, boolean accepts) { this.stack = stack; this.accepts = accepts; }
        @Override public ItemStack getItemStack() { return stack; }
        @Override public void setItemStack(ItemStack stack) { this.stack = stack; }
        @Override public void takeStack(int amount) { stack.shrink(amount); }
        @Override public boolean canPlace(ItemStack stack) { return accepts; }
    }
}
