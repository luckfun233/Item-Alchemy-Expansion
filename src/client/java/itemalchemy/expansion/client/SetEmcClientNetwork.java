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
 * <p>{@link #registerClientReceiver} 注册的 S2C 接收器：精确 map 同步
 * （{@code SYNC_PRECISE_EMC_ID}，写入 {@link PreciseEmcStore}）、自动定价结果同步
 * （{@code SYNC_AUTO_EMC_ID}，写入 {@link AutoEmcStore} 供 {@code MixinEMCManager} 查询 L3/L4）、
 * 重新定价候选（{@code REPRICE_CANDIDATES_ID}，打开 {@link RepriceConfirmScreen}）、
 * 升级提醒（{@code NEW_FEATURE_TOAST_ID}，弹 {@link NewFeatureToast} 一次）。</p>
 */
public final class SetEmcClientNetwork {

    /** 客户端缓存的当前变体键（用于 SetEmcScreen 显示「当前 EMC」时按定价精度查询） */
    private static String cachedVariantKey = "";

    /**
     * 待处理的 L1 候选查询回调（设通用价前查询该 ID 是否有 L1 精确覆盖）。
     *
     * <p>SetEmcScreen 发送查询包前设置此回调，收到 S2C 结果后调用并清空。
     * 回调参数为候选 map（空 map 表示无 L1 覆盖，可直接设通用价）。</p>
     */
    private static java.util.function.Consumer<Map<String, Long>> pendingPreciseQueryCallback;

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
     * @param preciseVkStrsToClear 仅 GENERAL 模式生效：要清除的 L1 精确变体键列表
     *                             （用户在确认框逐个勾选）；空列表=保留所有 L1。PRECISE 模式忽略此值
     */
    public static void sendSetEmc(String itemId, long emc, int scope, boolean precise,
                                  String variantKey, java.util.List<String> preciseVkStrsToClear) {
        itemalchemy.expansion.ItemAlchemyExpansion.debug(
                "[IAExp][SetEmc][C2S] sendSetEmc: itemId='{}', emc={}, scope={}, precise={}, variantKey='{}', clearCount={}",
                itemId, emc, scope, precise, variantKey,
                preciseVkStrsToClear == null ? 0 : preciseVkStrsToClear.size());
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(itemId);
        buf.writeLong(emc);
        buf.writeVarInt(scope);
        buf.writeBoolean(precise);
        buf.writeString(variantKey == null ? "" : variantKey);
        if (preciseVkStrsToClear == null) {
            buf.writeVarInt(0);
        } else {
            buf.writeVarInt(preciseVkStrsToClear.size());
            for (String vk : preciseVkStrsToClear) buf.writeString(vk == null ? "" : vk);
        }
        ClientPlayNetworking.send(SetEmcNetwork.SET_EMC_ID, buf);
    }

    /**
     * 客户端发送「查询 itemId 的 L1 精确覆盖」C2S 包。
     *
     * <p>设通用价前调用，服务端返回该 ID 的所有 L1 变体条目。
     * 客户端收到 {@link SetEmcNetwork#PRECISE_BY_ITEM_RESULT_ID} 后，
     * 若非空则弹覆盖确认框。</p>
     */
    public static void sendQueryPreciseByItem(String itemId) {
        try {
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeString(itemId == null ? "" : itemId);
            ClientPlayNetworking.send(SetEmcNetwork.QUERY_PRECISE_BY_ITEM_ID, buf);
            ItemAlchemyExpansion.debug("[IAExp] query precise by item sent: itemId='{}'", itemId);
        } catch (Throwable t) {
            ItemAlchemyExpansion.LOGGER.warn("[IAExp] Failed to send query precise by item: {}", t.toString());
        }
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
     * 客户端发送「重新定价逐个选择」C2S 包：把玩家勾选「重算」的条目回送服务端。
     *
     * @param generalIds    通用层选择重算的 itemId 列表
     * @param preciseVkStrs 精确层选择重算的变体键列表
     */
    public static void sendRepriceSelective(List<String> generalIds, List<String> preciseVkStrs) {
        try {
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeVarInt(generalIds.size());
            for (String id : generalIds) buf.writeString(id);
            buf.writeVarInt(preciseVkStrs.size());
            for (String vk : preciseVkStrs) buf.writeString(vk);
            ClientPlayNetworking.send(SetEmcNetwork.REPRICE_SELECTIVE_ID, buf);
            ItemAlchemyExpansion.debug("[IAExp] reprice selective sent: general={} precise={}",
                    generalIds.size(), preciseVkStrs.size());
        } catch (Throwable t) {
            ItemAlchemyExpansion.LOGGER.warn("[IAExp] Failed to send reprice selective: {}", t.toString());
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
     * 设置待处理的 L1 候选查询回调。
     *
     * <p>SetEmcScreen 在发送查询包前调用，收到 S2C 结果后自动触发并清空。</p>
     */
    public static void setPendingPreciseQueryCallback(java.util.function.Consumer<Map<String, Long>> cb) {
        pendingPreciseQueryCallback = cb;
    }

    /**
     * 客户端注册所有 S2C 接收器：精确 map 同步、重新定价候选、新功能 toast。
     *
     * <p>应在 ClientModInitializer 中调用一次。</p>
     */
    public static void registerClientReceiver() {
        // 玩家精确 map 同步
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

        // 自动定价结果同步（精确层 + 通用层）
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

        // 重新定价候选列表（通用层 + 精确层）→ 打开 RepriceConfirmScreen 逐个选择
        ClientPlayNetworking.registerGlobalReceiver(SetEmcNetwork.REPRICE_CANDIDATES_ID,
                (client, handler, buf, responseSender) -> {
                    int generalCount = buf.readVarInt();
                    List<RepriceConfirmScreen.RepriceEntry> entries = new ArrayList<>(generalCount);
                    for (int i = 0; i < generalCount; i++) {
                        String id = buf.readString();
                        long oldEmc = buf.readLong();
                        entries.add(RepriceConfirmScreen.RepriceEntry.general(id, oldEmc));
                    }
                    int preciseCount = buf.readVarInt();
                    for (int i = 0; i < preciseCount; i++) {
                        String vkStr = buf.readString();
                        long oldEmc = buf.readLong();
                        entries.add(RepriceConfirmScreen.RepriceEntry.precise(vkStr, oldEmc));
                    }
                    client.execute(() -> {
                        try {
                            MinecraftClient mc = MinecraftClient.getInstance();
                            if (mc == null || mc.player == null) return;
                            // 在客户端主线程打开逐个选择屏幕
                            mc.setScreen(new RepriceConfirmScreen(entries));
                            ItemAlchemyExpansion.debug("[IAExp] reprice candidates received: {} general + {} precise, opened RepriceConfirmScreen",
                                    generalCount, preciseCount);
                        } catch (Throwable t) {
                            ItemAlchemyExpansion.LOGGER.warn("[IAExp] Failed to open RepriceConfirmScreen: {}",
                                    t.toString());
                        }
                    });
                });

        // 新功能 toast（升级提醒）
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

        // L1 候选查询结果（设通用价前询问是否有精确覆盖）
        ClientPlayNetworking.registerGlobalReceiver(SetEmcNetwork.PRECISE_BY_ITEM_RESULT_ID,
                (client, handler, buf, responseSender) -> {
                    Map<String, Long> variants = SetEmcNetwork.readEmcMap(buf);
                    client.execute(() -> {
                        try {
                            java.util.function.Consumer<Map<String, Long>> cb = pendingPreciseQueryCallback;
                            pendingPreciseQueryCallback = null;
                            if (cb != null) {
                                cb.accept(variants);
                                ItemAlchemyExpansion.debug("[IAExp] precise by item result received: {} variants, callback invoked",
                                        variants.size());
                            } else {
                                ItemAlchemyExpansion.debug("[IAExp] precise by item result received: {} variants, no callback (dropped)",
                                        variants.size());
                            }
                        } catch (Throwable t) {
                            ItemAlchemyExpansion.LOGGER.warn("[IAExp] Failed to handle precise by item result: {}",
                                    t.toString());
                        }
                    });
                });
    }
}
