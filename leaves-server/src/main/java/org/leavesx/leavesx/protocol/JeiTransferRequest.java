package org.leavesx.leavesx.protocol;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;

/** JEI's legacy and counted transfer wire formats. No item stacks are accepted from the client. */
public record JeiTransferRequest(List<Operation> operations, List<Integer> craftingSlots,
                                 List<Integer> inventorySlots, boolean maximum, boolean completeSets, int transferId) {
    public record Operation(int source, int target, int count) {}

    public static JeiTransferRequest read(FriendlyByteBuf buffer, boolean counted, boolean result) {
        final int count = bounded(buffer.readVarInt(), 1, 128);
        final List<Operation> operations = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            operations.add(new Operation(bounded(buffer.readVarInt(), 0, 127),
                bounded(buffer.readVarInt(), 0, 127), counted ? bounded(buffer.readVarInt(), 1, 64) : 1));
        }
        final List<Integer> crafting = readSlots(buffer, 9);
        final List<Integer> inventory = readSlots(buffer, 36);
        final boolean maximum = buffer.readBoolean();
        final boolean complete = buffer.readBoolean();
        final int transferId = result ? buffer.readVarInt() : -1;
        if (buffer.isReadable()) throw new IllegalArgumentException("Trailing JEI transfer bytes");
        return new JeiTransferRequest(List.copyOf(operations), crafting, inventory, maximum, complete, transferId);
    }

    private static List<Integer> readSlots(FriendlyByteBuf buffer, int maximum) {
        final int count = bounded(buffer.readVarInt(), 1, maximum);
        final List<Integer> slots = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            final int slot = bounded(buffer.readVarInt(), 0, 127);
            if (slots.contains(slot)) throw new IllegalArgumentException("Duplicate JEI slot");
            slots.add(slot);
        }
        return List.copyOf(slots);
    }

    private static int bounded(int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) throw new IllegalArgumentException("Invalid JEI transfer size or slot");
        return value;
    }
}
