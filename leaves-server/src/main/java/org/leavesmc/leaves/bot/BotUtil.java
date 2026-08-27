package org.leavesmc.leaves.bot;

import com.google.common.base.Charsets;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.LeavesConfig;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public class BotUtil {

    public static void replenishment(@NotNull ItemStack itemStack, NonNullList<ItemStack> itemStackList) {
        int count = itemStack.getMaxStackSize() / 2;
        if (itemStack.getCount() <= 8 && count > 8) {
            for (ItemStack itemStack1 : itemStackList) {
                if (itemStack1 == ItemStack.EMPTY || itemStack1 == itemStack) {
                    continue;
                }

                if (ItemStack.isSameItemSameComponents(itemStack1, itemStack)) {
                    if (itemStack1.getCount() > count) {
                        itemStack.setCount(itemStack.getCount() + count);
                        itemStack1.setCount(itemStack1.getCount() - count);
                    } else {
                        itemStack.setCount(itemStack.getCount() + itemStack1.getCount());
                        itemStack1.setCount(0);
                    }
                    break;
                }
            }
        }
    }

    public static void replaceTool(@NotNull EquipmentSlot slot, @NotNull ServerBot bot) {
        ItemStack itemStack = bot.getItemBySlot(slot);
        for (int i = 0; i < 36; i++) {
            ItemStack itemStack1 = bot.getInventory().getItem(i);
            if (itemStack1 == ItemStack.EMPTY || itemStack1 == itemStack) {
                continue;
            }

            if (itemStack1.getItem().getClass() == itemStack.getItem().getClass() && !isDamage(itemStack1, 10)) {
                ItemStack itemStack2 = itemStack1.copy();
                bot.getInventory().setItem(i, itemStack);
                bot.setItemSlot(slot, itemStack2);
                return;
            }
        }

        for (int i = 0; i < 36; i++) {
            ItemStack itemStack1 = bot.getInventory().getItem(i);
            if (itemStack1 == ItemStack.EMPTY && itemStack1 != itemStack) {
                bot.getInventory().setItem(i, itemStack);
                bot.setItemSlot(slot, ItemStack.EMPTY);
                return;
            }
        }
    }

    public static boolean isDamage(@NotNull ItemStack item, int minDamage) {
        return item.isDamageableItem() && (item.getMaxDamage() - item.getDamageValue()) <= minDamage;
    }

    @NotNull
    public static UUID getBotUUID(@NotNull BotCreateState state) {
        return getBotUUID(state.fullName());
    }

    public static UUID getBotUUID(@NotNull String fullName) {
        return UUID.nameUUIDFromBytes(("Fakeplayer:" + fullName).getBytes(Charsets.UTF_8));
    }

    public static UUID getBotLevel(@NotNull String fullName, BotDataStorage botDataStorage) {
        // The list file is authoritative. Older bots may use a UUID that no longer matches the current name-derived
        // algorithm or configured username prefix, so deriving it first can incorrectly classify valid data as stale.
        UUID uuid = botDataStorage.findUUID(fullName).orElseGet(() -> getBotUUID(fullName));
        Optional<CompoundTag> tagOptional = botDataStorage.read(uuid.toString());
        if (tagOptional.isEmpty()) {
            return null;
        }
        CompoundTag tag = tagOptional.get();
        Optional<Long> worldUUIDMost = tag.getLong("WorldUUIDMost");
        Optional<Long> worldUUIDLeast = tag.getLong("WorldUUIDLeast");
        if (worldUUIDMost.isEmpty() || worldUUIDLeast.isEmpty()) {
            return null;
        }
        return new UUID(worldUUIDMost.get(), worldUUIDLeast.get());
    }

    public static String getFullName(String inputName) {
        return LeavesConfig.modify.fakeplayer.prefix + inputName + LeavesConfig.modify.fakeplayer.suffix;
    }

    public static boolean isCreateLegal(@NotNull String name) {
        if (!name.matches("^[a-zA-Z0-9_]{4,16}$")) {
            return false;
        }

        if (Bukkit.getPlayerExact(name) != null || BotList.INSTANCE.getBotByName(name) != null) {
            return false;
        }

        if (isNameForbidden(name)) {
            return false;
        }

        return BotList.INSTANCE.bots.size() < LeavesConfig.modify.fakeplayer.limit;
    }

    /** Returns whether a raw or full fakeplayer name matches either Leaves' exact list or LeavesX fragments. */
    public static boolean isNameForbidden(@NotNull String name) {
        if (LeavesConfig.modify.fakeplayer.unableNames.contains(name)) {
            return true;
        }
        // This is an opt-in extension of Leaves' exact-name blacklist. Keep the comparison independent from the
        // configured display prefix and normalize once so mixed-case input cannot bypass an operator rule.
        String candidate = name;
        final String configuredPrefix = LeavesConfig.modify.fakeplayer.prefix;
        final String configuredSuffix = LeavesConfig.modify.fakeplayer.suffix;
        if (!configuredPrefix.isEmpty() && candidate.startsWith(configuredPrefix)) {
            candidate = candidate.substring(configuredPrefix.length());
        }
        if (!configuredSuffix.isEmpty() && candidate.endsWith(configuredSuffix)) {
            candidate = candidate.substring(0, candidate.length() - configuredSuffix.length());
        }
        final String normalizedName = candidate.toLowerCase(Locale.ROOT);
        return org.leavesx.leavesx.config.LeavesXRuntime.forbiddenFakeplayerNameContains().stream()
            .anyMatch(fragment -> !fragment.isEmpty() && normalizedName.contains(fragment));
    }

    /** Validates the optional role without changing the account name or command identity. */
    public static boolean isRoleLegal(@NotNull String role) {
        if (role.isEmpty()) {
            return !org.leavesx.leavesx.config.LeavesXRuntime.fakeplayerRoleEnabled()
                || !org.leavesx.leavesx.config.LeavesXRuntime.fakeplayerRoleRequired();
        }
        final String content = stripLegacyColorCodes(role);
        if (org.leavesx.leavesx.config.LeavesXRuntime.fakeplayerRoleLengthLimited()
            && content.codePointCount(0, content.length()) > org.leavesx.leavesx.config.LeavesXRuntime.fakeplayerRoleMaxLength()) {
            return false;
        }
        if (org.leavesx.leavesx.config.LeavesXRuntime.fakeplayerRoleChineseOnly()) {
            for (int offset = 0; offset < content.length();) {
                final int codePoint = content.codePointAt(offset);
                final Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
                if (script != Character.UnicodeScript.HAN) {
                    return false;
                }
                offset += Character.charCount(codePoint);
            }
        }
        return true;
    }

    private static String stripLegacyColorCodes(final String input) {
        final StringBuilder result = new StringBuilder(input.length());
        for (int index = 0; index < input.length(); index++) {
            final char character = input.charAt(index);
            if ((character == '&' || character == '\u00A7') && index + 1 < input.length()) {
                final char code = input.charAt(index + 1);
                if ("0123456789AaBbCcDdEeFfKkLlMmNnOoRr".indexOf(code) >= 0) {
                    index++;
                    continue;
                }
                if (code == '#' && index + 7 < input.length()) {
                    boolean hex = true;
                    for (int hexIndex = index + 2; hexIndex <= index + 7; hexIndex++) {
                        final char digit = input.charAt(hexIndex);
                        if (Character.digit(digit, 16) < 0) {
                            hex = false;
                            break;
                        }
                    }
                    if (hex) {
                        index += 7;
                        continue;
                    }
                }
            }
            result.append(character);
        }
        return result.toString();
    }
}
