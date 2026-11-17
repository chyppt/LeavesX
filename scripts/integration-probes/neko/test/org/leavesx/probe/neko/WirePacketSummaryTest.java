package org.leavesx.probe.neko;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.shorts.Short2ObjectArrayMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.bukkit.support.RegistryHelper;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

@Normal
class WirePacketSummaryTest {
    @Test
    void decodesBlockChangedAckWithoutConfusingTransactionIds() {
        assertTrue(WirePacketSummary.describe(true, "ACKNOWLEDGE_BLOCK_CHANGES", varInt(37)).contains("blockSequence=37"));
        assertTrue(WirePacketSummary.describe(true, "PING", intBytes(-451)).contains("transactionId=-451"));
    }

    @Test
    void decodesDiggingSequenceAndPosition() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        writeVarInt(bytes, 2);
        writeLong(bytes, BlockPos.asLong(109, 69, -103));
        bytes.write(1);
        writeVarInt(bytes, 91);
        String text = WirePacketSummary.describe(false, "PLAYER_DIGGING", bytes.toByteArray());
        assertTrue(text.contains("position=109,69,-103"));
        assertTrue(text.contains("blockSequence=91"));
    }

    @Test
    void realBlockUpdateCodecPreservesNegativePositionAndStateId() {
        var state = Blocks.STONE.defaultBlockState();
        String text = encoded(true, "BLOCK_CHANGE", ClientboundBlockUpdatePacket.STREAM_CODEC,
            new ClientboundBlockUpdatePacket(new BlockPos(-30_000_000, -64, 30_000_000), state));
        assertEquals("position=-30000000,-64,30000000 stateId=" + Block.getId(state), text);
    }

    @Test
    void realSectionCodecPreservesSignedCoordinatesAndRelativeBlockOrder() {
        var changes = new Short2ObjectArrayMap<BlockState>();
        changes.put((short)0x2f9, Blocks.STONE.defaultBlockState());
        changes.put((short)0xf20, Blocks.AIR.defaultBlockState());
        String text = encoded(true, "MULTI_BLOCK_CHANGE", ClientboundSectionBlocksUpdatePacket.STREAM_CODEC,
            new ClientboundSectionBlocksUpdatePacket(SectionPos.of(-7, -4, 13), changes));
        assertTrue(text.contains("section=-7,-4,13 changes=2"));
        assertTrue(text.contains("samples=-110,-55,223=" + Block.getId(Blocks.STONE.defaultBlockState())
            + ";-97,-64,210=" + Block.getId(Blocks.AIR.defaultBlockState()) + " truncated=false"));
    }

    @Test
    void realPlacementCodecIncludesTheTwoFlagsBeforeTheSequence() {
        var hit = new BlockHitResult(new Vec3(109.25, 69.5, -102.75), Direction.UP, new BlockPos(109, 69, -103), true, true);
        String text = encoded(false, "PLAYER_BLOCK_PLACEMENT", ServerboundUseItemOnPacket.STREAM_CODEC,
            new ServerboundUseItemOnPacket(InteractionHand.OFF_HAND, hit, Integer.MAX_VALUE));
        assertEquals("hand=1 position=109,69,-103 face=1 blockSequence=2147483647", text);
    }

    @Test
    void capsSectionSamplesAndRejectsInvalidCountsAndTruncatedData() {
        var bytes = new ByteArrayOutputStream();
        writeLong(bytes, SectionPos.asLong(0, 0, 0));
        writeVarInt(bytes, 33);
        for (int i = 0; i < 32; i++) bytes.write(0);
        assertTrue(WirePacketSummary.describe(true, "MULTI_BLOCK_CHANGE", bytes.toByteArray()).endsWith("truncated=true"));
        assertThrows(IllegalArgumentException.class, () -> WirePacketSummary.describe(true, "BLOCK_CHANGE", new byte[7]));
        assertThrows(IllegalArgumentException.class, () -> WirePacketSummary.describe(true, "ACKNOWLEDGE_BLOCK_CHANGES",
            new byte[] {-1, -1, -1, -1, -1, -1}));
    }

    private static <T> String encoded(boolean outgoing, String type, StreamCodec<? super RegistryFriendlyByteBuf, T> codec, T packet) {
        var bytes = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryHelper.registryAccess());
        try {
            codec.encode(bytes, packet);
            byte[] data = new byte[bytes.readableBytes()];
            bytes.getBytes(0, data);
            return WirePacketSummary.describe(outgoing, type, data);
        } finally {
            bytes.release();
        }
    }

    private static byte[] varInt(int value) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        writeVarInt(bytes, value);
        return bytes.toByteArray();
    }

    private static byte[] intBytes(int value) {
        return new byte[] {(byte)(value >>> 24), (byte)(value >>> 16), (byte)(value >>> 8), (byte)value};
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        while ((value & ~127) != 0) { out.write((value & 127) | 128); value >>>= 7; }
        out.write(value);
    }

    private static void writeLong(ByteArrayOutputStream out, long value) {
        for (int shift = 56; shift >= 0; shift -= 8) out.write((int)(value >>> shift));
    }

}
