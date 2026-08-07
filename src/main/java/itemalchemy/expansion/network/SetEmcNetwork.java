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
 * <p>服务端接收后：
 * <ul>
 *   <li>{@link #applyOnServer} 根据定价精度分发：
 *     <ul>
 *       <li><b>通用</b>（{@code precise=false}）：更新 {@link EMCManager#set(String, long)} 内存 map；
 *           按 scope 写 {@link PerSaveEmcStore} 或 {@link GlobalEmcStore}</li>
 *       <li><b>精确</b>（{@code precise=true}）：写入 {@link PreciseEmcStore}（变体键存储），
 *           按 scope 决定本存档或全局层。不动 {@code EMCManager.map}</li>
 *     </ul>
 *   </li>
 *   <li>{@link #resyncAll} 重同步 emc_map + precise_emc_map 给所有在线玩家</li>
 *   <li>给发送者回一条聊天消息确认</li>
 * </ul>
 * </p>
 *
 * <p><b>注意</b>：客户端发送方法在 {@code SetEmcClientNetwork}（client source set）中，
 * 因为 {@code ClientPlayNetworking} 是客户端专属 API，不能在 main source set 引用。</p>
 *
 * <p><b>安全</b>：服务端校验 {@code itemId} 非空、emc >= 0。scope 仅 0/1。
 * 权限：任意玩家可设（EMC 修改是创作向功能，单人世界无权限问题；
 * 服主若需限制可在服务端配置加 op 检查——当前实现遵循前置模组的设计：
 * {@code /itemalchemy reloademc} 也是 permissionLevel 2，但本模组面向单人/小型联机，
 * 暂不强制 op，保持易用性。需要时可在 {@code applyOnServer} 加 {@code player.hasPermissionLevel(2)} 判断）。</p>
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
     * C2S 包 id：{@code itemalchemy-expansion:reprice_confirm}。
     * <p>玩家在 RepriceConfirmScreen 选择后回送：boolean yes（true=重定价，false=保留）。</p>
     */
    public static final Identifier REPRICE_CONFIRM_ID =
            new Identifier(ItemAlchemyExpansion.MOD_ID, "reprice_confirm");

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

        // 「重新定价确认」C2S：玩家点「是」/「否」
        ServerPlayNetworking.registerGlobalReceiver(REPRICE_CONFIRM_ID, (server, player, handler, buf, responseSender) -> {
            final boolean yes = buf.readBoolean();
            server.execute(() -> handleRepriceConfirm(server, player, yes));
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
        // 1. 基础校验
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

        // 2. 规范化 itemId（带命名空间）
        String normalizedId = normalizeItemId(itemId);

        try {
            if (precise) {
                // 精确模式：写入 PreciseEmcStore（按变体键），不动 EMCManager.map
                // 变体键兜底：若客户端没传，用纯 itemId 作为变体键（无 NBT 物品等价于通用）
                String vk = (variantKey == null || variantKey.isEmpty())
                        ? normalizedId : variantKey;
                boolean global = (scope == SCOPE_GLOBAL);
                PreciseEmcStore.set(server, vk, emc, global);
                ItemAlchemyExpansion.debug("[IAExp] precise emc set server-side: {} -> {} (scope={}, global={})",
                        vk, emc, scope, global);
            } else {
                // 通用模式：原行为，更新 EMCManager.map + PerSave/Global
                EMCManager.set(normalizedId, emc);
                if (scope == SCOPE_THIS_SAVE) {
                    PerSaveEmcStore.set(server, normalizedId, emc);
                } else {
                    GlobalEmcStore.set(normalizedId, emc);
                }
            }

            // 3. 重同步给所有在线玩家（通用 + 精确）
            resyncAll(server);

            // 4. 回执
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
            // 用前置模组的同步方法（包名 itemalchemy）：通用 map
            net.pitan76.mcpitanlib.api.entity.Player mcpPlayer =
                    new net.pitan76.mcpitanlib.api.entity.Player(p);
            EMCManager.syncS2C_emc_map(mcpPlayer);
            // 精确 map：自定义 S2C 包
            pushPreciseMapTo(p);
            // 自动定价 map：自定义 S2C 包
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
     * 服务端处理「重新定价查询」：扫描 PerSaveEmcStore 候选并回 S2C 给客户端弹窗。
     *
     * <p>候选过滤规则：
     * <ul>
     *   <li>排除 {@code minecraft:*} 原版物品（上游已定义）</li>
     *   <li>排除在 {@link EMCManager#defaultEMCMap} 中的物品（上游默认值不重置）</li>
     * </ul>
     * 候选为空时不弹窗（直接置 {@code autoPricingRepricePromptShown=true} 跳过）。</p>
     */
    static void handleRepriceCheck(net.minecraft.server.MinecraftServer server,
                                   ServerPlayerEntity player) {
        // 已经弹过则不再弹（避免重复）
        if (IAExpConfigHolder.get().autoPricingRepricePromptShown) {
            ItemAlchemyExpansion.debug("[IAExp] reprice check skipped: already shown");
            return;
        }

        Map<String, Long> all = PerSaveEmcStore.getSnapshot(server);
        List<String> candidates = new ArrayList<>();
        for (Map.Entry<String, Long> e : all.entrySet()) {
            String id = e.getKey();
            if (id == null || id.isEmpty()) continue;
            if (id.startsWith("minecraft:")) continue;  // 原版不动
            try {
                if (EMCManager.defaultEMCMap != null && EMCManager.defaultEMCMap.containsKey(id)) continue;
            } catch (Throwable ignore) {}
            candidates.add(id);
        }

        if (candidates.isEmpty()) {
            // 无候选：直接标记已弹过，不弹窗
            IAExpConfigHolder.get().autoPricingRepricePromptShown = true;
            IAExpConfigHolder.save();
            ItemAlchemyExpansion.debug("[IAExp] reprice check: no candidates, marked as shown");
            return;
        }

        // 推 S2C：候选列表
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeVarInt(candidates.size());
        for (String id : candidates) buf.writeString(id);
        ServerPlayNetworking.send(player, REPRICE_CANDIDATES_ID, buf);
        ItemAlchemyExpansion.debug("[IAExp] reprice check: sent {} candidates to {}",
                candidates.size(), player.getEntityName());
    }

    /**
     * 服务端处理「重新定价确认」：玩家在 RepriceConfirmScreen 选「是」或「否」。
     *
     * <p>无论选什么，都置 {@code autoPricingRepricePromptShown=true} 写盘（对话框只弹一次）。
     * 选「是」时：扫描候选并从 PerSaveEmcStore 移除 + 强制重算自动定价。</p>
     */
    static void handleRepriceConfirm(net.minecraft.server.MinecraftServer server,
                                     ServerPlayerEntity player, boolean yes) {
        // 标记已弹过
        IAExpConfigHolder.get().autoPricingRepricePromptShown = true;
        IAExpConfigHolder.save();

        if (!yes) {
            ItemAlchemyExpansion.debug("[IAExp] reprice confirm: player chose NO, keep overrides");
            sendFeedback(player, "itemalchemy-expansion.reprice.feedback.kept");
            return;
        }

        // 扫描候选并删除
        Map<String, Long> all = PerSaveEmcStore.getSnapshot(server);
        Set<String> toRemove = new HashSet<>();
        for (String id : all.keySet()) {
            if (id == null || id.startsWith("minecraft:")) continue;
            try {
                if (EMCManager.defaultEMCMap != null && EMCManager.defaultEMCMap.containsKey(id)) continue;
            } catch (Throwable ignore) {}
            toRemove.add(id);
        }

        int removed = PerSaveEmcStore.removeAll(server, toRemove);

        // 强制重算自动定价
        try {
            RecipeAutoPricer.forceRecompute(server);
        } catch (Throwable t) {
            ItemAlchemyExpansion.LOGGER.warn("[IAExp] reprice: forceRecompute failed: {}", t.toString());
        }

        // 重同步给所有在线玩家（通用 map 已变化）
        try {
            resyncAll(server);
        } catch (Throwable ignore) {}

        sendFeedback(player, "itemalchemy-expansion.reprice.feedback.done",
                Text.literal(String.valueOf(removed)));
        ItemAlchemyExpansion.debug("[IAExp] reprice confirm: player chose YES, removed {} candidates, recomputed",
                removed);
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
