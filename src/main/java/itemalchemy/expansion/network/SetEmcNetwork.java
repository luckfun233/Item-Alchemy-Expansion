package itemalchemy.expansion.network;

import itemalchemy.expansion.ItemAlchemyExpansion;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.pitan76.itemalchemy.EMCManager;
import net.pitan76.itemalchemy.ItemAlchemy;

import java.util.Map;

/**
 * 「设置物品 EMC」网络包：C2S。
 *
 * <p>客户端 {@code SetEmcScreen} 玩家点击「确认」时发送：
 * <pre>{ itemId: String, emc: long, scope: int (0=本存档, 1=全局) }</pre></p>
 *
 * <p>服务端接收后：
 * <ul>
 *   <li>{@link #applyOnServer} 更新内存 map（{@link EMCManager#set(String, long)}）</li>
 *   <li>根据 scope：
 *     <ul>
 *       <li>本存档：写入 {@code <world>/itemalchemy_expansion_overrides.json}（见 {@link PerSaveEmcStore}）</li>
 *       <li>全局：写入 {@code config/itemalchemy/emc_config.json}（前置模组自带文件）</li>
 *     </ul>
 *   </li>
 *   <li>{@link #resyncAll} 重同步 emc_map 给当前服务器所有玩家（含发送者）</li>
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

    /** scope 常量 */
    public static final int SCOPE_THIS_SAVE = 0;
    public static final int SCOPE_GLOBAL = 1;

    private SetEmcNetwork() {}

    /** 服务端注册接收器。在 {@code onInitialize}（服务端）中调用。 */
    public static void registerServer() {
        ServerPlayNetworking.registerGlobalReceiver(SET_EMC_ID, (server, player, handler, buf, responseSender) -> {
            final String itemId = buf.readString();
            final long emc = buf.readLong();
            final int scope = buf.readVarInt();

            server.execute(() -> applyOnServer(server, player, itemId, emc, scope));
        });
    }

    // ============ 服务端处理 ============

    /**
     * 服务端核心逻辑：更新内存 + 持久化 + 重同步 + 通知玩家。
     *
     * <p>注意：此方法在服务端主线程执行（通过 {@code server.execute}）。</p>
     */
    static void applyOnServer(net.minecraft.server.MinecraftServer server,
                              ServerPlayerEntity player,
                              String itemId, long emc, int scope) {
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
            // 3. 更新内存 map（EMCManager.set 会 replace 或 put）
            EMCManager.set(normalizedId, emc);

            // 4. 持久化
            if (scope == SCOPE_THIS_SAVE) {
                PerSaveEmcStore.set(server, normalizedId, emc);
            } else {
                // 全局：写 emc_config.json + 同步 config 对象
                GlobalEmcStore.set(normalizedId, emc);
            }

            // 5. 重同步给所有在线玩家
            resyncAll(server);

            // 6. 回执
            sendFeedback(player, "itemalchemy-expansion.set_emc.success",
                    Text.literal(normalizedId), Text.literal(String.valueOf(emc)),
                    Text.translatable(scope == SCOPE_THIS_SAVE
                            ? "itemalchemy-expansion.set_emc.scope.this_save"
                            : "itemalchemy-expansion.set_emc.scope.global"));
        } catch (Throwable t) {
            ItemAlchemyExpansion.LOGGER.error("[IAExp] set_emc failed for {} (emc={}, scope={})",
                    normalizedId, emc, scope, t);
            sendFeedback(player, "itemalchemy-expansion.set_emc.fail.exception");
        }
    }

    /** 更新内存中的 map 并把当前所有条目同步给在线玩家 */
    static void resyncAll(net.minecraft.server.MinecraftServer server) {
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            // 用前置模组的同步方法（包名 itemalchemy）
            net.pitan76.mcpitanlib.api.entity.Player mcpPlayer =
                    new net.pitan76.mcpitanlib.api.entity.Player(p);
            EMCManager.syncS2C_emc_map(mcpPlayer);
        }
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
