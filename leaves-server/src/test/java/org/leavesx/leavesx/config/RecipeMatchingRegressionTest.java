package org.leavesx.leavesx.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.MapPostProcessing;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeMap;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.block.entity.BannerPatterns;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.bukkit.Material;
import org.bukkit.craftbukkit.inventory.CraftRecipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.support.RegistryHelper;
import org.bukkit.support.environment.VanillaFeature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.CommentedConfigurationNode;

/** Exercises real matching, including the live vanilla recipe set, rather than empty index buckets. */
@VanillaFeature
class RecipeMatchingRegressionTest {
    private LeavesXConfig previousConfig;
    private LeavesXConfig indexedConfig;
    private LeavesXConfig originalConfig;
    private Level level;

    @BeforeEach
    void setUp() {
        this.previousConfig = LeavesXRuntime.configuration();
        final CommentedConfigurationNode node = CommentedConfigurationNode.root();
        node.node("performance", "crafting", "recipe-index").raw(true);
        this.indexedConfig = LeavesXConfigLoader.fromNode(node, Path.of("recipe-test.yml"));
        node.node("performance", "crafting", "recipe-index").raw(false);
        this.originalConfig = LeavesXConfigLoader.fromNode(node, Path.of("recipe-test.yml"));
        this.level = mock(Level.class);
    }

    @AfterEach
    void restoreConfig() {
        LeavesXRuntime.publish(this.previousConfig);
    }

    @Test
    void woodenInputsKeepTheirVanillaResults() {
        final RecipeManager manager = vanillaManager();
        assertResult(manager, grid(1, 1, Items.OAK_PLANKS), Items.OAK_BUTTON, 1);
        assertResult(manager, grid(2, 2, Items.OAK_PLANKS, Items.OAK_PLANKS,
            Items.OAK_PLANKS, Items.OAK_PLANKS), Items.CRAFTING_TABLE, 1);
        assertResult(manager, grid(1, 1, Items.OAK_LOG), Items.OAK_PLANKS, 4);
        assertResult(manager, grid(1, 1, Items.PALE_OAK_LOG), Items.PALE_OAK_PLANKS, 4);
        assertResult(manager, grid(1, 2, Items.OAK_PLANKS, Items.OAK_PLANKS), Items.STICK, 4);
        assertResult(manager, grid(2, 1, Items.OAK_PLANKS, Items.OAK_PLANKS), Items.OAK_PRESSURE_PLATE, 1);
    }

    @Test
    void wrongMaterialsAndWrongShapesProduceNoRecipe() {
        final RecipeManager manager = manager(List.of(vanillaRecipe("stick")));
        assertTrue(compare(manager, grid(2, 1, Items.OAK_PLANKS, Items.OAK_PLANKS)).isEmpty());
        assertTrue(compare(manager, grid(1, 2, Items.DIRT, Items.DIRT)).isEmpty());
        assertTrue(compare(manager, CraftingInput.EMPTY).isEmpty());
    }

    @Test
    void lastMatchingRecipeWinsAcrossSpecialAndCountBuckets() {
        final RecipeHolder<?> custom = shapeless("custom", Items.STICK, Ingredient.of(Items.OAK_LOG));
        final RecipeHolder<?> mismatch = shapeless("mismatch", Items.DIAMOND, Ingredient.of(Items.DIRT));
        final RecipeHolder<?> special = vanillaRecipe("firework_rocket");
        final RecipeManager manager = manager(List.of(vanillaRecipe("oak_planks"), special, custom, mismatch));
        assertEquals(custom.id(), compare(manager, grid(1, 1, Items.OAK_LOG)).orElseThrow().id());
        assertResult(manager, grid(2, 1, Items.PAPER, Items.GUNPOWDER), Items.FIREWORK_ROCKET, 3);
    }

    @Test
    void matchingSpecialAndIndexedRecipesKeepRegistrationPriority() {
        final RecipeHolder<?> custom = shapeless("custom_rocket", Items.STICK,
            Ingredient.of(Items.PAPER), Ingredient.of(Items.GUNPOWDER));
        final RecipeHolder<?> special = vanillaRecipe("firework_rocket");
        final CraftingInput input = grid(2, 1, Items.PAPER, Items.GUNPOWDER);
        assertResult(manager(List.of(custom, special)), input, Items.FIREWORK_ROCKET, 3);
        assertResult(manager(List.of(special, custom)), input, Items.STICK, 1);
    }

    @Test
    void emptyGridNeverInvokesCustomRecipeMatching() {
        final CraftingRecipe custom = mock(CraftingRecipe.class);
        when(custom.getType()).thenReturn(RecipeType.CRAFTING);
        when(custom.matches(CraftingInput.EMPTY, this.level)).thenReturn(true);
        final RecipeHolder<?> holder = new RecipeHolder<>(
            ResourceKey.create(Registries.RECIPE, Identifier.fromNamespaceAndPath("leavesx_test", "empty")), custom);
        assertTrue(compare(manager(List.of(holder)), CraftingInput.EMPTY).isEmpty());
        verify(custom, never()).matches(CraftingInput.EMPTY, this.level);
    }

    @Test
    void specialRecipesStillMatchAndRejectInvalidMaterials() {
        final RecipeManager manager = vanillaManager();
        assertResult(manager, grid(2, 1, Items.PAPER, Items.GUNPOWDER), Items.FIREWORK_ROCKET, 3);
        assertResult(manager, grid(2, 2, Items.PAPER, Items.GUNPOWDER,
            Items.GUNPOWDER, Items.GUNPOWDER), Items.FIREWORK_ROCKET, 3);
        assertResult(manager, grid(2, 1, Items.LEATHER_CHESTPLATE, Items.DYE.pick(DyeColor.RED)), Items.LEATHER_CHESTPLATE, 1);
        final ItemStack first = new ItemStack(Items.IRON_PICKAXE);
        first.setDamageValue(100);
        final ItemStack second = new ItemStack(Items.IRON_PICKAXE);
        second.setDamageValue(150);
        final CraftingInput repair = CraftingInput.of(2, 1, List.of(first, second));
        assertResult(manager, repair, Items.IRON_PICKAXE, 1);
        assertResult(manager, grid(3, 3, Items.AIR, Items.BRICK, Items.AIR,
            Items.BRICK, Items.AIR, Items.BRICK, Items.AIR, Items.BRICK, Items.AIR), Items.DECORATED_POT, 1);
        assertTrue(compare(manager, grid(2, 1, Items.DIRT, Items.PAPER)).isEmpty());
    }

    @Test
    void bukkitExactChoiceStillChecksItemComponents() {
        final org.bukkit.inventory.ItemStack named = new org.bukkit.inventory.ItemStack(Material.OAK_PLANKS);
        final var meta = named.getItemMeta();
        meta.displayName(net.kyori.adventure.text.Component.text("Recipe token"));
        named.setItemMeta(meta);
        final Ingredient exact = CraftRecipe.toIngredient(new RecipeChoice.ExactChoice(named), true);
        final RecipeManager manager = manager(List.of(shapeless("exact", Items.DIAMOND, exact)));
        assertTrue(compare(manager, grid(1, 1, Items.OAK_PLANKS)).isEmpty());
        final ItemStack token = new ItemStack(Items.OAK_PLANKS);
        token.set(DataComponents.CUSTOM_NAME, Component.literal("Recipe token"));
        assertResult(manager, CraftingInput.of(1, 1, List.of(token)), Items.DIAMOND, 1);
    }

    @Test
    void bookCopyingPreservesGenerationAndRemainder() {
        final RecipeManager manager = vanillaManager();
        final ItemStack source = new ItemStack(Items.WRITTEN_BOOK);
        source.set(DataComponents.WRITTEN_BOOK_CONTENT, WrittenBookContent.EMPTY);
        final CraftingInput input = CraftingInput.of(2, 1, List.of(source, new ItemStack(Items.WRITABLE_BOOK)));
        final CraftingRecipe recipe = compare(manager, input).orElseThrow().value();
        final ItemStack result = recipe.assemble(input);
        assertEquals(Items.WRITTEN_BOOK, result.getItem());
        assertEquals(1, result.get(DataComponents.WRITTEN_BOOK_CONTENT).generation());
        assertTrue(ItemStack.matches(source, recipe.getRemainingItems(input).getFirst()));
    }

    @Test
    void bannerCopyingPreservesPatternAndRejectsDifferentColors() {
        final RecipeManager manager = vanillaManager();
        final ItemStack source = new ItemStack(Items.BANNER.pick(DyeColor.WHITE));
        final BannerPatternLayers patterns = new BannerPatternLayers.Builder()
            .add(RegistryHelper.registryAccess().lookupOrThrow(Registries.BANNER_PATTERN)
                .getOrThrow(BannerPatterns.CROSS), DyeColor.RED).build();
        source.set(DataComponents.BANNER_PATTERNS, patterns);
        final CraftingInput input = CraftingInput.of(2, 1, List.of(source, new ItemStack(Items.BANNER.pick(DyeColor.WHITE))));
        final CraftingRecipe recipe = compare(manager, input).orElseThrow().value();
        assertEquals(patterns, recipe.assemble(input).get(DataComponents.BANNER_PATTERNS));
        assertTrue(ItemStack.matches(source, recipe.getRemainingItems(input).getFirst()));
        assertTrue(compare(manager, CraftingInput.of(2, 1, List.of(source, new ItemStack(Items.BANNER.pick(DyeColor.RED))))).isEmpty());
    }

    @Test
    void mapExtendingStillRequiresValidWorldMapData() {
        final RecipeManager manager = vanillaManager();
        final MapId mapId = new MapId(7);
        final MapItemSavedData data = mock(MapItemSavedData.class);
        when(this.level.getMapData(mapId)).thenReturn(data);
        final ItemStack map = new ItemStack(Items.FILLED_MAP);
        map.set(DataComponents.MAP_ID, mapId);
        final List<ItemStack> grid = new ArrayList<>();
        for (int slot = 0; slot < 9; slot++) {
            grid.add(slot == 4 ? map : new ItemStack(Items.PAPER));
        }
        final CraftingInput input = CraftingInput.of(3, 3, grid);
        final ItemStack result = compare(manager, input).orElseThrow().value().assemble(input);
        assertEquals(mapId, result.get(DataComponents.MAP_ID));
        assertEquals(MapPostProcessing.SCALE, result.get(DataComponents.MAP_POST_PROCESSING));
        when(this.level.getMapData(mapId)).thenReturn(null);
        assertTrue(compare(manager, input).isEmpty());
    }

    @Test
    void removingClearingAndReplacingRecipesInvalidatesCandidates() {
        final RecipeManager manager = manager(List.of(vanillaRecipe("oak_planks")));
        final CraftingInput input = grid(1, 1, Items.OAK_LOG);
        assertResult(manager, input, Items.OAK_PLANKS, 4);
        assertTrue(manager.removeRecipe(vanillaRecipe("oak_planks").id()));
        assertTrue(compare(manager, input).isEmpty());
        manager.recipes = RecipeMap.create(List.of(shapeless("replacement", Items.STICK, Ingredient.of(Items.OAK_LOG))));
        assertResult(manager, input, Items.STICK, 1);
        manager.clearRecipes();
        assertTrue(compare(manager, input).isEmpty());
    }

    @Test
    void vanillaShapedAndShapelessRecipesAgreeWithOptimizationDisabled() {
        final RecipeManager manager = vanillaManager();
        int checked = 0;
        for (final RecipeHolder<CraftingRecipe> holder : manager.recipes.byType(RecipeType.CRAFTING)) {
            final List<ItemStack> stacks = new ArrayList<>();
            final int width;
            final int height;
            if (holder.value() instanceof ShapedRecipe shaped) {
                width = shaped.getWidth();
                height = shaped.getHeight();
                for (final Optional<Ingredient> ingredient : shaped.getIngredients()) {
                    stacks.add(ingredient.map(RecipeMatchingRegressionTest::sample).orElse(ItemStack.EMPTY));
                }
            } else if (holder.value() instanceof ShapelessRecipe shapeless) {
                width = Math.min(3, shapeless.ingredients().size());
                height = (shapeless.ingredients().size() + width - 1) / width;
                for (final Ingredient ingredient : shapeless.ingredients()) {
                    stacks.add(sample(ingredient));
                }
            } else {
                continue;
            }
            while (stacks.size() < width * height) {
                stacks.add(ItemStack.EMPTY);
            }
            final CraftingInput input = CraftingInput.of(width, height, stacks);
            assertTrue(compare(manager, input).isPresent(), holder.id().toString());
            // Mirroring is supported by shaped recipes; changing a material must not bypass matches().
            final List<ItemStack> mirrored = new ArrayList<>(stacks);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    mirrored.set(y * width + x, stacks.get(y * width + width - 1 - x));
                }
            }
            compare(manager, CraftingInput.of(width, height, mirrored));
            for (int slot = 0; slot < stacks.size(); slot++) {
                if (!stacks.get(slot).isEmpty()) {
                    stacks.set(slot, new ItemStack(Items.BARRIER));
                    break;
                }
            }
            assertTrue(compare(manager, CraftingInput.of(width, height, stacks)).isEmpty(), holder.id().toString());
            checked++;
        }
        assertTrue(checked > 500, "Must exercise the actual vanilla recipe registry");
        System.out.println("Recipe equivalence: " + checked + " vanilla shaped/shapeless recipes; "
            + checked * 3 + " original, mirrored and wrong-material grids checked");
    }

    private Optional<RecipeHolder<CraftingRecipe>> compare(final RecipeManager manager, final CraftingInput input) {
        LeavesXRuntime.publish(this.originalConfig);
        assertFalse(LeavesXRuntime.recipeIndexOptimization());
        final var expected = manager.getRecipeFor(RecipeType.CRAFTING, input, this.level);
        LeavesXRuntime.publish(this.indexedConfig);
        assertTrue(LeavesXRuntime.recipeIndexOptimization());
        final var actual = manager.getRecipeFor(RecipeType.CRAFTING, input, this.level);
        assertEquals(expected, actual, () -> "Index changed recipe for " + input.items());
        return actual;
    }

    private void assertResult(final RecipeManager manager, final CraftingInput input, final Item item, final int count) {
        final ItemStack result = compare(manager, input).orElseThrow().value().assemble(input);
        assertEquals(item, result.getItem());
        assertEquals(count, result.getCount());
    }

    private static RecipeManager vanillaManager() {
        return manager(new ArrayList<>(RegistryHelper.context().datapack().getRecipeManager().getRecipes()));
    }

    private static RecipeManager manager(final List<RecipeHolder<?>> recipes) {
        final RecipeManager manager = new RecipeManager(RegistryHelper.registryAccess());
        manager.recipes = RecipeMap.create(recipes);
        return manager;
    }

    private static RecipeHolder<?> vanillaRecipe(final String id) {
        return RegistryHelper.context().datapack().getRecipeManager()
            .byKey(ResourceKey.create(Registries.RECIPE, Identifier.withDefaultNamespace(id))).orElseThrow();
    }

    private static RecipeHolder<?> shapeless(final String id, final Item result, final Ingredient... ingredients) {
        return new RecipeHolder<>(ResourceKey.create(Registries.RECIPE, Identifier.fromNamespaceAndPath("leavesx_test", id)),
            new ShapelessRecipe(new Recipe.CommonInfo(true),
                new CraftingRecipe.CraftingBookInfo(CraftingBookCategory.MISC, ""),
                new ItemStackTemplate(result), List.of(ingredients)));
    }

    private static ItemStack sample(final Ingredient ingredient) {
        return new ItemStack(ingredient.items().findFirst().orElseThrow().value());
    }

    private static CraftingInput grid(final int width, final int height, final Item... items) {
        final List<ItemStack> stacks = new ArrayList<>(items.length);
        for (final Item item : items) {
            stacks.add(item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item));
        }
        return CraftingInput.of(width, height, stacks);
    }
}
