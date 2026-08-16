package itemalchemy.expansion.item;

import itemalchemy.expansion.network.CardAccountStore;
import itemalchemy.expansion.network.EmcCardNetwork;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.pitan76.itemalchemy.EMCManager;
import net.pitan76.mcpitanlib.api.event.item.ItemAppendTooltipEvent;
import net.pitan76.mcpitanlib.api.event.item.ItemUseEvent;
import net.pitan76.mcpitanlib.api.item.v2.CompatItem;
import net.pitan76.mcpitanlib.api.item.v2.CompatibleItemSettings;
import net.pitan76.mcpitanlib.api.util.StackActionResult;
import net.pitan76.mcpitanlib.api.util.TextUtil;

/**
 * EMC 卡：可存储 EMC 的便携物品，NBT 记录存储值。
 *
 * <p>右键打开 GUI 进行充入/拿取；Shift+右键快速充能（若已启用）。
 * 多卡合并在铁砧进行（两卡分别放入左右槽，结果槽为合并后的卡）。
 * EMC 存于物品 NBT 的 {@link #STORED_EMC_KEY}，卡随物走——
 * 任何人持有卡即可操作其内 EMC，赠予/交易/丢弃即转移。</p>
 *
 * <p>支持铁砧重命名：自定义名称通过 NBT display 标签保留，
 * 转换桌按变体键（含 NBT 指纹）存储/提取。</p>
 *
 * <p>{@link itemalchemy.expansion.mixin.MixinEMCManager} 对本物品做特判：
 * 转换桌查价 = 卡本身 EMC + 卡内存储 EMC。
 * 卡本身 EMC 优先取自动/手动定价，未定价时按合成材料 EMC 总和计算。</p>
 */
public class EmcCardItem extends CompatItem {

    /** NBT 键：卡内存储的 EMC 值（long） */
    public static final String STORED_EMC_KEY = "stored_emc";

    /** NBT 键：快捷充能开关（boolean，默认 false） */
    public static final String QUICK_CHARGE_KEY = "quick_charge";

    /** NBT 键：快捷充能金额（long，默认 1000） */
    public static final String QUICK_AMOUNT_KEY = "quick_amount";

    /** NBT 键：交易记录列表（NbtList，每项为 NbtCompound） */
    public static final String TRANSACTIONS_KEY = "transactions";

    /** 交易记录最大条数 */
    public static final int MAX_TRANSACTIONS = 10;

    /** 交易类型：充入 */
    public static final byte TX_DEPOSIT = 0;
    /** 交易类型：拿取 */
    public static final byte TX_WITHDRAW = 1;

    /** NBT 键：卡可见性（String："public"/"private"，默认 public） */
    public static final String VISIBILITY_KEY = "visibility";
    /** 可见性值：公有 */
    public static final String VISIBILITY_PUBLIC = "public";
    /** 可见性值：私有 */
    public static final String VISIBILITY_PRIVATE = "private";

    /** NBT 键：私有卡绑定的玩家 UUID（String，仅私有卡有） */
    public static final String OWNER_UUID_KEY = "owner_uuid";

    /** NBT 键：关联组 UUID（String，仅关联卡有）。同一组共享服务端账户余额 */
    public static final String LINK_GROUP_KEY = "link_group";

    /** NBT 键：绑定的玩家 UUID（String，仅绑卡有）。卡余额/收支同步该玩家 EMC */
    public static final String BIND_UUID_KEY = "bind_uuid";

    /** NBT 键：绑定的玩家名（String，仅绑卡有）。用于离线时界面显示，不参与任何判定 */
    public static final String BIND_NAME_KEY = "bind_name";

    /** NBT 键：单次支出限额（long，0 表示不限） */
    public static final String BIND_SINGLE_LIMIT_KEY = "bind_single_limit";

    /** NBT 键：总额度限制（long，玩家 EMC 需保持的最小值，0 表示关闭） */
    public static final String BIND_TOTAL_LIMIT_KEY = "bind_total_limit";

    /** 合成材料 EMC 总和的硬编码 fallback（金×2 + 红石 + 铁×3） */
    private static final long FALLBACK_MATERIAL_EMC = 2048L * 2 + 64 + 256L * 3; // = 4928

    /** 默认快捷充能金额 */
    public static final long DEFAULT_QUICK_AMOUNT = 1000L;

    public EmcCardItem(CompatibleItemSettings settings) {
        super(settings);
    }

    // ==================== 存储 EMC ====================

    public static long getStoredEmc(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0L;
        NbtCompound nbt = stack.getNbt();
        if (nbt == null || !nbt.contains(STORED_EMC_KEY)) return 0L;
        return nbt.getLong(STORED_EMC_KEY);
    }

    public static void setStoredEmc(ItemStack stack, long emc) {
        if (stack == null || stack.isEmpty()) return;
        stack.getOrCreateNbt().putLong(STORED_EMC_KEY, Math.max(0L, emc));
    }

    /**
     * 返回卡的「有效余额」：绑卡返回绑定玩家 EMC（需服务端，此处返回 0），
     * 关联卡返回共享账户余额（服务端存储），普通卡返回卡 NBT 存储值。
     *
     * <p>注：本方法无服务端上下文，绑卡余额无法在无服务端处读取，返回 0。
     * 服务端读写请用 {@link itemalchemy.expansion.network.EmcCardBalanceUtil}。</p>
     */
    public static long getBalance(ItemStack stack) {
        if (isBound(stack)) return 0L;
        String group = getLinkGroup(stack);
        if (group != null) {
            return CardAccountStore.get(group);
        }
        return getStoredEmc(stack);
    }

    // ==================== 卡属性（可见性 / 归属 / 关联） ====================

    /** 是否私有卡（仅主人可充入/拿取） */
    public static boolean isPrivate(ItemStack stack) {
        return VISIBILITY_PRIVATE.equals(getVisibility(stack));
    }

    /** 是否公有卡（人人可用，默认） */
    public static boolean isPublic(ItemStack stack) {
        return !isPrivate(stack);
    }

    /** 返回可见性值（默认 public） */
    public static String getVisibility(ItemStack stack) {
        NbtCompound nbt = stack == null ? null : stack.getNbt();
        if (nbt != null && nbt.contains(VISIBILITY_KEY)) {
            return nbt.getString(VISIBILITY_KEY);
        }
        return VISIBILITY_PUBLIC;
    }

    /** 设置可见性；非 public 一律视为 private */
    public static void setVisibility(ItemStack stack, boolean privateCard) {
        if (stack == null || stack.isEmpty()) return;
        stack.getOrCreateNbt().putString(VISIBILITY_KEY,
                privateCard ? VISIBILITY_PRIVATE : VISIBILITY_PUBLIC);
    }

    /** 返回私有卡绑定的玩家 UUID（String），未私有时返回 null */
    public static String getOwnerUuid(ItemStack stack) {
        NbtCompound nbt = stack == null ? null : stack.getNbt();
        if (nbt != null && nbt.contains(OWNER_UUID_KEY)) {
            String s = nbt.getString(OWNER_UUID_KEY);
            return s.isEmpty() ? null : s;
        }
        return null;
    }

    /** 绑定/清空私有卡主人 */
    public static void setOwnerUuid(ItemStack stack, String ownerUuid) {
        if (stack == null || stack.isEmpty()) return;
        if (ownerUuid == null || ownerUuid.isEmpty()) {
            stack.getOrCreateNbt().remove(OWNER_UUID_KEY);
        } else {
            stack.getOrCreateNbt().putString(OWNER_UUID_KEY, ownerUuid);
        }
    }

    /** 返回卡的关联组 UUID（String），未关联返回 null */
    public static String getLinkGroup(ItemStack stack) {
        NbtCompound nbt = stack == null ? null : stack.getNbt();
        if (nbt != null && nbt.contains(LINK_GROUP_KEY)) {
            String s = nbt.getString(LINK_GROUP_KEY);
            return s.isEmpty() ? null : s;
        }
        return null;
    }

    /** 设置/清除关联组 UUID */
    public static void setLinkGroup(ItemStack stack, String groupId) {
        if (stack == null || stack.isEmpty()) return;
        if (groupId == null || groupId.isEmpty()) {
            stack.getOrCreateNbt().remove(LINK_GROUP_KEY);
        } else {
            stack.getOrCreateNbt().putString(LINK_GROUP_KEY, groupId);
        }
    }

    /** 某玩家是否可使用该卡（公有卡人人可用；私有卡仅主人可用） */
    public static boolean canUse(ItemStack stack, net.minecraft.server.network.ServerPlayerEntity player) {
        if (isPublic(stack)) return true;
        String owner = getOwnerUuid(stack);
        if (owner == null) return true; // 私有但无主人（异常态）放行，避免卡死
        return owner.equalsIgnoreCase(player.getUuidAsString());
    }

    // ==================== 绑定（同步指定玩家 EMC） ====================

    /** 是否已绑定玩家 */
    public static boolean isBound(ItemStack stack) {
        return getBindUuid(stack) != null;
    }

    /** 返回绑定的玩家 UUID（String），未绑定返回 null */
    public static String getBindUuid(ItemStack stack) {
        NbtCompound nbt = stack == null ? null : stack.getNbt();
        if (nbt != null && nbt.contains(BIND_UUID_KEY)) {
            String s = nbt.getString(BIND_UUID_KEY);
            return s.isEmpty() ? null : s;
        }
        return null;
    }

    /** 设置/清除绑定玩家 UUID */
    public static void setBindUuid(ItemStack stack, String uuid) {
        if (stack == null || stack.isEmpty()) return;
        if (uuid == null || uuid.isEmpty()) {
            stack.getOrCreateNbt().remove(BIND_UUID_KEY);
        } else {
            stack.getOrCreateNbt().putString(BIND_UUID_KEY, uuid);
        }
    }

    /** 返回绑定的玩家名（String），未绑定返回 null */
    public static String getBindName(ItemStack stack) {
        NbtCompound nbt = stack == null ? null : stack.getNbt();
        if (nbt != null && nbt.contains(BIND_NAME_KEY)) {
            String s = nbt.getString(BIND_NAME_KEY);
            return s.isEmpty() ? null : s;
        }
        return null;
    }

    /** 设置/清除绑定玩家名（仅展示用） */
    public static void setBindName(ItemStack stack, String name) {
        if (stack == null || stack.isEmpty()) return;
        if (name == null || name.isEmpty()) {
            stack.getOrCreateNbt().remove(BIND_NAME_KEY);
        } else {
            stack.getOrCreateNbt().putString(BIND_NAME_KEY, name);
        }
    }

    /** 单次支出限额（0 表示不限） */
    public static long getBindSingleLimit(ItemStack stack) {
        NbtCompound nbt = stack == null ? null : stack.getNbt();
        if (nbt != null && nbt.contains(BIND_SINGLE_LIMIT_KEY)) {
            long v = nbt.getLong(BIND_SINGLE_LIMIT_KEY);
            return v > 0 ? v : 0L;
        }
        return 0L;
    }

    /** 设置单次支出限额（<=0 表示不限） */
    public static void setBindSingleLimit(ItemStack stack, long limit) {
        if (stack == null || stack.isEmpty()) return;
        if (limit <= 0) {
            stack.getOrCreateNbt().remove(BIND_SINGLE_LIMIT_KEY);
        } else {
            stack.getOrCreateNbt().putLong(BIND_SINGLE_LIMIT_KEY, limit);
        }
    }

    /** 总额度限制（玩家 EMC 需保持的最小值；0 表示关闭） */
    public static long getBindTotalLimit(ItemStack stack) {
        NbtCompound nbt = stack == null ? null : stack.getNbt();
        if (nbt != null && nbt.contains(BIND_TOTAL_LIMIT_KEY)) {
            long v = nbt.getLong(BIND_TOTAL_LIMIT_KEY);
            return v > 0 ? v : 0L;
        }
        return 0L;
    }

    /** 设置总额度限制（<=0 表示关闭） */
    public static void setBindTotalLimit(ItemStack stack, long limit) {
        if (stack == null || stack.isEmpty()) return;
        if (limit <= 0) {
            stack.getOrCreateNbt().remove(BIND_TOTAL_LIMIT_KEY);
        } else {
            stack.getOrCreateNbt().putLong(BIND_TOTAL_LIMIT_KEY, limit);
        }
    }

    // ==================== 卡本身基础 EMC ====================

    public static long getBaseEmc() {
        if (IAExpItems.EMC_CARD == null) return FALLBACK_MATERIAL_EMC;
        long val = EMCManager.get(IAExpItems.EMC_CARD);
        if (val > 0) return val;
        long gold = EMCManager.get(Items.GOLD_INGOT);
        long redstone = EMCManager.get(Items.REDSTONE);
        long iron = EMCManager.get(Items.IRON_INGOT);
        if (gold > 0 || iron > 0) {
            return gold * 2 + redstone + iron * 3;
        }
        return FALLBACK_MATERIAL_EMC;
    }

    // ==================== 快捷充能配置 ====================

    public static boolean isQuickChargeEnabled(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        NbtCompound nbt = stack.getNbt();
        return nbt != null && nbt.getBoolean(QUICK_CHARGE_KEY);
    }

    public static void setQuickChargeEnabled(ItemStack stack, boolean enabled) {
        if (stack == null || stack.isEmpty()) return;
        stack.getOrCreateNbt().putBoolean(QUICK_CHARGE_KEY, enabled);
    }

    public static long getQuickChargeAmount(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return DEFAULT_QUICK_AMOUNT;
        NbtCompound nbt = stack.getNbt();
        if (nbt == null || !nbt.contains(QUICK_AMOUNT_KEY)) return DEFAULT_QUICK_AMOUNT;
        long val = nbt.getLong(QUICK_AMOUNT_KEY);
        return val > 0 ? val : DEFAULT_QUICK_AMOUNT;
    }

    public static void setQuickChargeAmount(ItemStack stack, long amount) {
        if (stack == null || stack.isEmpty()) return;
        stack.getOrCreateNbt().putLong(QUICK_AMOUNT_KEY, Math.max(1L, amount));
    }

    // ==================== 交易记录 ====================

    /** 添加一笔交易记录，超过 {@link #MAX_TRANSACTIONS} 条时移除最旧的 */
    public static void addTransaction(ItemStack stack, byte type, long amount) {
        if (stack == null || stack.isEmpty()) return;
        NbtCompound nbt = stack.getOrCreateNbt();
        NbtList list = nbt.getList(TRANSACTIONS_KEY, NbtList.COMPOUND_TYPE);
        NbtCompound entry = new NbtCompound();
        entry.putByte("type", type);
        entry.putLong("amount", amount);
        entry.putLong("time", System.currentTimeMillis());
        list.add(entry);
        while (list.size() > MAX_TRANSACTIONS) {
            list.remove(0);
        }
        nbt.put(TRANSACTIONS_KEY, list);
    }

    /** 返回交易记录列表（NbtList of NbtCompound），可能为空 */
    public static NbtList getTransactions(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return new NbtList();
        NbtCompound nbt = stack.getNbt();
        if (nbt == null) return new NbtList();
        return nbt.getList(TRANSACTIONS_KEY, NbtList.COMPOUND_TYPE);
    }

    // ==================== 铁砧合并 ====================

    /**
     * 合并两张卡：{@code target} 保留（身份/NBT 不变），{@code source} 的 base_emc + stored_emc
     * 全部转入 {@code target} 的 stored_emc。返回 {@code target} 的副本，不改动入参。
     */
    public static ItemStack mergeCards(ItemStack target, ItemStack source) {
        long transfer = getBaseEmc() + getStoredEmc(source);
        ItemStack result = target.copy();
        setStoredEmc(result, getStoredEmc(result) + transfer);
        addTransaction(result, TX_DEPOSIT, transfer);
        return result;
    }

    // ==================== 右键交互 ====================

    @Override
    public StackActionResult onRightClick(ItemUseEvent e) {
        if (e.isClient()) return e.consume();

        if (e.user == null) return e.consume();
        if (e.user.isSneaking()) {
            // Shift+右键：快捷充能（已启用时）；多卡合并在铁砧进行
            ItemStack mainHand = e.user.getMainHandStack();
            if (EmcCardItem.isQuickChargeEnabled(mainHand)) {
                e.user.getServerPlayer().ifPresent(EmcCardNetwork::handleQuickCharge);
                return e.consume();
            }
        }
        e.user.getServerPlayer().ifPresent(EmcCardNetwork::sendOpenGui);
        return e.consume();
    }

    // ==================== Tooltip ====================

    @Override
    public void appendTooltip(ItemAppendTooltipEvent e) {
        long base = getBaseEmc();
        e.addTooltip(TextUtil.translatable("itemalchemy-expansion.emc_card.tooltip.base",
                TextUtil.literal(formatNumber(base))));
        if (isBound(e.getStack())) {
            e.addTooltip(TextUtil.translatable("itemalchemy-expansion.emc_card.tooltip.bound"));
        } else if (getLinkGroup(e.getStack()) != null) {
            // 关联卡余额在服务端共享账户，客户端无从读取，不显示数字避免误导为 0
            e.addTooltip(TextUtil.translatable("itemalchemy-expansion.emc_card.tooltip.stored_shared"));
        } else {
            long stored = getStoredEmc(e.getStack());
            e.addTooltip(TextUtil.translatable("itemalchemy-expansion.emc_card.tooltip.stored",
                    TextUtil.literal(formatNumber(stored))));
            e.addTooltip(TextUtil.translatable("itemalchemy-expansion.emc_card.tooltip.total",
                    TextUtil.literal(formatNumber(base + stored))));
        }
        if (isPrivate(e.getStack())) {
            e.addTooltip(TextUtil.translatable("itemalchemy-expansion.emc_card.tooltip.private"));
        }
        if (getLinkGroup(e.getStack()) != null) {
            e.addTooltip(TextUtil.translatable("itemalchemy-expansion.emc_card.tooltip.linked"));
        }
        if (isQuickChargeEnabled(e.getStack())) {
            e.addTooltip(TextUtil.translatable("itemalchemy-expansion.emc_card.tooltip.quickcharge",
                    TextUtil.literal(formatNumber(getQuickChargeAmount(e.getStack())))));
        }
    }

    // ==================== 格式化 ====================

    public static String formatNumber(long n) {
        if (n >= 1_000_000_000_000L) return formatScaled(n, 1_000_000_000_000L, "T");
        return String.format("%,d", n);
    }

    private static String formatScaled(long n, long unit, String suffix) {
        long intPart = n / unit;
        long frac = (n % unit) * 100 / unit;
        return String.format("%d.%02d%s", intPart, frac, suffix);
    }
}
