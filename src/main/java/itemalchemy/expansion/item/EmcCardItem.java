package itemalchemy.expansion.item;

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
        long stored = getStoredEmc(e.getStack());
        e.addTooltip(TextUtil.translatable("itemalchemy-expansion.emc_card.tooltip.base",
                TextUtil.literal(formatNumber(base))));
        e.addTooltip(TextUtil.translatable("itemalchemy-expansion.emc_card.tooltip.stored",
                TextUtil.literal(formatNumber(stored))));
        e.addTooltip(TextUtil.translatable("itemalchemy-expansion.emc_card.tooltip.total",
                TextUtil.literal(formatNumber(base + stored))));
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
