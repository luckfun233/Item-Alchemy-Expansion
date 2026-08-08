package itemalchemy.expansion.network;

import itemalchemy.expansion.ItemAlchemyExpansion;
import itemalchemy.expansion.config.IAExpConfig;
import itemalchemy.expansion.config.IAExpConfigHolder;
import itemalchemy.expansion.recipe.RecipeAutoPricer;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.pitan76.itemalchemy.EMCManager;
import net.pitan76.itemalchemy.ItemAlchemy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「设置物品 EMC」网络包：C2S。
 *
 * <p>客户端 {@code SetEmcScreen} 玩家点击「确认」时发送：
 * <pre>{ itemId: String, emc: long, scope: varint (0=本存档, 1=全局),
 *         precise: boolean, variantKey: String }</pre></p>
 *
 * <p>服务端接收后由 {@link #applyOnServer} 根据定价精度分发：
 * <ul>
 *   <li><b>通用</b>（{@code precise=false}）：更新 {@link EMCManager#set(String, long)} 内存 map，
 *       按 scope 写 {@link PerSaveEmcStore} 或 {@link GlobalEmcStore}</li>
 *   <li><b>精确</b>（{@code precise=true}）：写入 {@link PreciseEmcStore}（按变体键存储），
 *       按 scope 决定本存档或全局层，不动 {@code EMCManager.map}</li>
 * </ul>
 * 再由 {@link #resyncAll} 重同步 emc_map + precise_emc_map 给所有在线玩家，并给发送者回一条聊天消息确认。</p>
 *
 * <p><b>注意</b>：客户端发送方法在 {@code SetEmcClientNetwork}（client source set）中，
 * 因为 {@code ClientPlayNetworking} 是客户端专属 API，不能在 main source set 引用。</p>
 *
 * <p><b>安全</b>：服务端校验 {@code itemId} 非空、emc >= 0，scope 仅 0/1。
 * 任意玩家可设（EMC 修改是创作向功能）。服主若需限制可在 {@code applyOnServer} 加 {@code player.hasPermissionLevel(2)} 判断。</p>
 */
public final class SetEmcNetwork {

    /** C2S 包 id：{@code itemalchemy-expansion:set_emc} */
    public static final Identifier SET_EMC_ID = new Identifier(ItemAlchemyExpansion.MOD_ID, "set_emc");

    /** S2C 包 id：{@code itemalchemy-expansion:sync_precise_emc}（玩家精确 map 同步） */
    public static final Identifier SYNC_PRECISE_EMC_ID =
            new Identifier(ItemAlchemyExpansion.MOD_ID, "sync_precise_emc");

    /**
     * S2C 包 id：{@code itemalchemy-expansion:sync_auto_emc}（自动定价结果同步）。
     * <p>格式：varint preciseSize + (string key + long value) * N
     *       + varint generalSize + (string key + long value) * N</p>
     */
    public static final Identifier SYNC_AUTO_EMC_ID =
            new Identifier(ItemAlchemyExpansion.MOD_ID, "sync_auto_emc");

    /**
     * C2S 包 id：{@code itemalchemy-expansion:reprice_check}。
     * <p>客户端请求服务端扫描 PerSaveEmcStore 候选（玩家开启自动定价时触发）。</p>
     */
    public static final Identifier REPRICE_CHECK_ID =
            new Identifier(ItemAlchemyExpansion.MOD_ID, "reprice_check");

    /**
     * S2C 包 id：{@code itemalchemy-expansion:reprice_candidates}。
     * <p>服务端回客户端：候选 itemId 列表（varint size + N 个 string）。</p>
     */
    public static final Identifier REPRICE_CANDIDATES_ID =
            new Identifier(ItemAlchemyExpansion.MOD_ID, "reprice_candidates");

    /**
     * C2S 包 id：{@code itemalchemy-expansion:reprice_selective}。
     * <p>玩家在 RepriceConfirmScreen 逐个勾选后回送选择「重算」的条目：
     * <pre>varint generalCount + N 个 string(itemId)
     *       + varint preciseCount + N 个 string(vkStr)</pre>
     * 服务端删除这些条目后强制重算自动定价。</p>
     */
    public static final Identifier REPRICE_SELECTIVE_ID =
            new Identifier(ItemAlchemyExpansion.MOD_ID, "reprice_selective");

    /**
     * S2C 包 id：{@code itemalchemy-expansion:new_feature_toast}。
     * <p>服务端推客户端：弹一次升级 toast（仅 upgradedFromLegacy && !featureNoticeShown）。</p>
     */
    public static final Identifier NEW_FEATURE_TOAST_ID =
            new Identifier(ItemAlchemyExpansion.MOD_ID, "new_feature_toast");

    /** scope 常量 */
    public static final int SCOPE_THIS_SAVE = 0;
    public static final int SCOPE_GLOBAL = 1;

    private SetEmcNetwork() {}

    /** 服务端注册接收器。在 {@code onInitialize}（服务端）中调用。 */
    public static void registerServer() {
        // 「设置 EMC」C2S
        ServerPlayNetworking.registerGlobalReceiver(SET_EMC_ID, (server, player, handler, buf, responseSender) -> {
            final String itemId = buf.readString();
            final long emc = buf.readLong();
            final int scope = buf.readVarInt();
            final boolean precise = buf.readBoolean();
            final String variantKey = buf.readString();

            server.execute(() -> applyOnServer(server, player, itemId, emc, scope, precise, variantKey));
        });

        // 「重新定价查询」C2S：扫描 PerSaveEmcStore 候选
        ServerPlayNetworking.registerGlobalReceiver(REPRICE_CHECK_ID, (server, player, handler, buf, responseSender) -> {
            server.execute(() -> handleRepriceCheck(server, player));
        });

        // 「重新定价逐个选择」C2S：玩家勾选「重算」的条目（通用层 itemId + 精确层变体键）
        ServerPlayNetworking.registerGlobalReceiver(REPRICE_SELECTIVE_ID, (server, player, handler, buf, responseSender) -> {
            final int generalCount = buf.readVarInt();
            final List<String> generalIds = new ArrayList<>(generalCount);
            for (int i = 0; i < generalCount; i++) generalIds.add(buf.readString());
            final int preciseCount = buf.readVarInt();
            final List<String> preciseVkStrs = new ArrayList<>(preciseCount);
            for (int i = 0; i < preciseCount; i++) preciseVkStrs.add(buf.readString());
            server.execute(() -> handleRepriceSelective(server, player, generalIds, preciseVkStrs));
        });
    }

    // ============ 服务端处理 ============

    /**
     * 服务端核心逻辑：根据定价精度分发到精确或通用存储，持久化 + 重同步 + 通知玩家。
     *
     * <p>注意：此方法在服务端主线程执行（通过 {@code server.execute}）。</p>
     */
    static void applyOnServer(net.minecraft.server.MinecraftServer server,
                              ServerPlayerEntity player,
                              String itemId, long emc, int scope,
                              boolean precise, String variantKey) {
        if (itemId == null || itemId.isEmpty()) {
            sendFeedback(player, "itemalchemy-expansion.set_emc.fail.invalid_id");
            return;
        }
        if (emc < 0) {
            sendFeedback(player, "itemalchemy-expansion.set_emc.fail.negative");
            return;
        }
        if (scope != SCOPE_THIS_SAVE && scope != SCOPE_GLOBAL) {
            sendFeedback(player, "itemalchemy-expansion.set_emc.fail.invalid_scope");
            return;
        }

        String normalizedId = normalizeItemId(itemId);

        try {
            if (precise) {
                // 精确模式：写入 PreciseEmcStore（按变体键），不动 EMCManager.map
                // 变体键兜底：客户端未传时用纯 itemId 作为变体键（无 NBT 物品等价于通用）
                String vk = (variantKey == null || variantKey.isEmpty())
                        ? normalizedId : variantKey;
                boolean global = (scope == SCOPE_GLOBAL);
                PreciseEmcStore.set(server, vk, emc, global);
                ItemAlchemyExpansion.debug("[IAExp] precise emc set server-side: {} -> {} (scope={}, global={})",
                        vk, emc, scope, global);
            } else {
                EMCManager.set(normalizedId, emc);
                if (scope == SCOPE_THIS_SAVE) {
                    PerSaveEmcStore.set(server, normalizedId, emc);
                } else {
                    GlobalEmcStore.set(normalizedId, emc);
                }
            }

            resyncAll(server);

            sendFeedback(player, "itemalchemy-expansion.set_emc.success",
                    Text.literal(normalizedId), Text.literal(String.valueOf(emc)),
                    Text.translatable(scope == SCOPE_THIS_SAVE
                            ? "itemalchemy-expansion.set_emc.scope.this_save"
                            : "itemalchemy-expansion.set_emc.scope.global"));
        } catch (Throwable t) {
            ItemAlchemyExpansion.LOGGER.error("[IAExp] set_emc failed for {} (emc={}, scope={}, precise={})",
                    normalizedId, emc, scope, precise, t);
            sendFeedback(player, "itemalchemy-expansion.set_emc.fail.exception");
        }
    }

    /** 更新内存中的 map 并把当前所有条目同步给在线玩家（通用 + 精确 + 自动） */
    static void resyncAll(net.minecraft.server.MinecraftServer server) {
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            // 通用 map 用前置模组的同步方法，精确/自动定价 map 用自定义 S2C 包
            net.pitan76.mcpitanlib.api.entity.Player mcpPlayer =
                    new net.pitan76.mcpitanlib.api.entity.Player(p);
            EMCManager.syncS2C_emc_map(mcpPlayer);
            pushPreciseMapTo(p);
            pushAutoEmcMapTo(p);
        }
    }

    /**
     * 公开入口：把当前所有 EMC map（通用 + 精确 + 自动）同步给在线玩家。
     *
     * <p>供 {@link itemalchemy.expansion.command.RepriceCommand} 在命令执行后重同步使用。
     * 内部转发到 {@link #resyncAll}（包级私有）。</p>
     */
    public static void resyncAllPublic(net.minecraft.server.MinecraftServer server) {
        resyncAll(server);
    }

    /** 把当前精确覆盖 map 同步给单个玩家（S2C）。玩家加入或他人修改时调用。 */
    public static void pushPreciseMapTo(ServerPlayerEntity player) {
        PacketByteBuf buf = PacketByteBufs.create();
        writeEmcMap(buf, PreciseEmcStore.snapshot());
        ServerPlayNetworking.send(player, SYNC_PRECISE_EMC_ID, buf);
    }

    /**
     * 把当前自动定价结果（精确层 + 通用层）同步给单个玩家（S2C）。
     *
     * <p>玩家加入时、{@code /reprice} 命令执行后、他人修改后调用。
     * 客户端收到后写入 {@link AutoEmcStore#applyFromSnapshot}。</p>
     */
    public static void pushAutoEmcMapTo(ServerPlayerEntity player) {
        PacketByteBuf buf = PacketByteBufs.create();
        writeEmcMap(buf, AutoEmcStore.snapshotPrecise());
        writeEmcMap(buf, AutoEmcStore.snapshotGeneral());
        ServerPlayNetworking.send(player, SYNC_AUTO_EMC_ID, buf);
    }

    /**
     * 把 {@code Map<String, Long>} 编码到 PacketByteBuf：varint size + N 个 (string key + long value)。
     *
     * <p>供 S2C 推送（精确/自动/通用 map）复用，与 {@link #readEmcMap} 配对。</p>
     */
    public static void writeEmcMap(PacketByteBuf buf, java.util.Map<String, Long> map) {
        buf.writeVarInt(map.size());
        for (java.util.Map.Entry<String, Long> e : map.entrySet()) {
            buf.writeString(e.getKey());
            buf.writeLong(e.getValue());
        }
    }

    /**
     * 从 PacketByteBuf 读取 {@code Map<String, Long>}：varint size + N 个 (string key + long value)。
     *
     * <p>供 C2S/S2C 接收端复用，与 {@link #writeEmcMap} 配对。返回 {@link LinkedHashMap} 保持插入顺序。</p>
     */
    public static java.util.Map<String, Long> readEmcMap(PacketByteBuf buf) {
        int size = buf.readVarInt();
        java.util.Map<String, Long> map = new LinkedHashMap<>(size);
        for (int i = 0; i < size; i++) {
            map.put(buf.readString(), buf.readLong());
        }
        return map;
    }

    // ============ 重新定价（reprice）流程 ============

    /**
     * 服务端处理「重新定价查询」：扫描通用层（PerSaveEmcStore）+ 精确层（PreciseEmcStore 本存档层）
     * 的手动定价候选，回 S2C {@link #REPRICE_CANDIDATES_ID} 给客户端弹逐个选择对话框。
     *
     * <p>候选过滤规则（两层共用）：
     * <ul>
     *   <li>排除 {@code minecraft:*} 原版物品（上游已定义）</li>
     *   <li>排除在 {@link EMCManager#defaultEMCMap} 中的物品（上游默认值不重置）</li>
     * </ul>
     * 精确层 key 为变体键（{@code itemId\u0001nbt}），过滤时取其 itemId 部分判定。</p>
     *
     * <p>S2C 协议：{@code varint generalCount + N×(string itemId + long oldEmc)
     *              + varint preciseCount + N×(string vkStr + long oldEmc)}。
     * 两层均空时直接置 {@code autoPricingRepricePromptShown=true} 跳过，不弹窗。</p>
     */
    public static void handleRepriceCheck(net.minecraft.server.MinecraftServer server,
                                          ServerPlayerEntity player) {
        // 已经弹过则不再弹（避免重复）
        if (IAExpConfigHolder.get().autoPricingRepricePromptShown) {
            ItemAlchemyExpansion.debug("[IAExp] reprice check skipped: already shown");
            return;
        }

        // 通用层候选（PerSaveEmcStore：按 itemId 存储）
        List<Map.Entry<String, Long>> generalCandidates = new ArrayList<>();
        for (Map.Entry<String, Long> e : PerSaveEmcStore.getSnapshot(server).entrySet()) {
            String id = e.getKey();
            if (id == null || id.isEmpty() || !isRepriceCandidate(id)) continue;
            generalCandidates.add(e);
        }

        // 精确层候选（PreciseEmcStore 本存档层：按变体键存储）
        List<Map.Entry<String, Long>> preciseCandidates = new ArrayList<>();
        for (Map.Entry<String, Long> e : PreciseEmcStore.getSaveLayerSnapshot().entrySet()) {
            String vkStr = e.getKey();
            if (vkStr == null || vkStr.isEmpty()) continue;
            if (!isRepriceCandidate(extractItemId(vkStr))) continue;
            preciseCandidates.add(e);
        }

        if (generalCandidates.isEmpty() && preciseCandidates.isEmpty()) {
            // 无候选：直接标记已弹过，不弹窗
            IAExpConfigHolder.get().autoPricingRepricePromptShown = true;
            IAExpConfigHolder.save();
            ItemAlchemyExpansion.debug("[IAExp] reprice check: no candidates, marked as shown");
            return;
        }

        // 推 S2C：通用层 + 精确层候选（含旧 EMC 供 UI 展示「旧: X」）
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeVarInt(generalCandidates.size());
        for (Map.Entry<String, Long> e : generalCandidates) {
            buf.writeString(e.getKey());
            buf.writeLong(e.getValue());
        }
        buf.writeVarInt(preciseCandidates.size());
        for (Map.Entry<String, Long> e : preciseCandidates) {
            buf.writeString(e.getKey());
            buf.writeLong(e.getValue());
        }
        ServerPlayNetworking.send(player, REPRICE_CANDIDATES_ID, buf);
        ItemAlchemyExpansion.debug("[IAExp] reprice check: sent {} general + {} precise candidates to {}",
                generalCandidates.size(), preciseCandidates.size(), player.getEntityName());
    }

    /**
     * 服务端处理「重新定价逐个选择」：玩家在 RepriceConfirmScreen 逐个勾选后回送。
     *
     * <p>玩家勾选「重算」的条目从对应存储移除：通用层走 {@link PerSaveEmcStore#removeAll}
     * （同步重置内存 map），精确层走 {@link PreciseEmcStore#removeAllFromSaveLayer}。
     * 然后强制重算自动定价并重同步给所有在线玩家。未勾选的条目保留原手动值。
     * 无论结果如何都置 {@code autoPricingRepricePromptShown=true} 写盘（对话框只弹一次）。</p>
     */
    static void handleRepriceSelective(net.minecraft.server.MinecraftServer server,
                                       ServerPlayerEntity player,
                                       List<String> generalIds, List<String> preciseVkStrs) {
        IAExpConfigHolder.get().autoPricingRepricePromptShown = true;
        IAExpConfigHolder.save();

        int removedGeneral = PerSaveEmcStore.removeAll(server, new HashSet<>(generalIds));
        int removedPrecise = PreciseEmcStore.removeAllFromSaveLayer(server, new HashSet<>(preciseVkStrs));
        int removed = removedGeneral + removedPrecise;

        if (removed == 0) {
            ItemAlchemyExpansion.debug("[IAExp] reprice selective: nothing removed, keep overrides");
            sendFeedback(player, "itemalchemy-expansion.reprice.feedback.kept");
            return;
        }

        try {
            RecipeAutoPricer.forceRecompute(server);
        } catch (Throwable t) {
            ItemAlchemyExpansion.LOGGER.warn("[IAExp] reprice selective: forceRecompute failed: {}", t.toString());
        }
        try {
            resyncAll(server);
        } catch (Throwable ignore) {}

        sendFeedback(player, "itemalchemy-expansion.reprice.feedback.done",
                Text.literal(String.valueOf(removed)));
        ItemAlchemyExpansion.debug("[IAExp] reprice selective: removed {} (general={} precise={}), recomputed",
                removed, removedGeneral, removedPrecise);
    }

    /** 判断 itemId 是否为重新定价候选：排除原版与上游已定义（不重置这些）。 */
    private static boolean isRepriceCandidate(String itemId) {
        if (itemId == null || itemId.startsWith("minecraft:")) return false;
        try {
            if (EMCManager.defaultEMCMap != null && EMCManager.defaultEMCMap.containsKey(itemId)) return false;
        } catch (Throwable ignore) {}
        return true;
    }

    /** 从变体键存储串提取 itemId（兼容纯 ID 与 {@code itemId\u0001nbt} 格式）。 */
    private static String extractItemId(String vkStr) {
        int idx = vkStr.indexOf(itemalchemy.expansion.nbt.ItemVariantKey.SEPARATOR);
        return idx < 0 ? vkStr : vkStr.substring(0, idx);
    }

    /**
     * 推送「新功能」toast 给玩家（S2C，空包，仅触发信号）。
     *
     * <p>在玩家加入时若 {@link IAExpConfigHolder#wasUpgradedFromLegacy()} 返回 true
     * 且 {@code featureNoticeShown=false} 时调用。客户端收到后弹 {@code NewFeatureToast}。</p>
     */
    public static void pushNewFeatureToast(ServerPlayerEntity player) {
        PacketByteBuf buf = PacketByteBufs.create();
        // 空包：仅作为触发信号
        ServerPlayNetworking.send(player, NEW_FEATURE_TOAST_ID, buf);
    }

    static String normalizeItemId(String id) {
        if (!id.contains(":")) return "minecraft:" + id;
        return id;
    }

    static void sendFeedback(ServerPlayerEntity player, String key, Text... args) {
        player.sendMessage(Text.translatable(key, (Object[]) args), false);
    }

    // 防止误用：保留对前置模组 ItemAlchemy 的引用以触发类初始化检查
    @SuppressWarnings("unused")
    private static final String PRECONDITION_MOD_ID = ItemAlchemy.MOD_ID;
}
