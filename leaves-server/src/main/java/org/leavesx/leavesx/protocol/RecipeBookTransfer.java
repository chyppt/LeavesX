package org.leavesx.leavesx.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.craftbukkit.util.CraftNamespacedKey;

/** Resolves client ingredient hints against server recipes, then uses the ordinary recipe-book placement path. */
public final class RecipeBookTransfer {
    private RecipeBookTransfer() {}

    public static List<Integer> inputSlots(AbstractContainerMenu menu) {
        // Exact classes exclude plugin-defined virtual containers and their arbitrary slot callbacks.
        if (menu.getClass() == CraftingMenu.class || menu.getClass() == InventoryMenu.class) {
            return ((AbstractCraftingMenu) menu).getInputGridSlots().stream().map(slot -> slot.index).toList();
        }
        return cookingType(menu) == null ? List.of() : List.of(0);
    }

    public static boolean transfer(ServerPlayer player, JeiTransferRequest request) {
        final AbstractContainerMenu menu = player.containerMenu;
        if (player.isSpectator() || !player.isAlive() || !menu.stillValid(player) || !menu.getCarried().isEmpty()) return false;
        final List<Integer> inputs = inputSlots(menu);
        if (inputs.isEmpty() || !request.craftingSlots().equals(inputs)) return false;
        for (int slotId : request.inventorySlots()) {
            if (slotId < 0 || slotId >= menu.slots.size()) return false;
            final Slot slot = menu.getSlot(slotId);
            if (slot.container != player.getInventory() || slot.getContainerSlot() < 0 || slot.getContainerSlot() >= 36
                || !slot.mayPickup(player) || inputs.contains(slotId)) return false;
        }
        final List<ItemStack> proposed = new ArrayList<>(Collections.nCopies(inputs.size(), ItemStack.EMPTY));
        for (final var operation : request.operations()) {
            final int target = inputs.indexOf(operation.target());
            if (target < 0 || operation.source() < 0 || operation.source() >= menu.slots.size()
                || (!inputs.contains(operation.source()) && !request.inventorySlots().contains(operation.source()))) return false;
            final Slot source = menu.getSlot(operation.source());
            final ItemStack stack = source.getItem();
            if (stack.isEmpty() || !source.mayPickup(player) || operation.count() != 1
                || !menu.getSlot(operation.target()).mayPlace(stack) || !proposed.get(target).isEmpty()) return false;
            proposed.set(target, stack.copyWithCount(1));
        }
        final RecipeHolder<?> recipe = findRecipe(player, menu, proposed);
        return recipe != null && place(player, menu, recipe, request.maximum());
    }

    public static RecipeHolder<?> findRecipe(ServerPlayer player, AbstractContainerMenu menu, List<ItemStack> inputs) {
        final RecipeManager manager = player.level().getServer().getRecipeManager();
        if (menu instanceof AbstractCraftingMenu crafting && inputs.size() == crafting.getGridWidth() * crafting.getGridHeight()) {
            return manager.getRecipeFor(RecipeType.CRAFTING,
                CraftingInput.of(crafting.getGridWidth(), crafting.getGridHeight(), inputs), player.level()).orElse(null);
        }
        final RecipeType<? extends AbstractCookingRecipe> type = cookingType(menu);
        return type == null || inputs.size() != 1 ? null
            : manager.getRecipeFor(type, new SingleRecipeInput(inputs.getFirst()), player.level()).orElse(null);
    }

    public static boolean place(ServerPlayer player, AbstractContainerMenu expected, RecipeHolder<?> recipe, boolean maximum) {
        if (!(expected instanceof RecipeBookMenu menu) || inputSlots(expected).isEmpty()
            || player.containerMenu != expected || player.isSpectator() || !player.isAlive() || !expected.stillValid(player)
            || !player.getRecipeBook().contains(recipe.id()) || !accepts(menu, recipe)) return false;

        // Match Paper's recipe-book events. Plugins can veto/replace the recipe or close the menu here.
        final var event = new com.destroystokyo.paper.event.player.PlayerRecipeBookClickEvent(
            player.getBukkitEntity(), CraftNamespacedKey.fromMinecraft(recipe.id().identifier()), maximum);
        if (!event.callEvent()) return false;
        var key = event.getRecipe();
        maximum = event.isMakeAll();
        if (org.bukkit.event.player.PlayerRecipeBookClickEvent.getHandlerList().getRegisteredListeners().length > 0) {
            final var bukkitRecipe = org.bukkit.Bukkit.getRecipe(key);
            if (bukkitRecipe == null) return false;
            final var legacy = CraftEventFactory.callRecipeBookClickEvent(player, bukkitRecipe, maximum);
            if (!(legacy.getRecipe() instanceof org.bukkit.Keyed keyed)) return false;
            key = keyed.getKey();
            maximum = legacy.isShiftClick();
        }
        recipe = player.level().getServer().getRecipeManager().byKey(
            ResourceKey.create(Registries.RECIPE, CraftNamespacedKey.toMinecraft(key))).orElse(null);
        if (recipe == null || player.containerMenu != expected || !expected.stillValid(player)
            || player.isSpectator() || !player.isAlive() || !expected.getCarried().isEmpty()
            || !player.getRecipeBook().contains(recipe.id()) || !accepts(menu, recipe)) return false;
        // Never allow clearing the grid to drop items, even for creative players.
        final var action = menu.handlePlacement(maximum, false, recipe, player.level(), player.getInventory());
        menu.broadcastChanges();
        if (action == RecipeBookMenu.PostPlaceAction.PLACE_GHOST_RECIPE || player.containerMenu != menu) return false;
        if (menu instanceof AbstractCraftingMenu crafting && recipe.value() instanceof CraftingRecipe craftingRecipe) {
            return craftingRecipe.matches(crafting.craftSlots.asCraftInput(), player.level());
        }
        return recipe.value() instanceof AbstractCookingRecipe cooking
            && cooking.matches(new SingleRecipeInput(menu.getSlot(0).getItem()), player.level());
    }

    private static boolean accepts(AbstractContainerMenu menu, RecipeHolder<?> recipe) {
        return !recipe.value().placementInfo().isImpossibleToPlace()
            && recipe.value().getType() == (menu instanceof AbstractCraftingMenu ? RecipeType.CRAFTING : cookingType(menu));
    }

    private static RecipeType<? extends AbstractCookingRecipe> cookingType(AbstractContainerMenu menu) {
        if (menu.getClass() == FurnaceMenu.class) return RecipeType.SMELTING;
        if (menu.getClass() == BlastFurnaceMenu.class) return RecipeType.BLASTING;
        if (menu.getClass() == SmokerMenu.class) return RecipeType.SMOKING;
        return null;
    }
}
