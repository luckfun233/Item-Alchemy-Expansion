package itemalchemy.expansion.client;

import itemalchemy.expansion.network.FilterModeNetwork;
import itemalchemy.expansion.search.SearchFilterMode;
import net.minecraft.network.PacketByteBuf;
import net.pitan76.mcpitanlib.api.network.ClientNetworking;
import net.pitan76.mcpitanlib.api.network.PacketByteUtil;

/**
 * 「筛选模式切换」网络包客户端发送方。
 *
 * <p>客户端点击筛选按钮时调用 {@link #send(SearchFilterMode)}，发送 ordinal 给服务端。
 * 服务端在 {@link FilterModeNetwork#registerServer} 接收并应用。</p>
 */
public final class FilterModeClientNetwork {

    private FilterModeClientNetwork() {}

    /** 发送筛选模式切换包 */
    public static void send(SearchFilterMode mode) {
        PacketByteBuf buf = PacketByteUtil.create();
        buf.writeByte(mode.ordinal());
        ClientNetworking.send(FilterModeNetwork.FILTER_MODE_ID, buf);
    }
}
