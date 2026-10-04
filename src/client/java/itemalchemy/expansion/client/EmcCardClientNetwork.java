package itemalchemy.expansion.client;

import itemalchemy.expansion.ItemAlchemyExpansion;
import itemalchemy.expansion.item.EmcCardItem;
import itemalchemy.expansion.network.EmcCardNetwork;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;

/**
 * EMC 卡客户端网络：C2S 发送充入/拿取请求 + S2C 接收打开 GUI 信号。
 *
 * <p>放在 client source set（{@link ClientPlayNetworking} 为客户端专属 API）。
 * 服务端收到右键后发 {@link EmcCardNetwork#OPEN_GUI_ID}（携带卡有效余额），
 * 客户端打开 {@link EmcCardMainScreen} 并缓存余额供各界面显示。</p>
 */
public final class EmcCardClientNetwork {

    private EmcCardClientNetwork() {}

    /** 最近一次服务端下发的卡有效余额（关联卡余额在服务端，需同步到客户端显示） */
    private static long cachedBalance = -1L;

    /** 是否已收到过服务端余额同步 */
    private static boolean hasBalance = false;

    /**
     * 返回当前卡的有效余额：优先用服务端同步值（覆盖关联卡账户余额），
     * 未收到时回退读卡 NBT（普通卡）。
     */
    public static long getCardBalance() {
        if (hasBalance && cachedBalance >= 0) return cachedBalance;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null) return 0;
        ItemStack mainHand = mc.player.getMainHandStack();
        if (mainHand.isEmpty() || !(mainHand.getItem() instanceof EmcCardItem)) return 0;
        return EmcCardItem.getStoredEmc(mainHand);
    }

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

    /** 客户端发送配置请求（C2S） */
    public static void sendConfig(byte action, long value) {
        try {
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeByte(action);
            buf.writeLong(value);
            ClientPlayNetworking.send(EmcCardNetwork.CONFIG_ID, buf);
        } catch (Throwable t) {
            ItemAlchemyExpansion.LOGGER.warn("[IAExp] emc card: failed to send config: {}", t.toString());
        }
    }

    /** 注册 S2C 接收器：收到 OPEN_GUI 信号后打开 EMC 卡主菜单。 */
    public static void registerClientReceiver() {
        ClientPlayNetworking.registerGlobalReceiver(EmcCardNetwork.OPEN_GUI_ID,
                (client, handler, buf, responseSender) -> {
                    final long balance = buf.readLong();
                    client.execute(() -> {
                        try {
                            cachedBalance = balance;
                            hasBalance = true;
                            MinecraftClient mc = MinecraftClient.getInstance();
                            if (mc == null || mc.player == null) return;
                            mc.setScreen(new EmcCardMainScreen());
                        } catch (Throwable t) {
                            ItemAlchemyExpansion.LOGGER.warn("[IAExp] failed to open emc card screen: {}", t.toString());
                        }
                    });
                });
        // BALANCE：充入/拿取/快捷后刷新余额缓存（更新当前已打开的界面）
        ClientPlayNetworking.registerGlobalReceiver(EmcCardNetwork.BALANCE_ID,
                (client, handler, buf, responseSender) -> {
                    final long balance = buf.readLong();
                    client.execute(() -> {
                        cachedBalance = balance;
                        hasBalance = true;
                    });
                });
    }
}
