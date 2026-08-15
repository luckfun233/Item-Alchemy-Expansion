package itemalchemy.expansion.client;

import itemalchemy.expansion.IAExpServices;
import itemalchemy.expansion.ItemAlchemyExpansion;
import itemalchemy.expansion.nbt.ItemVariantKey;
import itemalchemy.expansion.network.EmcAutoNetwork;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * 自动装置客户端网络：EMC 输出器列表请求/选择设置（C2S）+ 打开界面/列表/所选同步（S2C）。
 */
public final class EmcAutoClientNetwork {

    private EmcAutoClientNetwork() {}

    /** 当前打开的输出器选择界面（用于实时刷新） */
    private static EmcEmitterScreen activeScreen;

    /** 登记当前输出器界面（打开时调用） */
    public static void attach(EmcEmitterScreen screen) {
        activeScreen = screen;
    }

    /** 注销当前输出器界面（关闭时调用） */
    public static void detach(EmcEmitterScreen screen) {
        if (activeScreen == screen) activeScreen = null;
    }

    /** 客户端请求打开者的转换桌列表（C2S） */
    public static void sendRequest(long pos) {
        try {
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeLong(pos);
            ClientPlayNetworking.send(EmcAutoNetwork.REQ_ID, buf);
        } catch (Throwable t) {
            ItemAlchemyExpansion.LOGGER.warn("[IAExp] emc emitter: failed to send list request: {}", t.toString());
        }
    }

    /** 客户端设置所选物品（variant 为 null 表示清除） */
    public static void sendSet(long pos, String variant) {
        try {
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeLong(pos);
            buf.writeBoolean(variant != null && !variant.isEmpty());
            if (variant != null && !variant.isEmpty()) {
                buf.writeString(variant);
            }
            ClientPlayNetworking.send(EmcAutoNetwork.SET_ID, buf);
        } catch (Throwable t) {
            ItemAlchemyExpansion.LOGGER.warn("[IAExp] emc emitter: failed to send set: {}", t.toString());
        }
    }

    /** 注册 S2C 接收器：打开界面 / 下发列表 / 更新所选 */
    public static void registerClientReceiver() {
        ClientPlayNetworking.registerGlobalReceiver(EmcAutoNetwork.OPEN_ID,
                (client, handler, buf, responseSender) -> {
                    final long pos = buf.readLong();
                    client.execute(() -> {
                        try {
                            MinecraftClient mc = MinecraftClient.getInstance();
                            if (mc == null || mc.player == null) return;
                            mc.setScreen(new EmcEmitterScreen(pos, true));
                        } catch (Throwable t) {
                            ItemAlchemyExpansion.LOGGER.warn("[IAExp] failed to open emc emitter screen: {}", t.toString());
                        }
                    });
                });

        ClientPlayNetworking.registerGlobalReceiver(EmcAutoNetwork.LIST_S2C_ID,
                (client, handler, buf, responseSender) -> {
                    final String selected = buf.readString();
                    final long balance = buf.readLong();
                    // 与服务端 handleListRequest 写入顺序严格一致（含 facing），漏读会导致后续字节全部错位
                    final String facing = buf.readString();
                    final int n = buf.readInt();
                    final List<String> keys = new ArrayList<>(n);
                    final List<ItemStack> stacks = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) {
                        String s = buf.readString();
                        keys.add(s);
                        ItemVariantKey vk = ItemVariantKey.fromStorageString(s);
                        if (vk != null) {
                            stacks.add(IAExpServices.rebuildStack(vk));
                        } else {
                            stacks.add(ItemStack.EMPTY);
                        }
                    }
                    client.execute(() -> {
                        if (activeScreen != null) {
                            activeScreen.onListReceived(keys, stacks, selected, balance, facing);
                        }
                    });
                });

        ClientPlayNetworking.registerGlobalReceiver(EmcAutoNetwork.SELECTED_S2C_ID,
                (client, handler, buf, responseSender) -> {
                    final String selected = buf.readString();
                    client.execute(() -> {
                        if (activeScreen != null) activeScreen.onSelectedUpdated(selected);
                    });
                });
    }
}