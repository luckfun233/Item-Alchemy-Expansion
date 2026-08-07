package itemalchemy.expansion.client;

import itemalchemy.expansion.ItemAlchemyExpansion;
import itemalchemy.expansion.network.AutoEmcStore;
import itemalchemy.expansion.network.PreciseEmcStore;
import itemalchemy.expansion.network.SetEmcNetwork;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.PacketByteBuf;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 「设置 EMC」C2S 包客户端发送方 + 精确 map S2C 接收方。
 *
 * <p>放在 client source set（{@code src/client/java}），因为
 * {@link ClientPlayNetworking} 是客户端专属 API，不能在 main source set 引用
 * （服务端编译期不可见）。</p>
 *
 * <p>{@link SetEmcScreen} 点击「确认」时调用 {@link #sendSetEmc} 发送 C2S 包，
 * 服务端由 {@link SetEmcNetwork#registerServer()} 接收处理。</p>
 *
 * <p>客户端通过 {@link #registerClientReceiver} 注册 S2C 接收器：
 * <ul>
 *   <li>{@link SetEmcNetwork#SYNC_PRECISE_EMC_ID}：玩家精确 map 快照（玩家上线/他人修改时触发），
 *       写入 {@link PreciseEmcStore#applyFromSnapshot} 供 {@link SetEmcScreen} 显示「当前 EMC」。</li>
 *   <li>{@link SetEmcNetwork#SYNC_AUTO_EMC_ID}：自动定价结果（精确层 + 通用层）快照，
 *       写入 {@link AutoEmcStore#applyFromSnapshot} 供 {@code MixinEMCManager} 查询 L3/L4。</li>
 *   <li>{@link SetEmcNetwork#REPRICE_CANDIDATES_ID}：候选 itemId 列表，
 *       打开 {@link RepriceConfirmScreen} 让玩家选择是否重新定价。</li>
 *   <li>{@link SetEmcNetwork#NEW_FEATURE_TOAST_ID}：升级提醒，
 *       弹 {@link NewFeatureToast} 一次。</li>
 * </ul>
 * </p>
 */
public final class SetEmcClientNetwork {

    /** 客户端缓存的当前变体键（用于 SetEmcScreen 显示「当前 EMC」时按定价精度查询） */
    private static String cachedVariantKey = "";

    private SetEmcClientNetwork() {}

    /**
     * 客户端发送设置 EMC 请求。
     *
     * @param itemId     物品 ID（如 {@code "minecraft:stone"}）
     * @param emc        新 EMC 值（>= 0）
     * @param scope      {@link SetEmcNetwork#SCOPE_THIS_SAVE} 或 {@link SetEmcNetwork#SCOPE_GLOBAL}
     * @param precise    是否写入精确存储（按变体键）；false=走通用存储（按 id）
     * @param variantKey 精确模式下的变体键存储串（{@code ItemVariantKey.toStorageString()}）；
     *                   通用模式下传空串
     */
    public static void sendSetEmc(String itemId, long emc, int scope, boolean precise, String variantKey) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(itemId);
        buf.writeLong(emc);
        buf.writeVarInt(scope);
        buf.writeBoolean(precise);
        buf.writeString(variantKey == null ? "" : variantKey);
        ClientPlayNetworking.send(SetEmcNetwork.SET_EMC_ID, buf);
    }

    /**
     * 客户端发送「重新定价查询」C2S 包。
     *
     * <p>玩家在 Cloth Config GUI 把「配方自动定价」从关切换为开并保存后调用。
     * 服务端扫描 PerSaveEmcStore 候选并回 {@link SetEmcNetwork#REPRICE_CANDIDATES_ID}。</p>
     */
    public static void sendRepriceCheck() {
        try {
            PacketByteBuf buf = PacketByteBufs.create();
            ClientPlayNetworking.send(SetEmcNetwork.REPRICE_CHECK_ID, buf);
            ItemAlchemyExpansion.debug("[IAExp] reprice check sent to server");
        } catch (Throwable t) {
            ItemAlchemyExpansion.LOGGER.warn("[IAExp] Failed to send reprice check: {}", t.toString());
        }
    }

    /**
     * 客户端发送「重新定价确认」C2S 包。
     *
     * @param yes true=重新自动定价（删除候选 + 重算），false=保留玩家覆盖
     */
    public static void sendRepriceConfirm(boolean yes) {
        try {
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeBoolean(yes);
            ClientPlayNetworking.send(SetEmcNetwork.REPRICE_CONFIRM_ID, buf);
            ItemAlchemyExpansion.debug("[IAExp] reprice confirm sent: yes={}", yes);
        } catch (Throwable t) {
            ItemAlchemyExpansion.LOGGER.warn("[IAExp] Failed to send reprice confirm: {}", t.toString());
        }
    }

    /** 缓存当前打开 SetEmcScreen 时算出的变体键（供其它客户端逻辑使用） */
    public static void setCachedVariantKey(String vk) {
        cachedVariantKey = vk == null ? "" : vk;
    }

    /** 读取缓存的变体键 */
    public static String getCachedVariantKey() {
        return cachedVariantKey;
    }

    /**
     * 客户端注册所有 S2C 接收器：精确 map 同步、重新定价候选、新功能 toast。
     *
     * <p>应在 ClientModInitializer 中调用一次。</p>
     */
    public static void registerClientReceiver() {
        // 1. 玩家精确 map 同步
        ClientPlayNetworking.registerGlobalReceiver(SetEmcNetwork.SYNC_PRECISE_EMC_ID,
                (client, handler, buf, responseSender) -> {
                    Map<String, Long> snapshot = SetEmcNetwork.readEmcMap(buf);
                    client.execute(() -> {
                        try {
                            PreciseEmcStore.applyFromSnapshot(snapshot);
                            ItemAlchemyExpansion.debug("[IAExp] precise emc map synced from server: {} entries",
                                    snapshot.size());
                        } catch (Throwable t) {
                            ItemAlchemyExpansion.LOGGER.warn("[IAExp] Failed to apply precise emc snapshot: {}",
                                    t.toString());
                        }
                    });
                });

        // 2. 自动定价结果同步（精确层 + 通用层）
        ClientPlayNetworking.registerGlobalReceiver(SetEmcNetwork.SYNC_AUTO_EMC_ID,
                (client, handler, buf, responseSender) -> {
                    Map<String, Long> precise = SetEmcNetwork.readEmcMap(buf);
                    Map<String, Long> general = SetEmcNetwork.readEmcMap(buf);
                    client.execute(() -> {
                        try {
                            AutoEmcStore.applyFromSnapshot(precise, general);
                            ItemAlchemyExpansion.debug("[IAExp] auto emc map synced from server: precise={} entries, general={} entries",
                                    precise.size(), general.size());
                        } catch (Throwable t) {
                            ItemAlchemyExpansion.LOGGER.warn("[IAExp] Failed to apply auto emc snapshot: {}",
                                    t.toString());
                        }
                    });
                });

        // 3. 重新定价候选列表 → 打开 RepriceConfirmScreen
        ClientPlayNetworking.registerGlobalReceiver(SetEmcNetwork.REPRICE_CANDIDATES_ID,
                (client, handler, buf, responseSender) -> {
                    int size = buf.readVarInt();
                    List<String> candidates = new ArrayList<>(size);
                    for (int i = 0; i < size; i++) {
                        candidates.add(buf.readString());
                    }
                    client.execute(() -> {
                        try {
                            MinecraftClient mc = MinecraftClient.getInstance();
                            if (mc == null || mc.player == null) return;
                            // 在客户端主线程打开屏幕
                            mc.setScreen(new RepriceConfirmScreen(candidates));
                            ItemAlchemyExpansion.debug("[IAExp] reprice candidates received: {} items, opened RepriceConfirmScreen",
                                    candidates.size());
                        } catch (Throwable t) {
                            ItemAlchemyExpansion.LOGGER.warn("[IAExp] Failed to open RepriceConfirmScreen: {}",
                                    t.toString());
                        }
                    });
                });

        // 4. 新功能 toast（升级提醒）
        ClientPlayNetworking.registerGlobalReceiver(SetEmcNetwork.NEW_FEATURE_TOAST_ID,
                (client, handler, buf, responseSender) -> {
                    client.execute(() -> {
                        try {
                            NewFeatureToast.show();
                        } catch (Throwable t) {
                            ItemAlchemyExpansion.LOGGER.warn("[IAExp] Failed to show new feature toast: {}",
                                    t.toString());
                        }
                    });
                });
    }
}
