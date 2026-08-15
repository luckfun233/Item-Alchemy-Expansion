package itemalchemy.expansion.client;

import itemalchemy.expansion.ItemAlchemyExpansion;
import itemalchemy.expansion.network.CardForgeNetwork;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.PacketByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * 制卡台客户端网络：发送私有/公有/关联/合并/绑定动作（C2S）+ 接收在线玩家列表（S2C）。
 *
 * <p>玩家列表缓存在 {@link #onlinePlayers}（volatile，主线程替换），
 * 绑定页下拉模糊匹配直接读取，无需与 Screen 生命周期绑定。</p>
 */
public final class CardForgeClientNetwork {

    private CardForgeClientNetwork() {}

    /** 最近一次服务端下发的在线玩家名列表（已排序） */
    public static volatile List<String> onlinePlayers = new ArrayList<>();

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

    /** 请求在线玩家列表（C2S，服务端回 PLAYERS_ID） */
    public static void sendRequestPlayers() {
        sendAction(CardForgeNetwork.ACTION_REQUEST_PLAYERS);
    }

    /** 注册 S2C 接收器：在线玩家名列表下发 */
    public static void registerClientReceiver() {
        ClientPlayNetworking.registerGlobalReceiver(CardForgeNetwork.PLAYERS_ID,
                (client, handler, buf, responseSender) -> {
                    final int n = buf.readVarInt();
                    final List<String> names = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) {
                        names.add(buf.readString());
                    }
                    client.execute(() -> onlinePlayers = names);
                });
    }
}
