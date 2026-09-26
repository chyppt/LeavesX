package org.leavesx.leavesx.gameplay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.support.environment.VanillaFeature;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@VanillaFeature
class EntityAiToolTest {
    @Test
    void signedToolTogglesAiButNameAndPermissionsCannotForgeIt() {
        final Player op = mock(Player.class);
        final PlayerInventory inventory = mock(PlayerInventory.class);
        when(op.isOp()).thenReturn(true);
        when(op.getInventory()).thenReturn(inventory);
        when(inventory.addItem(any(ItemStack.class))).thenReturn(new java.util.HashMap<>());
        EntityAiTool.give(op);
        final var captor = ArgumentCaptor.forClass(ItemStack.class);
        verify(inventory).addItem(captor.capture());
        final ItemStack tool = captor.getValue();
        final Mob mob = mock(Mob.class);
        when(mob.getNavigation()).thenReturn(mock(PathNavigation.class));
        assertTrue(EntityAiTool.interact(op, mob, tool));
        verify(mob).setNoAi(true);
        when(mob.isNoAi()).thenReturn(true);
        assertTrue(EntityAiTool.interact(op, mob, tool));
        verify(mob).setNoAi(false);
        clearInvocations(mob);
        when(op.isOp()).thenReturn(false);
        assertTrue(EntityAiTool.interact(op, mob, tool));
        verifyNoInteractions(mob);
        final var forged = new ItemStack(Material.STICK);
        forged.editMeta(meta -> meta.displayName(net.kyori.adventure.text.Component.text("禁用实体AI")));
        assertFalse(EntityAiTool.interact(op, mob, forged));
    }
}
