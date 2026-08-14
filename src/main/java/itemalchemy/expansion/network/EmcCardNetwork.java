package itemalchemy.expansion.network;

import itemalchemy.expansion.ItemAlchemyExpansion;
import itemalchemy.expansion.item.EmcCardItem;
import itemalchemy.expansion.item.IAExpItems;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.pitan76.itemalchemy.EMCManager;
import net.pitan76.mcpitanlib.api.entity.Player;

/**
 * EMC 卡网络包：C2S 充入/拿取 + S2C 打开 GUI。
 *
 * <p><b>协议</b>：
 * <ul>
 *   <li>{@link #OPEN_GUI_ID} S2C：空包，客户端收到后打开 {@code EmcCardMainScreen}</li>
 *   <li>{@link #DEPOSIT_ID} C2S：{@code long amount}，玩家请求充入 amount EMC 到手持卡</li>
 *   <li>{@link #WITHDRAW_ID} C2S：{@code long amount}，玩家请求从手持卡拿取 amount EMC</li>
 * </ul>
 *
 * <p><b>充入</b>：校验 玩家 Team EMC >= amount，扣减 Team EMC，增加卡内 stored_emc。
 * <b>拿取</b>：校验 卡内 stored_emc >= amount，扣减卡内，增加 Team EMC。
 * 操作后同步：{@link EMCManager#syncS2C} 刷 Team EMC；{@code sendContentUpdates} 刷手持物品 NBT。</p>
 */
public final class EmcCardNetwork {

    public static final Identifier OPEN_GUI_ID =
            new Identifier(ItemAlchemyExpansion.MOD_ID, "emc_card_open");
    public static final Identifier DEPOSIT_ID =
            new Identifier(ItemAlchemyExpansion.MOD_ID, "emc_card_deposit");
    public static final Identifier WITHDRAW_ID =
            new Identifier(ItemAlchemyExpansion.MOD_ID, "emc_card_withdraw");

    private EmcCardNetwork() {}

    /** 服务端注册 C2S 接收器。在 {@code onInitialize} 中调用。 */
    public static void registerServer() {
        ServerPlayNetworking.registerGlobalReceiver(DEPOSIT_ID, (server, player, handler, buf, responseSender) -> {
            final long amount = buf.readLong();
            server.execute(() -> handleDeposit(player, amount));
        });
        ServerPlayNetworking.registerGlobalReceiver(WITHDRAW_ID, (server, player, handler, buf, responseSender) -> {
            final long amount = buf.readLong();
            server.execute(() -> handleWithdraw(player, amount));
        });
    }

    /** 发 S2C 打开 GUI 信号给客户端。 */
    public static void sendOpenGui(ServerPlayerEntity player) {
        try {
            ServerPlayNetworking.send(player, OPEN_GUI_ID, PacketByteBufs.create());
        } catch (Throwable t) {
            ItemAlchemyExpansion.LOGGER.warn("[IAExp] emc card: failed to send open gui: {}", t.toString());
        }
    }

    /** 充入：玩家 Team EMC -> 卡内。 */
    private static void handleDeposit(ServerPlayerEntity player, long amount) {
        if (amount <= 0) return;
        Hand hand = findCardHand(player);
        if (hand == null) {
            sendMsg(player, "itemalchemy-expansion.emc_card.no_card");
            return;
        }
        ItemStack card = player.getStackInHand(hand);
        Player mcpPlayer = new Player(player);
        long teamEmc = EMCManager.getEmcFromPlayer(mcpPlayer);
        if (teamEmc < amount) {
            sendMsg(player, "itemalchemy-expansion.emc_card.deposit.fail.insufficient",
                    Text.literal(EmcCardItem.formatNumber(teamEmc)));
            return;
        }
        EMCManager.decrementEmc(mcpPlayer, amount);
        EmcCardItem.setStoredEmc(card, EmcCardItem.getStoredEmc(card) + amount);
        syncHandStack(player);
        EMCManager.syncS2C(mcpPlayer);
        sendMsg(player, "itemalchemy-expansion.emc_card.deposit.success",
                Text.literal(EmcCardItem.formatNumber(amount)));
        ItemAlchemyExpansion.debug("[IAExp] emc card deposit: player={}, amount={}, teamEmc after={}",
                player.getEntityName(), amount, teamEmc - amount);
    }

    /** 拿取：卡内 -> 玩家 Team EMC。 */
    private static void handleWithdraw(ServerPlayerEntity player, long amount) {
        if (amount <= 0) return;
        Hand hand = findCardHand(player);
        if (hand == null) {
            sendMsg(player, "itemalchemy-expansion.emc_card.no_card");
            return;
        }
        ItemStack card = player.getStackInHand(hand);
        long stored = EmcCardItem.getStoredEmc(card);
        if (stored < amount) {
            sendMsg(player, "itemalchemy-expansion.emc_card.withdraw.fail.insufficient",
                    Text.literal(EmcCardItem.formatNumber(stored)));
            return;
        }
        EmcCardItem.setStoredEmc(card, stored - amount);
        Player mcpPlayer = new Player(player);
        EMCManager.incrementEmc(mcpPlayer, amount);
        syncHandStack(player);
        EMCManager.syncS2C(mcpPlayer);
        sendMsg(player, "itemalchemy-expansion.emc_card.withdraw.success",
                Text.literal(EmcCardItem.formatNumber(amount)));
        ItemAlchemyExpansion.debug("[IAExp] emc card withdraw: player={}, amount={}, stored after={}",
                player.getEntityName(), amount, stored - amount);
    }

    /** 查找玩家主/副手是否持有 EMC 卡，优先主手。 */
    private static Hand findCardHand(ServerPlayerEntity player) {
        ItemStack mainHand = player.getMainHandStack();
        if (!mainHand.isEmpty() && mainHand.getItem() == IAExpItems.EMC_CARD) {
            return Hand.MAIN_HAND;
        }
        ItemStack offHand = player.getOffHandStack();
        if (!offHand.isEmpty() && offHand.getItem() == IAExpItems.EMC_CARD) {
            return Hand.OFF_HAND;
        }
        return null;
    }

    /** 同步手持物品 NBT 变化到客户端（卡内 EMC 更新）。 */
    private static void syncHandStack(ServerPlayerEntity player) {
        player.getInventory().markDirty();
        // playerScreenHandler 持有玩家整个物品栏，sendContentUpdates 比较 NBT 变化发送更新包
        player.playerScreenHandler.sendContentUpdates();
    }

    private static void sendMsg(ServerPlayerEntity player, String key, Text... args) {
        // actionbar（true）避免刷聊天框
        player.sendMessage(Text.translatable(key, (Object[]) args), true);
    }
}
