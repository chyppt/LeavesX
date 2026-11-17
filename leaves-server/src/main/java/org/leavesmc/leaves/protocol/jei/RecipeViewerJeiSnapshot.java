package org.leavesmc.leaves.protocol.jei;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.crafting.*;
import org.bukkit.craftbukkit.inventory.CraftItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Converts the plugin's server-only snapshot to recipes understood by vanilla JEI codecs. */
public final class RecipeViewerJeiSnapshot {
    private static final int MAX_RECIPES = 10_000;
    private static final int MAX_CHOICES = 256;

    private RecipeViewerJeiSnapshot() {}

    public static List<RecipeHolder<?>> parse(String json, String providerId, RegistryAccess registries) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (!root.has("schemaVersion") || root.get("schemaVersion").getAsInt() != 1) {
            throw new IllegalArgumentException("unsupported recipe snapshot schema");
        }
        JsonArray recipes = root.getAsJsonArray("recipes");
        if (recipes == null || recipes.size() > MAX_RECIPES) throw new IllegalArgumentException("too many recipes");
        List<RecipeHolder<?>> result = new ArrayList<>();
        int approximate = 0;
        List<String> failures = new ArrayList<>();
        for (JsonElement element : recipes) {
            try {
                JsonObject recipe = element.getAsJsonObject();
                String rawId = recipe.get("id").getAsString();
                Identifier id = Identifier.parse(rawId);
                if ("minecraft".equals(id.getNamespace())) continue;
                Recipe<?> converted = convert(recipe);
                if (recipe.getAsJsonArray("inputs").asList().stream().anyMatch(input ->
                        input.getAsJsonObject().has("exactInput") && input.getAsJsonObject().get("exactInput").getAsBoolean())) {
                    approximate++;
                }
                if (converted != null) {
                    ResourceKey<Recipe<?>> key = ResourceKey.create(Registries.RECIPE, id);
                    result.add(new RecipeHolder<>(key, converted));
                }
            } catch (RuntimeException failure) {
                // A malformed optional entry cannot remove vanilla recipes; retain a bounded diagnostic sample.
                if (failures.size() < 3) failures.add(failure.toString());
            }
        }
        if (approximate > 0) org.leavesmc.leaves.LeavesLogger.LOGGER.warn(
                "JEI provider {}: {} recipes are display-only previews; vanilla wire ingredients do not preserve custom identity/count predicates. REI retains full input stacks.",
                providerId, approximate);
        if (result.size() != recipes.size()) {
            org.leavesmc.leaves.LeavesLogger.LOGGER.warn(
                    "JEI provider {}: accepted {}/{} recipes; unsupported/invalid entries were skipped. Samples: {}",
                    providerId, result.size(), recipes.size(), failures);
        }
        return List.copyOf(result);
    }

    private static Recipe<?> convert(JsonObject recipe) {
        String type = recipe.get("type").getAsString();
        List<Ingredient> inputs = inputIngredients(recipe, type);
        if (inputs.isEmpty()) return null;
        JsonArray outputValues = recipe.getAsJsonArray("outputs");
        if (outputValues == null || outputValues.size() != 1) return null;
        ItemStack output = decode(outputValues.get(0).getAsString());
        ItemStackTemplate result = ItemStackTemplate.fromNonEmptyStack(output);
        Recipe.CommonInfo common = new Recipe.CommonInfo(true);
        return switch (type) {
            case "shaped" -> shaped(recipe, inputs, result, common);
            case "shapeless" -> new ShapelessRecipe(common,
                    new CraftingRecipe.CraftingBookInfo(CraftingBookCategory.MISC, ""), result, inputs);
            case "smithing" -> inputs.size() == 3 && inputs.get(1) != null
                    ? new SmithingTransformRecipe(common, Optional.ofNullable(inputs.get(0)), inputs.get(1),
                            Optional.ofNullable(inputs.get(2)), result) : null;
            case "stonecutting" -> inputs.size() == 1 ? new StonecutterRecipe(common, inputs.getFirst(), result) : null;
            case "smelting", "blasting", "smoking", "campfire" -> cooking(type, inputs, result, recipe, common);
            default -> null;
        };
    }

    private static Recipe<?> shaped(JsonObject recipe, List<Ingredient> inputs, ItemStackTemplate result,
                                    Recipe.CommonInfo common) {
        int width = bounded(recipe, "width", 1, 3);
        int height = bounded(recipe, "height", 1, 3);
        if (inputs.size() != width * height) return null;
        List<Optional<Ingredient>> pattern = new ArrayList<>(inputs.size());
        for (Ingredient ingredient : inputs) pattern.add(ingredient == null || ingredient.isEmpty() ? Optional.empty() : Optional.of(ingredient));
        ShapedRecipePattern shaped = new ShapedRecipePattern(width, height, pattern, Optional.empty());
        return new ShapedRecipe(common,
                new CraftingRecipe.CraftingBookInfo(CraftingBookCategory.MISC, ""), shaped, result);
    }

    private static Recipe<?> cooking(String type, List<Ingredient> inputs, ItemStackTemplate result,
                                     JsonObject recipe, Recipe.CommonInfo common) {
        if (inputs.size() != 1) return null;
        float experience = recipe.has("experience") ? recipe.get("experience").getAsFloat() : 0F;
        int time = recipe.has("cookingTimeTicks") ? recipe.get("cookingTimeTicks").getAsInt() : 200;
        if (!Float.isFinite(experience) || time < 0) return null;
        AbstractCookingRecipe.CookingBookInfo info = new AbstractCookingRecipe.CookingBookInfo(
                CookingBookCategory.MISC, "");
        return switch (type) {
            case "smelting" -> new SmeltingRecipe(common, info, inputs.getFirst(), result, experience, time);
            case "blasting" -> new BlastingRecipe(common, info, inputs.getFirst(), result, experience, time);
            case "smoking" -> new SmokingRecipe(common, info, inputs.getFirst(), result, experience, time);
            case "campfire" -> new CampfireCookingRecipe(common, info, inputs.getFirst(), result, experience, time);
            default -> null;
        };
    }

    private static List<Ingredient> inputIngredients(JsonObject recipe, String type) {
        JsonArray values = recipe.getAsJsonArray("inputs");
        if (values == null || values.size() > 64) return List.of();
        int expected = type.equals("shaped")
                ? bounded(recipe, "width", 1, 3) * bounded(recipe, "height", 1, 3) : type.equals("smithing") ? 3 : -1;
        Ingredient[] slots = expected > 0 ? new Ingredient[expected] : null;
        boolean[] occupied = slots == null ? null : new boolean[expected];
        if (type.equals("smithing") && values.size() != 3) return List.of();
        List<Ingredient> result = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            JsonObject slot = values.get(index).getAsJsonObject();
            int slotIndex = slots == null ? index : bounded(slot, "slot", 0, expected - 1);
            if (slots != null) {
                if (occupied[slotIndex]) return List.of();
                occupied[slotIndex] = true;
            }
            if (slot.has("empty") && slot.get("empty").getAsBoolean()) {
                if (!type.equals("smithing") || slotIndex == 1 || slot.has("choices")) return List.of();
                continue;
            }
            JsonArray choices = slot.getAsJsonArray("choices");
            if (choices == null || choices.size() == 0 || choices.size() > MAX_CHOICES) return List.of();
            List<ItemStack> stacks = new ArrayList<>(choices.size());
            for (JsonElement choice : choices) stacks.add(decode(choice.getAsString()));
            Ingredient ingredient = Ingredient.ofStacks(stacks);
            if (slots == null) result.add(ingredient); else slots[slotIndex] = ingredient;
        }
        if (slots != null) {
            for (Ingredient slot : slots) result.add(slot);
        }
        if (expected >= 0 && values.size() > expected) return List.of();
        return result;
    }

    private static ItemStack decode(String encoded) {
        if (encoded.length() > 100_000) throw new IllegalArgumentException("stack too large");
        org.bukkit.inventory.ItemStack bukkit = org.bukkit.inventory.ItemStack.deserializeBytes(
                java.util.Base64.getDecoder().decode(encoded));
        ItemStack stack = CraftItemStack.asNMSCopy(bukkit);
        if (stack.isEmpty()) throw new IllegalArgumentException("empty stack");
        return stack;
    }

    private static int bounded(JsonObject object, String key, int min, int max) {
        int value = object.get(key).getAsInt();
        if (value < min || value > max) throw new IllegalArgumentException("value out of range");
        return value;
    }
}

