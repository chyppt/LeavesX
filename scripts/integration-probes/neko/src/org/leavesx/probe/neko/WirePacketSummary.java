package org.leavesx.probe.neko;

/** 只读 26.1.2 包头和有限方块变化，不移动原缓冲区游标，不解析整份区块。 */
final class WirePacketSummary {
    private static final int MAX_BLOCKS = 32;

    static boolean selected(boolean outgoing, String type) {
        return outgoing ? switch (type) {
            case "BLOCK_CHANGE", "MULTI_BLOCK_CHANGE", "ACKNOWLEDGE_BLOCK_CHANGES", "CHUNK_DATA",
                "UNLOAD_CHUNK", "PING", "PLAYER_POSITION_AND_LOOK", "RESPAWN" -> true;
            default -> false;
        } : switch (type) {
            case "PONG", "PLAYER_DIGGING", "PLAYER_BLOCK_PLACEMENT", "USE_ITEM" -> true;
            default -> false;
        };
    }

    static String describe(boolean outgoing, String type, byte[] payload) {
        Cursor input = new Cursor(payload);
        if (!outgoing) return switch (type) {
            case "PONG" -> "transactionId=" + input.readInt();
            case "USE_ITEM" -> useItem(input);
            case "PLAYER_DIGGING" -> digging(input);
            case "PLAYER_BLOCK_PLACEMENT" -> placement(input);
            default -> "payload=not-decoded";
        };
        return switch (type) {
            case "ACKNOWLEDGE_BLOCK_CHANGES" -> "blockSequence=" + input.varInt();
            case "PING" -> "transactionId=" + input.readInt();
            case "BLOCK_CHANGE" -> "position=" + position(input.readLong()) + " stateId=" + input.varInt();
            case "MULTI_BLOCK_CHANGE" -> section(input);
            case "CHUNK_DATA" -> "chunk=" + input.readInt() + "," + input.readInt();
            case "UNLOAD_CHUNK" -> chunk(input.readLong());
            default -> "payload=not-decoded";
        };
    }

    private static String useItem(Cursor input) {
        return "hand=" + input.varInt() + " blockSequence=" + input.varInt();
    }

    private static String digging(Cursor input) {
        int action = input.varInt();
        String position = position(input.readLong());
        int face = input.readUnsignedByte();
        return "action=" + action + " position=" + position + " face=" + face + " blockSequence=" + input.varInt();
    }

    private static String placement(Cursor input) {
        int hand = input.varInt();
        String position = position(input.readLong());
        int face = input.varInt();
        input.skipBytes(14); // 三个点击位置浮点数、inside 和 worldBorder。
        return "hand=" + hand + " position=" + position + " face=" + face + " blockSequence=" + input.varInt();
    }

    private static String section(Cursor input) {
        long packed = input.readLong();
        int x = (int)(packed >> 42);
        int y = (int)(packed << 44 >> 44);
        int z = (int)(packed << 22 >> 42);
        int count = input.varInt();
        if (count < 0 || count > 4096) return "payload=unavailable(invalid-section-count)";
        StringBuilder text = new StringBuilder("section=").append(x).append(',').append(y).append(',').append(z)
            .append(" changes=").append(count).append(" samples=");
        for (int index = 0; index < Math.min(count, MAX_BLOCKS); index++) {
            long change = input.varLong();
            int relative = (int)(change & 4095L);
            if (index != 0) text.append(';');
            text.append((x << 4) + (relative >> 8)).append(',')
                .append((y << 4) + (relative & 15)).append(',')
                .append((z << 4) + ((relative >> 4) & 15)).append('=').append(change >>> 12);
        }
        return text.append(" truncated=").append(count > MAX_BLOCKS).toString();
    }

    private static String chunk(long packed) {
        return "chunk=" + (int)packed + "," + (int)(packed >> 32);
    }

    private static String position(long packed) {
        return (packed >> 38) + "," + (packed << 52 >> 52) + "," + (packed << 26 >> 38);
    }

    private static final class Cursor {
        private final byte[] data;
        private int index;

        private Cursor(byte[] data) {
            this.data = data;
        }

        private int readUnsignedByte() {
            if (index >= data.length) throw new IllegalArgumentException("Packet payload ended early");
            return data[index++] & 255;
        }

        private int readInt() {
            return (readUnsignedByte() << 24) | (readUnsignedByte() << 16) | (readUnsignedByte() << 8) | readUnsignedByte();
        }

        private long readLong() {
            long value = 0;
            for (int part = 0; part < 8; part++) value = (value << 8) | readUnsignedByte();
            return value;
        }

        private void skipBytes(int count) {
            if (count < 0 || data.length - index < count) throw new IllegalArgumentException("Packet payload ended early");
            index += count;
        }

        private int varInt() {
            return (int) variable(5);
        }

        private long varLong() {
            return variable(10);
        }

        private long variable(int limit) {
            long value = 0;
            for (int index = 0; index < limit; index++) {
                int part = readUnsignedByte();
                value |= (long)(part & 127) << (index * 7);
                if ((part & 128) == 0) return value;
            }
            throw new IllegalArgumentException("Invalid variable integer");
        }
    }
}
