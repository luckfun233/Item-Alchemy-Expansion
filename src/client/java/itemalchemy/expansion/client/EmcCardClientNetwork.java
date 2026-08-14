package itemalchemy.expansion.client;

import itemalchemy.expansion.ItemAlchemyExpansion;
import itemalchemy.expansion.network.EmcCardNetwork;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.PacketByteBuf;

/**
 * EMC 卡客户端网络：C2S 发送充入/拿取请求 + S2C 接收打开 GUI 信号。
 *
 * <p>放在 client source set（{@link ClientPlayNetworking} 为客户端专属 API）。
 * 服务端收到右键后发 {@link EmcCardNetwork#OPEN_GUI_ID}，客户端在此打开 {@link EmcCardMainScreen}。</p>
 */
public final class EmcCardClientNetwork {

    private EmcCardClientNetwork() {}

    /** 客户端发送充入请求（C2S）。 */
    public static void sendDeposit(long amount) {
        try {
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeLong(amount);
            ClientPlayNetworking.send(EmcCardNetwork.DEPOSIT_ID, buf);
        } catch (Throwable t) {
            ItemAlchemyExpansion.LOGGER.warn("[IAExp] emc card: failed to send deposit: {}", t.toString());
        }
    }

    /** 客户端发送拿取请求（C2S）。 */
    public static void sendWithdraw(long amount) {
        try {
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeLong(amount);
            ClientPlayNetworking.send(EmcCardNetwork.WITHDRAW_ID, buf);
        } catch (Throwable t) {
            ItemAlchemyExpansion.LOGGER.warn("[IAExp] emc card: failed to send withdraw: {}", t.toString());
        }
    }

    /** 注册 S2C 接收器：收到 OPEN_GUI 信号后打开 EMC 卡主菜单。 */
    public static void registerClientReceiver() {
        ClientPlayNetworking.registerGlobalReceiver(EmcCardNetwork.OPEN_GUI_ID,
                (client, handler, buf, responseSender) -> {
                    client.execute(() -> {
                        try {
                            MinecraftClient mc = MinecraftClient.getInstance();
                            if (mc == null || mc.player == null) return;
                            mc.setScreen(new EmcCardMainScreen());
                        } catch (Throwable t) {
                            ItemAlchemyExpansion.LOGGER.warn("[IAExp] failed to open emc card screen: {}", t.toString());
                        }
                    });
                });
    }
}
