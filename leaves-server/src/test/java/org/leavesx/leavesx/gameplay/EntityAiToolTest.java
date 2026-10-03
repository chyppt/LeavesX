package org.leavesx.leavesx.gameplay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
    void inactiveTradeOnlyVillagerSkipsGoalSelectors() {
        final Villager villager = inactiveVillager(true);
        final ServerLevel level = villager.level().getMinecraftWorld();
        villager.inactiveTick();
        verify(villager).leavesX$tickTradeOnly(level);
        verifyNoInteractions(villager.goalSelector, villager.targetSelector);
    }

    @Test
    void inactiveOrdinaryVillagerKeepsGoalSelectors() {
        final Villager villager = inactiveVillager(false);
        when(villager.goalSelector.inactiveTick()).thenReturn(true);
        when(villager.targetSelector.inactiveTick()).thenReturn(true);
        villager.inactiveTick();
        verify(villager, never()).leavesX$tickTradeOnly(any());
        verify(villager.goalSelector).tick();
        verify(villager.targetSelector).tick();
    }

    private static Villager inactiveVillager(final boolean tradeOnly) {
        // 只替换外部依赖，执行 Villager -> AgeableMob -> Mob 的真实非活跃调用链。
        final Villager villager = mock(Villager.class);
        final ServerLevel level = mock(ServerLevel.class);
        when(level.getMinecraftWorld()).thenReturn(level);
        when(villager.level()).thenReturn(level);
        when(villager.leavesX$isTradeOnly()).thenReturn(tradeOnly);
        // Villager 的入口读取真实字段，Mob 的保护通过方法读取同一状态。
        try {
            final var field = Villager.class.getDeclaredField("leavesX$tradeOnly");
            field.setAccessible(true);
            field.setBoolean(villager, tradeOnly);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
        villager.aware = true;
        villager.goalSelector = mock(GoalSelector.class);
        villager.targetSelector = mock(GoalSelector.class);
        doCallRealMethod().when(villager).inactiveTick();
        return villager;
    }

    @Test
    void villagerUsesTradeOnlyToggleAndSameMessages() {
        final Player op = mock(Player.class);
        final PlayerInventory inventory = mock(PlayerInventory.class);
        when(op.isOp()).thenReturn(true);
        when(op.getInventory()).thenReturn(inventory);
        when(inventory.addItem(any(ItemStack.class))).thenReturn(new java.util.HashMap<>());
        EntityAiTool.give(op);
        final var item = ArgumentCaptor.forClass(ItemStack.class);
        verify(inventory).addItem(item.capture());
        final Villager villager = mock(Villager.class);
        assertTrue(EntityAiTool.interact(op, villager, item.getValue()));
        verify(villager).leavesX$setTradeOnly(true);
        verify(villager, never()).setNoAi(anyBoolean());
        verify(op).sendMessage(Component.text("已禁用实体 AI。", NamedTextColor.GREEN));
        when(villager.leavesX$isTradeOnly()).thenReturn(true);
        assertTrue(EntityAiTool.interact(op, villager, item.getValue()));
        verify(villager).leavesX$setTradeOnly(false);
        verify(op).sendMessage(Component.text("已启用实体 AI。", NamedTextColor.GREEN));
        clearInvocations(villager);
        when(op.getGameMode()).thenReturn(org.bukkit.GameMode.SPECTATOR);
        assertTrue(EntityAiTool.interact(op, villager, item.getValue()));
        verifyNoInteractions(villager);
        final ItemStack oldTool = item.getValue().clone();
        oldTool.setType(Material.STICK);
        assertFalse(EntityAiTool.interact(op, villager, oldTool));
        verifyNoInteractions(villager);
    }
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
        assertEquals(Material.WOODEN_SHOVEL, tool.getType());
        final var forged = new ItemStack(Material.WOODEN_SHOVEL);
        forged.editMeta(meta -> meta.displayName(net.kyori.adventure.text.Component.text("禁用实体AI")));
        assertFalse(EntityAiTool.interact(op, mob, forged));
    }
}
