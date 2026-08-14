package itemalchemy.expansion.network;

import itemalchemy.expansion.ItemAlchemyExpansion;
import itemalchemy.expansion.item.EmcCardItem;
import itemalchemy.expansion.item.IAExpItems;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.pitan76.itemalchemy.EMCManager;
import net.pitan76.mcpitanlib.api.entity.Player;

/**
 * EMC 卡网络包：C2S 充入/拿取/配置 + S2C 打开 GUI。
 *
 * <p>右键交互（打开 GUI、合并、快捷充能）由服务端 {@link EmcCardItem#onRightClick} 直接处理，
 * GUI 内操作（充入、拿取、配置）通过 C2S 包处理。</p>
 */
public final class EmcCardNetwork {

    public static final Identifier OPEN_GUI_ID =
            new Identifier(ItemAlchemyExpansion.MOD_ID, "emc_card_open");
    public static final Identifier DEPOSIT_ID =
            new Identifier(ItemAlchemyExpansion.MOD_ID, "emc_card_deposit");
    public static final Identifier WITHDRAW_ID =
            new Identifier(ItemAlchemyExpansion.MOD_ID, "emc_card_withdraw");
    public static final Identifier CONFIG_ID =
            new Identifier(ItemAlchemyExpansion.MOD_ID, "emc_card_config");

    /** 配置动作：切换快捷充能 */
    public static final byte CFG_TOGGLE_QUICK = 0;
    /** 配置动作：设置快捷充能金额 */
    public static final byte CFG_SET_QUICK_AMOUNT = 1;

    private EmcCardNetwork() {}

    /** 服务端注册 C2S 接收器 */
    public static void registerServer() {
        ServerPlayNetworking.registerGlobalReceiver(DEPOSIT_ID, (server, player, handler, buf, responseSender) -> {
            final long amount = buf.readLong();
            server.execute(() -> handleDeposit(player, amount));
        });
        ServerPlayNetworking.registerGlobalReceiver(WITHDRAW_ID, (server, player, handler, buf, responseSender) -> {
            final long amount = buf.readLong();
            server.execute(() -> handleWithdraw(player, amount));
        });
        ServerPlayNetworking.registerGlobalReceiver(CONFIG_ID, (server, player, handler, buf, responseSender) -> {
            final byte action = buf.readByte();
            final long value = buf.readLong();
            server.execute(() -> handleConfig(player, action, value));
        });
    }

    /** 发 S2C 打开 GUI */
    public static void sendOpenGui(ServerPlayerEntity player) {
        try {
            ServerPlayNetworking.send(player, OPEN_GUI_ID, PacketByteBufs.create());
        } catch (Throwable t) {
            ItemAlchemyExpansion.LOGGER.warn("[IAExp] emc card: failed to send open gui: {}", t.toString());
        }
    }

    // ==================== 充入 ====================

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
        EmcCardItem.addTransaction(card, EmcCardItem.TX_DEPOSIT, amount);
        syncHandStack(player);
        EMCManager.syncS2C(mcpPlayer);
        sendMsg(player, "itemalchemy-expansion.emc_card.deposit.success",
                Text.literal(EmcCardItem.formatNumber(amount)));
    }

    // ==================== 拿取 ====================

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
        EmcCardItem.addTransaction(card, EmcCardItem.TX_WITHDRAW, amount);
        Player mcpPlayer = new Player(player);
        EMCManager.incrementEmc(mcpPlayer, amount);
        syncHandStack(player);
        EMCManager.syncS2C(mcpPlayer);
        sendMsg(player, "itemalchemy-expansion.emc_card.withdraw.success",
                Text.literal(EmcCardItem.formatNumber(amount)));
    }

    // ==================== 快捷充能（服务端直接调用） ====================

    public static void handleQuickCharge(ServerPlayerEntity player) {
        Hand hand = findCardHand(player);
        if (hand == null) {
            sendMsg(player, "itemalchemy-expansion.emc_card.no_card");
            return;
        }
        ItemStack card = player.getStackInHand(hand);
        long amount = EmcCardItem.getQuickChargeAmount(card);
        Player mcpPlayer = new Player(player);
        long teamEmc = EMCManager.getEmcFromPlayer(mcpPlayer);
        long actualAmount = Math.min(amount, teamEmc);
        if (actualAmount <= 0) {
            sendMsg(player, "itemalchemy-expansion.emc_card.quickcharge.fail.empty");
            return;
        }
        EMCManager.decrementEmc(mcpPlayer, actualAmount);
        EmcCardItem.setStoredEmc(card, EmcCardItem.getStoredEmc(card) + actualAmount);
        EmcCardItem.addTransaction(card, EmcCardItem.TX_DEPOSIT, actualAmount);
        syncHandStack(player);
        EMCManager.syncS2C(mcpPlayer);
        sendMsg(player, "itemalchemy-expansion.emc_card.quickcharge.success",
                Text.literal(EmcCardItem.formatNumber(actualAmount)));
    }

    // ==================== 配置（C2S） ====================

    private static void handleConfig(ServerPlayerEntity player, byte action, long value) {
        Hand hand = findCardHand(player);
        if (hand == null) {
            sendMsg(player, "itemalchemy-expansion.emc_card.no_card");
            return;
        }
        ItemStack card = player.getStackInHand(hand);
        switch (action) {
            case CFG_TOGGLE_QUICK:
                boolean newState = !EmcCardItem.isQuickChargeEnabled(card);
                EmcCardItem.setQuickChargeEnabled(card, newState);
                syncHandStack(player);
                sendMsg(player, newState
                        ? "itemalchemy-expansion.emc_card.config.quick.enabled"
                        : "itemalchemy-expansion.emc_card.config.quick.disabled");
                break;
            case CFG_SET_QUICK_AMOUNT:
                if (value <= 0) return;
                EmcCardItem.setQuickChargeAmount(card, value);
                syncHandStack(player);
                sendMsg(player, "itemalchemy-expansion.emc_card.config.quick.amount",
                        Text.literal(EmcCardItem.formatNumber(value)));
                break;
        }
    }

    // ==================== 工具方法 ====================

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

    private static void syncHandStack(ServerPlayerEntity player) {
        player.getInventory().markDirty();
        player.playerScreenHandler.sendContentUpdates();
    }

    private static void sendMsg(ServerPlayerEntity player, String key, Text... args) {
        player.sendMessage(Text.translatable(key, (Object[]) args), true);
    }
}
