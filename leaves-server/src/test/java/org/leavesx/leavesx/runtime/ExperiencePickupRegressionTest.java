package org.leavesx.leavesx.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerExpCooldownChangeEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;
import org.leavesmc.leaves.LeavesConfig;

/** 从真实拾取入口验证经验授予，不把装备修补或插件取消误当作经验丢失。 */
@Normal
public class ExperiencePickupRegressionTest {
    @Test
    void normalAndFastPickupAwardEveryUnspentOrb() {
        for (final boolean fast : new boolean[]{false, true}) {
            final var previous = LeavesConfig.modify.fastAbsorbPredicate;
            LeavesConfig.modify.fastAbsorbPredicate = player -> fast;
            try (var bukkit = mockStatic(Bukkit.class);
                 var events = mockStatic(CraftEventFactory.class);
                 var enchantments = mockStatic(EnchantmentHelper.class)) {
                bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
                final ServerPlayer player = mock(ServerPlayer.class);
                final CraftPlayer craft = mock(CraftPlayer.class);
                when(player.getBukkitEntity()).thenReturn(craft);
                final ExperienceOrb orb = mock(ExperienceOrb.class);
                when(orb.getBukkitEntity()).thenReturn(mock(org.bukkit.craftbukkit.entity.CraftExperienceOrb.class));
                when(orb.getValue()).thenReturn(5);
                orb.count = 3;
                doCallRealMethod().when(orb).playerTouch(player);
                events.when(() -> CraftEventFactory.callPlayerXpCooldownEvent(eq(player), anyInt(), any()))
                    .thenAnswer(call -> new PlayerExpCooldownChangeEvent(craft, call.getArgument(1), call.getArgument(2)));
                events.when(() -> CraftEventFactory.callPlayerExpChangeEvent(player, orb, 5))
                    .thenReturn(new PlayerExpChangeEvent(craft, 5));
                enchantments.when(() -> EnchantmentHelper.getRandomItemWith(any(), eq(player), any()))
                    .thenReturn(Optional.empty());
                orb.playerTouch(player);
                verify(player, times(fast ? 3 : 1)).giveExperiencePoints(5);
                assertEquals(fast ? 0 : 2, orb.count);
                assertEquals(fast ? 0 : 2, player.takeXpDelay);
            } finally {
                LeavesConfig.modify.fastAbsorbPredicate = previous;
            }
        }
    }
}
