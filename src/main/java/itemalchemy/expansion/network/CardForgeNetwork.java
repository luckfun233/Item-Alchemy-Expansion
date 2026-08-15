package itemalchemy.expansion.network;

import itemalchemy.expansion.ItemAlchemyExpansion;
import itemalchemy.expansion.block.CardForgeBlockEntity;
import itemalchemy.expansion.gui.CardForgeScreenHandler;
import itemalchemy.expansion.item.EmcCardItem;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * 制卡台 C2S 网络：私有/公有、关联、合并操作。
 *
 * <p>客户端 GUI 按钮发 C2S 包，服务端在 {@link CardForgeBlockEntity} 槽位上执行。
 * 操作前校验：私有卡仅主人可改；合并/关联两卡均需在场。</p>
 */
public final class CardForgeNetwork {

    public static final Identifier ACTION_ID =
            new Identifier(ItemAlchemyExpansion.MOD_ID, "card_forge_action");

    /** 动作：设为私有（作用于槽 0） */
    public static final byte ACTION_SET_PRIVATE = 0;
    /** 动作：设为公有（作用于槽 0） */
    public static final byte ACTION_SET_PUBLIC = 1;
    /** 动作：关联两卡（槽 0/1） */
    public static final byte ACTION_LINK = 2;
    /** 动作：合并两卡（槽 1 base+stored 并入槽 0） */
    public static final byte ACTION_MERGE = 3;
    /** 动作：绑定玩家（携带玩家名，作用于槽 0） */
    public static final byte ACTION_BIND = 4;
    /** 动作：解除绑定（作用于槽 0） */
    public static final byte ACTION_UNBIND = 5;
    /** 动作：设置绑定限额（携带 单次/总额 两个 long，作用于槽 0） */
    public static final byte ACTION_SET_LIMITS = 6;
    /** 动作：解除关联（槽 0 的关联组解散，余额回到槽 0 卡） */
    public static final byte ACTION_UNLINK = 7;
    /** 动作：请求在线玩家列表（触发 S2C 下发） */
    public static final byte ACTION_REQUEST_PLAYERS = 8;

    /** S2C 通道：下发在线玩家名列表（绑定页模糊匹配用） */
    public static final Identifier PLAYERS_ID =
            new Identifier(ItemAlchemyExpansion.MOD_ID, "card_forge_players");

    private CardForgeNetwork() {}

    /** 服务端注册 C2S 接收器 */
    public static void registerServer() {
        ServerPlayNetworking.registerGlobalReceiver(ACTION_ID, (server, player, handler, buf, responseSender) -> {
            final byte action = buf.readByte();
            final String name = (action == ACTION_BIND) ? buf.readString() : null;
            final long limSingle = (action == ACTION_SET_LIMITS) ? buf.readLong() : 0L;
            final long limTotal = (action == ACTION_SET_LIMITS) ? buf.readLong() : 0L;
            server.execute(() -> {
                try {
                    handleAction(player, action, name, limSingle, limTotal);
                } catch (Throwable t) {
                    ItemAlchemyExpansion.LOGGER.warn("[IAExp] card forge action failed: {}", t.toString());
                }
            });
        });
    }

    private static void handleAction(ServerPlayerEntity player, byte action, String name, long limSingle, long limTotal) {
        if (!(player.currentScreenHandler instanceof CardForgeScreenHandler sh)) return;
        CardForgeBlockEntity forge = sh.forge;
        if (forge == null) return;

        switch (action) {
            case ACTION_SET_PRIVATE -> setPrivate(forge, player);
            case ACTION_SET_PUBLIC -> setPublic(forge, player);
            case ACTION_LINK -> link(forge, player);
            case ACTION_MERGE -> merge(forge, player);
            case ACTION_BIND -> bind(forge, player, name);
            case ACTION_UNBIND -> unbind(forge, player);
            case ACTION_SET_LIMITS -> setLimits(forge, player, limSingle, limTotal);
            case ACTION_UNLINK -> unlink(forge, player);
            case ACTION_REQUEST_PLAYERS -> sendPlayers(player);
            default -> { return; }
        }
        forge.markDirty();
        sh.sendContentUpdates();
    }

    /** 下发在线玩家名列表（S2C，绑定页模糊匹配数据源） */
    private static void sendPlayers(ServerPlayerEntity player) {
        PacketByteBuf buf = PacketByteBufs.create();
        var names = player.getServer().getPlayerManager().getPlayerList().stream()
                .map(p -> p.getGameProfile().getName())
                .sorted(String::compareToIgnoreCase)
                .toList();
        buf.writeVarInt(names.size());
        for (String n : names) buf.writeString(n);
        ServerPlayNetworking.send(player, PLAYERS_ID, buf);
    }

    // ==================== 私有 / 公有 ====================

    private static void setPrivate(CardForgeBlockEntity forge, ServerPlayerEntity player) {
        ItemStack card = forge.getStack(0);
        if (card.isEmpty() || !(card.getItem() instanceof EmcCardItem)) {
            msg(player, "card_forge.no_card");
            return;
        }
        if (!canModify(card, player)) {
            msg(player, "card_forge.denied");
            return;
        }
        EmcCardItem.setVisibility(card, true);
        EmcCardItem.setOwnerUuid(card, player.getUuidAsString());
        msg(player, "card_forge.now_private", Text.literal(player.getName().getString()));
    }

    private static void setPublic(CardForgeBlockEntity forge, ServerPlayerEntity player) {
        ItemStack card = forge.getStack(0);
        if (card.isEmpty() || !(card.getItem() instanceof EmcCardItem)) {
            msg(player, "card_forge.no_card");
            return;
        }
        if (!canModify(card, player)) {
            msg(player, "card_forge.denied");
            return;
        }
        EmcCardItem.setVisibility(card, false);
        EmcCardItem.setOwnerUuid(card, null);
        msg(player, "card_forge.now_public");
    }

    /** 私有卡仅主人可改；公有卡任何人可改 */
    private static boolean canModify(ItemStack card, ServerPlayerEntity player) {
        return EmcCardItem.canUse(card, player);
    }

    // ==================== 关联 ====================

    private static void link(CardForgeBlockEntity forge, ServerPlayerEntity player) {
        ItemStack a = forge.getStack(0);
        ItemStack b = forge.getStack(1);
        if (a.isEmpty() || b.isEmpty() || !(a.getItem() instanceof EmcCardItem) || !(b.getItem() instanceof EmcCardItem)) {
            msg(player, "card_forge.link.need_two");
            return;
        }
        if (a == b) {
            msg(player, "card_forge.link.same");
            return;
        }
        // 已关联的卡重新关联：需先解散（避免账户混乱），此处提示
        if (EmcCardItem.getLinkGroup(a) != null || EmcCardItem.getLinkGroup(b) != null) {
            msg(player, "card_forge.link.already_linked");
            return;
        }
        // 绑卡余额在玩家 EMC 上，无法并入共享账户
        if (EmcCardItem.isBound(a) || EmcCardItem.isBound(b)) {
            msg(player, "card_forge.link.bound_unsupported");
            return;
        }
        // 生成新组，两卡余额并入共享账户，卡 NBT 存储清零
        String group = CardAccountStore.newGroupId();
        long total = EmcCardItem.getStoredEmc(a) + EmcCardItem.getStoredEmc(b);
        CardAccountStore.add(player.getServer(), group, total);
        EmcCardItem.setLinkGroup(a, group);
        EmcCardItem.setLinkGroup(b, group);
        EmcCardItem.setStoredEmc(a, 0);
        EmcCardItem.setStoredEmc(b, 0);
        msg(player, "card_forge.link.success");
    }

    /** 解除关联：共享余额全部回到槽 0 卡，关联组删除 */
    private static void unlink(CardForgeBlockEntity forge, ServerPlayerEntity player) {
        ItemStack card = forge.getStack(0);
        if (card.isEmpty() || !(card.getItem() instanceof EmcCardItem)) {
            msg(player, "card_forge.no_card");
            return;
        }
        String group = EmcCardItem.getLinkGroup(card);
        if (group == null) {
            msg(player, "card_forge.unlink.not_linked");
            return;
        }
        if (!canModify(card, player)) {
            msg(player, "card_forge.denied");
            return;
        }
        // 共享余额回到左槽卡；右槽同组卡一并解除标记
        long balance = CardAccountStore.get(group);
        EmcCardItem.setLinkGroup(card, null);
        EmcCardItem.setStoredEmc(card, balance);
        ItemStack other = forge.getStack(1);
        if (!other.isEmpty() && group.equals(EmcCardItem.getLinkGroup(other))) {
            EmcCardItem.setLinkGroup(other, null);
        }
        CardAccountStore.remove(player.getServer(), group);
        msg(player, "card_forge.unlink.success",
                Text.literal(EmcCardItem.formatNumber(balance)));
    }

    // ==================== 合并 ====================

    private static void merge(CardForgeBlockEntity forge, ServerPlayerEntity player) {
        ItemStack target = forge.getStack(0);
        ItemStack source = forge.getStack(1);
        if (target.isEmpty() || source.isEmpty() || !(target.getItem() instanceof EmcCardItem) || !(source.getItem() instanceof EmcCardItem)) {
            msg(player, "card_forge.merge.need_two");
            return;
        }
        if (target == source) {
            msg(player, "card_forge.merge.same");
            return;
        }
        // 关联卡合并语义复杂，暂仅支持普通卡合并
        if (EmcCardItem.getLinkGroup(target) != null || EmcCardItem.getLinkGroup(source) != null) {
            msg(player, "card_forge.merge.linked_unsupported");
            return;
        }
        // 绑卡余额在玩家 EMC 上，合并会导致凭空增减 EMC
        if (EmcCardItem.isBound(target) || EmcCardItem.isBound(source)) {
            msg(player, "card_forge.merge.bound_unsupported");
            return;
        }
        long transfer = EmcCardItem.getBaseEmc() + EmcCardItem.getStoredEmc(source);
        EmcCardItem.setStoredEmc(target, EmcCardItem.getStoredEmc(target) + transfer);
        EmcCardItem.addTransaction(target, EmcCardItem.TX_DEPOSIT, transfer);
        forge.setStack(1, ItemStack.EMPTY); // 消耗 source 卡
        msg(player, "card_forge.merge.success");
    }

    private static void msg(ServerPlayerEntity player, String key, Text... args) {
        player.sendMessage(Text.translatable("itemalchemy-expansion." + key, (Object[]) args), true);
    }

    // ==================== 绑定玩家 ====================

    private static void bind(CardForgeBlockEntity forge, ServerPlayerEntity player, String name) {
        ItemStack card = forge.getStack(0);
        if (card.isEmpty() || !(card.getItem() instanceof EmcCardItem)) {
            msg(player, "card_forge.no_card");
            return;
        }
        if (!canModify(card, player)) {
            msg(player, "card_forge.denied");
            return;
        }
        if (name == null || name.trim().isEmpty()) {
            msg(player, "card_forge.bind.need_name");
            return;
        }
        // 已关联卡余额在共享账户，绑定语义冲突，要求先解除关联
        if (EmcCardItem.getLinkGroup(card) != null) {
            msg(player, "card_forge.bind.linked");
            return;
        }
        // 解析玩家名 -> UUID（支持离线，走服务器 usercache）
        java.util.Optional<com.mojang.authlib.GameProfile> profile =
                player.getServer().getUserCache().findByName(name.trim());
        if (profile.isEmpty()) {
            msg(player, "card_forge.bind.not_found", Text.literal(name.trim()));
            return;
        }
        String uuid = profile.get().getId().toString();
        // 卡内原余额转入绑定玩家 EMC，避免绑定后"消失"
        long stored = EmcCardItem.getStoredEmc(card);
        if (stored > 0) {
            PlayerEmcUtil.add(player.getServer(), java.util.UUID.fromString(uuid), stored);
            EmcCardItem.setStoredEmc(card, 0);
        }
        EmcCardItem.setBindUuid(card, uuid);
        // 绑定后默认自动设为私有，绑定给该玩家
        EmcCardItem.setVisibility(card, true);
        EmcCardItem.setOwnerUuid(card, uuid);
        msg(player, "card_forge.bind.success", Text.literal(profile.get().getName()));
    }

    private static void unbind(CardForgeBlockEntity forge, ServerPlayerEntity player) {
        ItemStack card = forge.getStack(0);
        if (card.isEmpty() || !(card.getItem() instanceof EmcCardItem)) {
            msg(player, "card_forge.no_card");
            return;
        }
        if (!canModify(card, player)) {
            msg(player, "card_forge.denied");
            return;
        }
        EmcCardItem.setBindUuid(card, null);
        EmcCardItem.setBindSingleLimit(card, 0);
        EmcCardItem.setBindTotalLimit(card, 0);
        // 解除绑定同时清理自动设置的私有
        EmcCardItem.setVisibility(card, false);
        EmcCardItem.setOwnerUuid(card, null);
        msg(player, "card_forge.bind.unbound");
    }

    private static void setLimits(CardForgeBlockEntity forge, ServerPlayerEntity player, long single, long total) {
        ItemStack card = forge.getStack(0);
        if (card.isEmpty() || !(card.getItem() instanceof EmcCardItem)) {
            msg(player, "card_forge.no_card");
            return;
        }
        if (!canModify(card, player)) {
            msg(player, "card_forge.denied");
            return;
        }
        if (!EmcCardItem.isBound(card)) {
            msg(player, "card_forge.bind.not_bound");
            return;
        }
        EmcCardItem.setBindSingleLimit(card, single);
        EmcCardItem.setBindTotalLimit(card, total);
        msg(player, "card_forge.bind.limits_set",
                Text.literal(EmcCardItem.formatNumber(single)),
                Text.literal(EmcCardItem.formatNumber(total)));
    }
}