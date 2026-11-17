package org.leavesx.probe.neko;

import cn.dg32z.libs.com.github.retrooper.packetevents.event.PacketListenerAbstract;
import cn.dg32z.libs.com.github.retrooper.packetevents.event.PacketListenerPriority;
import cn.dg32z.libs.com.github.retrooper.packetevents.event.PacketReceiveEvent;
import cn.dg32z.libs.com.github.retrooper.packetevents.event.PacketSendEvent;
import cn.dg32z.libs.com.github.retrooper.packetevents.event.ProtocolPacketEvent;
import cn.dg32z.libs.com.github.retrooper.packetevents.manager.server.ServerVersion;
import cn.dg32z.libs.com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import java.util.function.Supplier;

/** 在 Neko 自带 PacketEvents 的观察边界记录包序，不注册编码器或发送额外确认包。 */
final class MovementWireProbe extends PacketListenerAbstract {
    private final Supplier<MovementTrace> traces;

    MovementWireProbe(Supplier<MovementTrace> traces) {
        super(PacketListenerPriority.MONITOR);
        this.traces = traces;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        observe(event, true);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        observe(event, false);
    }

    private void observe(ProtocolPacketEvent event, boolean outgoing) {
        MovementTrace trace = traces.get();
        if (trace == null) return;
        try {
            if (event.getUser() == null || !trace.player.equals(event.getUser().getUUID())) return;
            if (!trace.accepting()) return;
            String type = event.getPacketType() instanceof Enum<?> value ? value.name() : event.getPacketType().toString();
            if (!WirePacketSummary.selected(outgoing, type)) return;
            String summary = "payload=unavailable(version-or-buffer)";
            // 有 Via 转换时也不拿 26.1.2 格式猜测其他协议；包类型与观察顺序仍可保留。
            if (event.getServerVersion() == ServerVersion.V_26_1_2 && event.getByteBuf() != null) {
                try {
                    Object buffer = event.getByteBuf();
                    // 绝对读取最多 384 字节；避免为了读区块坐标复制几百 KiB 的区块数据。
                    byte[] prefix = new byte[Math.min(384, ByteBufHelper.readableBytes(buffer))];
                    ByteBufHelper.getBytes(buffer, ByteBufHelper.readerIndex(buffer), prefix);
                    summary = WirePacketSummary.describe(outgoing, type, prefix);
                } catch (RuntimeException error) {
                    summary = "payload=unavailable(" + error.getClass().getSimpleName() + ")";
                }
            }
            trace.appendWire(outgoing, "wire-" + (outgoing ? "out" : "in") + " stage=PacketEvents-MONITOR type=" + type
                + " " + summary + " cancelled=" + event.isCancelled() + " reencode=" + event.needsReEncode());
        } catch (RuntimeException | LinkageError error) {
            // 观察器的失败不得传播到玩家的网络处理链。
            trace.append("wire-observer-error=" + error.getClass().getSimpleName(), false);
        }
    }
}
