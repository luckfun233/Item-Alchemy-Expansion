package itemalchemy.expansion.network;

import itemalchemy.expansion.item.EmcCardItem;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;

import java.util.UUID;

/**
 * EMC 卡余额读写工具（服务端）。
 *
 * <p>统一处理三种存储：<b>绑卡</b>（同步指定玩家 EMC，可带支出限额）、<b>关联卡</b>
 * （共享账户）、<b>普通卡</b>（卡 NBT）。供制卡台、自动装置、EMC 卡 GUI 等逻辑复用。
 * 所有方法须在服务端主线程调用。</p>
 */
public final class EmcCardBalanceUtil {

    private EmcCardBalanceUtil() {}

    /** 读取卡有效余额：绑卡读玩家 EMC，关联卡读共享账户，普通卡读卡 NBT。 */
    public static long getBalance(MinecraftServer server, ItemStack card) {
        if (card == null || card.isEmpty()) return 0L;
        normalizeCard(card);
        String bind = EmcCardItem.getBindUuid(card);
        if (bind != null) {
            return PlayerEmcUtil.getEmc(server, parseUuid(bind));
        }
        String group = EmcCardItem.getLinkGroup(card);
        if (group != null) {
            return CardAccountStore.get(group);
        }
        return EmcCardItem.getStoredEmc(card);
    }

    /**
     * 往卡加 EMC（amount &gt; 0）。绑卡加给绑定玩家，关联卡加共享账户，普通卡写卡 NBT。
     *
     * @return 是否真正入账（绑卡目标玩家无队伍等情况下返回 false，
     *         调用方据此决定是否消耗来源物品，避免「物品被吞、余额未入账」）。
     */
    public static boolean add(MinecraftServer server, ItemStack card, long amount) {
        if (card == null || card.isEmpty() || amount <= 0) return false;
        normalizeCard(card);
        String bind = EmcCardItem.getBindUuid(card);
        if (bind != null) {
            return PlayerEmcUtil.add(server, parseUuid(bind), amount);
        }
        String group = EmcCardItem.getLinkGroup(card);
        if (group != null) {
            CardAccountStore.add(server, group, amount);
        } else {
            EmcCardItem.setStoredEmc(card, EmcCardItem.getStoredEmc(card) + amount);
        }
        return true;
    }

    /**
     * 从卡减 EMC；余额不足拒绝。绑卡额外受「单次限额 / 总额度保留」约束。
     */
    public static boolean subtract(MinecraftServer server, ItemStack card, long amount) {
        if (card == null || card.isEmpty() || amount <= 0) return false;
        normalizeCard(card);
        String bind = EmcCardItem.getBindUuid(card);
        if (bind != null) {
            return subtractBound(server, card, amount);
        }
        long cur = getBalance(server, card);
        if (cur < amount) return false;
        String group = EmcCardItem.getLinkGroup(card);
        if (group != null) {
            CardAccountStore.subtract(server, group, amount);
        } else {
            EmcCardItem.setStoredEmc(card, cur - amount);
        }
        return true;
    }

    /** 绑卡减值：应用单次限额与总额度保留 */
    private static boolean subtractBound(MinecraftServer server, ItemStack card, long amount) {
        UUID uuid = parseUuid(EmcCardItem.getBindUuid(card));
        if (uuid == null) return false;

        long single = EmcCardItem.getBindSingleLimit(card);
        if (single > 0 && amount > single) return false;

        long total = EmcCardItem.getBindTotalLimit(card);
        long emc = PlayerEmcUtil.getEmc(server, uuid);
        if (total > 0 && emc <= total) return false; // 已到保留线下，禁止支出
        if (total > 0 && emc - amount < total) return false; // 本次支出会跌破保留线

        return PlayerEmcUtil.subtract(server, uuid, amount);
    }

    private static UUID parseUuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 孤儿关联组清理：解除关联时若同组另一张卡不在制卡台内，其 NBT 仍带着已删除的
     * link_group（读到 0）。此处惰性清理——发现账户不存在时移除失效标记，
     * 之后该卡按普通卡（卡 NBT 余额）处理。
     */
    private static void normalizeCard(ItemStack card) {
        String group = EmcCardItem.getLinkGroup(card);
        if (group != null && !CardAccountStore.has(group)) {
            EmcCardItem.setLinkGroup(card, null);
        }
    }
}