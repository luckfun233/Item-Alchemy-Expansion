package itemalchemy.expansion.item;

import itemalchemy.expansion.network.EmcCardNetwork;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.pitan76.mcpitanlib.api.event.item.ItemAppendTooltipEvent;
import net.pitan76.mcpitanlib.api.event.item.ItemBarColorArgs;
import net.pitan76.mcpitanlib.api.event.item.ItemBarStepArgs;
import net.pitan76.mcpitanlib.api.event.item.ItemBarVisibleArgs;
import net.pitan76.mcpitanlib.api.event.item.ItemUseEvent;
import net.pitan76.mcpitanlib.api.item.v2.CompatItem;
import net.pitan76.mcpitanlib.api.item.v2.CompatibleItemSettings;
import net.pitan76.mcpitanlib.api.util.StackActionResult;
import net.pitan76.mcpitanlib.api.util.TextUtil;

/**
 * EMC 卡：可存储 EMC 的便携物品，NBT 记录存储值。
 *
 * <p>右键打开 GUI 进行充入/拿取（服务端发 S2C 让客户端打开界面）。
 * EMC 存于物品 NBT 的 {@link #STORED_EMC_KEY}，卡随物走——
 * 任何人持有卡即可操作其内 EMC，赠予/交易/丢弃即转移，天然支持多用户共享。</p>
 *
 * <p>{@link itemalchemy.expansion.mixin.MixinEMCManager} 对本物品做特判：
 * 转换桌查价 = 卡本身 EMC（合成材料自动定价）+ 卡内存储 EMC。
 * 卡本身 EMC 由 {@link itemalchemy.expansion.recipe.RecipeAutoPricer} 扫描合成配方得出。</p>
 */
public class EmcCardItem extends CompatItem {

    /** NBT 键：卡内存储的 EMC 值（long） */
    public static final String STORED_EMC_KEY = "stored_emc";

    public EmcCardItem(CompatibleItemSettings settings) {
        super(settings);
    }

    /**
     * 从原版 {@link ItemStack} 读卡内 EMC。
     *
     * <p>供 {@link itemalchemy.expansion.mixin.MixinEMCManager} 与网络层调用，
     * 接受原版 ItemStack 避免 midohra 封装转换。</p>
     */
    public static long getStoredEmc(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0L;
        NbtCompound nbt = stack.getNbt();
        if (nbt == null || !nbt.contains(STORED_EMC_KEY)) return 0L;
        return nbt.getLong(STORED_EMC_KEY);
    }

    /** 写入卡内 EMC 到原版 {@link ItemStack}（负值截到 0）。 */
    public static void setStoredEmc(ItemStack stack, long emc) {
        if (stack == null || stack.isEmpty()) return;
        stack.getOrCreateNbt().putLong(STORED_EMC_KEY, Math.max(0L, emc));
    }

    @Override
    public StackActionResult onRightClick(ItemUseEvent e) {
        if (e.isClient()) return e.consume();
        // 服务端发 S2C，客户端收到后打开 EMC 卡 GUI
        if (e.user == null) return e.consume();
        e.user.getServerPlayer().ifPresent(EmcCardNetwork::sendOpenGui);
        return e.consume();
    }

    @Override
    public void appendTooltip(ItemAppendTooltipEvent e) {
        long stored = getStoredEmc(e.getStack());
        e.addTooltip(TextUtil.translatable("itemalchemy-expansion.emc_card.tooltip.stored",
                TextUtil.literal(formatNumber(stored))));
    }

    /** 卡内有 EMC 时显示满格青色条，作为「已存储」指示（卡无上限，不表达比例） */
    @Override
    public boolean isItemBarVisible(ItemBarVisibleArgs args) {
        return getStoredEmc(args.getStack()) > 0;
    }

    @Override
    public int getItemBarStep(ItemBarStepArgs args) {
        return 13;
    }

    @Override
    public int getItemBarColor(ItemBarColorArgs args) {
        return 0x00CCFF;
    }

    /** 紧凑数字格式化：1.23K / 4.56M / 7.89B（纯整数算术，避免 long→double 精度损失） */
    public static String formatNumber(long n) {
        if (n >= 1_000_000_000L) return formatScaled(n, 1_000_000_000L, "B");
        if (n >= 1_000_000L) return formatScaled(n, 1_000_000L, "M");
        if (n >= 1_000L) return formatScaled(n, 1_000L, "K");
        return String.valueOf(n);
    }

    private static String formatScaled(long n, long unit, String suffix) {
        long intPart = n / unit;
        long frac = (n % unit) * 100 / unit; // 两位小数
        return String.format("%d.%02d%s", intPart, frac, suffix);
    }
}
