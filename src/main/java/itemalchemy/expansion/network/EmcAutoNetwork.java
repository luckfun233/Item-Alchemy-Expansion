package itemalchemy.expansion.network;

import itemalchemy.expansion.ItemAlchemyExpansion;
import itemalchemy.expansion.block.EmcEmitterBlockEntity;
import itemalchemy.expansion.config.IAExpConfigHolder;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.pitan76.itemalchemy.api.PlayerRegisteredItemUtil;
import net.pitan76.mcpitanlib.api.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * 自动装置（EMC 转能器 / EMC 输出器）网络层。
 *
 * <p>目前用于 EMC 输出器：右键方块 → 服务端 S2C 打开选择界面；客户端请求打开者转换桌列表
 * （按「打开者」取列表）；设置所选物品后存入方块 NBT（共享，他人可见）。</p>
 */
public final class EmcAutoNetwork {

    /** S2C：打开输出器选择界面（携带方块位置） */
    public static final Identifier OPEN_ID =
            new Identifier(ItemAlchemyExpansion.MOD_ID, "emc_emitter_open");
    /** C2S：请求列表（携带方块位置） */
    public static final Identifier REQ_ID =
            new Identifier(ItemAlchemyExpansion.MOD_ID, "emc_emitter_req");
    /** C2S：设置所选物品（携带方块位置 + 变体键） */
    public static final Identifier SET_ID =
            new Identifier(ItemAlchemyExpansion.MOD_ID, "emc_emitter_set");
    /** S2C：下发列表 + 当前所选 + 卡余额 */
    public static final Identifier LIST_S2C_ID =
            new Identifier(ItemAlchemyExpansion.MOD_ID, "emc_emitter_list_s2c");
    /** S2C：下发更新后的所选物品 */
    public static final Identifier SELECTED_S2C_ID =
            new Identifier(ItemAlchemyExpansion.MOD_ID, "emc_emitter_selected_s2c");

    private EmcAutoNetwork() {}

    /** 服务端注册 C2S 接收器 */
    public static void registerServer() {
        ServerPlayNetworking.registerGlobalReceiver(REQ_ID, (server, player, handler, buf, responseSender) -> {
            final long pos = buf.readLong();
            server.execute(() -> handleListRequest(player, pos));
        });
        ServerPlayNetworking.registerGlobalReceiver(SET_ID, (server, player, handler, buf, responseSender) -> {
            final long pos = buf.readLong();
            final boolean has = buf.readBoolean();
            final String variant = has ? buf.readString() : null;
            server.execute(() -> handleSet(player, pos, variant));
        });
    }

    /** 服务端发 S2C：打开输出器选择界面 */
    public static void sendOpen(ServerPlayerEntity player, BlockPos pos) {
        try {
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeLong(pos.asLong());
            ServerPlayNetworking.send(player, OPEN_ID, buf);
        } catch (Throwable t) {
            ItemAlchemyExpansion.LOGGER.warn("[IAExp] emc emitter: failed to send open: {}", t.toString());
        }
    }

    private static void handleListRequest(ServerPlayerEntity player, long pos) {
        // 自动装置总开关关闭时拒绝（与方块右键/tick 闸门一致，防伪造包绕过）
        if (!IAExpConfigHolder.get().automationEnabled) return;
        EmcEmitterBlockEntity tile = findTile(player, pos);
        if (tile == null) return;

        List<String> ids;
        try {
            ids = new ArrayList<>(PlayerRegisteredItemUtil.getItemsAsString(new Player(player)));
        } catch (Throwable t) {
            ids = new ArrayList<>();
        }

        long balance = EmcCardBalanceUtil.getBalance(player.getServer(), tile.getStack(EmcEmitterBlockEntity.CARD_SLOT));
        String selected = tile.getSelectedVariant() == null ? "" : tile.getSelectedVariant();
        String facing = tile.getFacing().getName();

        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(selected);
        buf.writeLong(balance);
        buf.writeString(facing);
        buf.writeInt(ids.size());
        for (String s : ids) {
            buf.writeString(s);
        }
        ServerPlayNetworking.send(player, LIST_S2C_ID, buf);
    }

    private static void handleSet(ServerPlayerEntity player, long pos, String variant) {
        // 自动装置总开关关闭时拒绝（与方块右键/tick 闸门一致，防伪造包绕过）
        if (!IAExpConfigHolder.get().automationEnabled) return;
        EmcEmitterBlockEntity tile = findTile(player, pos);
        if (tile == null) return;

        // 校验所选物品属于打开者自己的转换桌列表（防止设置任意物品）
        if (variant != null && !variant.isEmpty()) {
            List<String> ids;
            try {
                ids = new ArrayList<>(PlayerRegisteredItemUtil.getItemsAsString(new Player(player)));
            } catch (Throwable t) {
                ids = new ArrayList<>();
            }
            if (!ids.contains(variant)) return;
        }

        tile.setSelectedVariant(variant);
        sendSelected(player, tile.getSelectedVariant() == null ? "" : tile.getSelectedVariant());
    }

    private static void sendSelected(ServerPlayerEntity player, String selected) {
        try {
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeString(selected);
            ServerPlayNetworking.send(player, SELECTED_S2C_ID, buf);
        } catch (Throwable t) {
            ItemAlchemyExpansion.LOGGER.warn("[IAExp] emc emitter: failed to send selected: {}", t.toString());
        }
    }

    private static EmcEmitterBlockEntity findTile(ServerPlayerEntity player, long pos) {
        try {
            BlockPos bp = BlockPos.fromLong(pos);
            // 距离校验：仅允许操作 8 格内的输出器（与 canPlayerUse 一致，防伪造坐标刷物品）
            if (player.squaredDistanceTo(bp.getX() + 0.5, bp.getY() + 0.5, bp.getZ() + 0.5) > 64.0) {
                return null;
            }
            if (player.getServerWorld().getBlockEntity(bp) instanceof EmcEmitterBlockEntity tile) {
                return tile;
            }
        } catch (Throwable t) {
            ItemAlchemyExpansion.LOGGER.warn("[IAExp] emc emitter: find tile failed: {}", t.toString());
        }
        return null;
    }
}