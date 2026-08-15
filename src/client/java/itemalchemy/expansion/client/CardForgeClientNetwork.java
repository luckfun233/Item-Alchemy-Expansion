package itemalchemy.expansion.client;

import itemalchemy.expansion.ItemAlchemyExpansion;
import itemalchemy.expansion.network.CardForgeNetwork;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.PacketByteBuf;

/**
 * 制卡台客户端网络：发送私有/公有/关联/合并动作（C2S）。
 */
public final class CardForgeClientNetwork {

    private CardForgeClientNetwork() {}

    public static void sendAction(byte action) {
        try {
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeByte(action);
            ClientPlayNetworking.send(CardForgeNetwork.ACTION_ID, buf);
        } catch (Throwable t) {
            ItemAlchemyExpansion.LOGGER.warn("[IAExp] card forge: failed to send action {}: {}", action, t.toString());
        }
    }

    /** 发送绑定动作（C2S，携带玩家名） */
    public static void sendBind(String playerName) {
        try {
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeByte(CardForgeNetwork.ACTION_BIND);
            buf.writeString(playerName);
            ClientPlayNetworking.send(CardForgeNetwork.ACTION_ID, buf);
        } catch (Throwable t) {
            ItemAlchemyExpansion.LOGGER.warn("[IAExp] card forge: failed to send bind: {}", t.toString());
        }
    }

    /** 发送解除绑定动作（C2S） */
    public static void sendUnbind() {
        sendAction(CardForgeNetwork.ACTION_UNBIND);
    }

    /** 发送设置绑定限额（C2S，携带单次/总额） */
    public static void sendSetLimits(long single, long total) {
        try {
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeByte(CardForgeNetwork.ACTION_SET_LIMITS);
            buf.writeLong(single);
            buf.writeLong(total);
            ClientPlayNetworking.send(CardForgeNetwork.ACTION_ID, buf);
        } catch (Throwable t) {
            ItemAlchemyExpansion.LOGGER.warn("[IAExp] card forge: failed to send limits: {}", t.toString());
        }
    }
}