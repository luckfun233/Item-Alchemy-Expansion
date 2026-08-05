package itemalchemy.expansion.client;

import itemalchemy.expansion.network.SetEmcNetwork;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.PacketByteBuf;

/**
 * 「设置 EMC」C2S 包客户端发送方。
 *
 * <p>放在 client source set（{@code src/client/java}），因为
 * {@link ClientPlayNetworking} 是客户端专属 API，不能在 main source set 引用
 * （服务端编译期不可见）。</p>
 *
 * <p>{@link SetEmcScreen} 点击「确认」时调用 {@link #sendSetEmc} 发送 C2S 包，
 * 服务端由 {@link SetEmcNetwork#registerServer()} 接收处理。</p>
 */
public final class SetEmcClientNetwork {

    private SetEmcClientNetwork() {}

    /**
     * 客户端发送设置 EMC 请求。
     *
     * @param itemId 物品 ID（如 {@code "minecraft:stone"}）
     * @param emc    新 EMC 值（>= 0）
     * @param scope  {@link SetEmcNetwork#SCOPE_THIS_SAVE} 或 {@link SetEmcNetwork#SCOPE_GLOBAL}
     */
    public static void sendSetEmc(String itemId, long emc, int scope) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(itemId);
        buf.writeLong(emc);
        buf.writeVarInt(scope);
        ClientPlayNetworking.send(SetEmcNetwork.SET_EMC_ID, buf);
    }
}
