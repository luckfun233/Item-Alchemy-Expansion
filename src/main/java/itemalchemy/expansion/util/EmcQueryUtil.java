package itemalchemy.expansion.util;

import itemalchemy.expansion.IAExpServices;
import itemalchemy.expansion.config.IAExpConfig;
import itemalchemy.expansion.config.IAExpConfigHolder;
import itemalchemy.expansion.nbt.ItemVariantKey;
import itemalchemy.expansion.network.AutoEmcStore;
import itemalchemy.expansion.network.PreciseEmcStore;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.Ingredient;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.pitan76.itemalchemy.EMCManager;
import net.pitan76.mcpitanlib.api.util.ItemStackUtil;

/**
 * EMC 查询工具：供配方自动定价等场景调用，<b>绕过 {@link MixinEMCManager}</b>，避免递归。
 *
 * <p>本类是 {@link MixinEMCManager} 中 public 静态方法的搬迁归宿：Mixin 规则要求 Mixin 类内的静态方法必须是 {@code private}，
 * 因此原本放在 Mixin 类里的 {@code resolveEmcForInput} / {@code resolveEmcForIngredient} 被移到这里。</p>
 *
 * <h3>查询优先级（始终生效，不再依赖 preciseMode 开关）</h3>
 * <p>L1（玩家精确）→ L3（自动精确）→ L2（原版 {@link EMCManager#get(net.minecraft.item.Item)}）→ L4（自动通用）→ 0。
 * L2 <b>直接调用 {@link EMCManager#contains} + {@link EMCManager#get(net.minecraft.item.Item)}</b>
 * 而非 {@link EMCManager#get(ItemStack)}，避免触发本 Mixin 的递归（精确查询 → 查输入材料 EMC → 又查 ItemStack → 又走精确查询）。</p>
 */
public final class EmcQueryUtil {

    private EmcQueryUtil() {}

    /**
     * 计算输入物品的 EMC（用于配方自动定价）。
     *
     * <p><b>不触发 MixinEMCManager</b>（避免递归）：直接查各层，
     * L2 使用 {@link EMCManager#get(net.minecraft.item.Item)} 按 ID 查询。</p>
     *
     * @param inStack 输入物品堆
     * @return 该堆的总 EMC（单价 × 数量）；无定价返回 0
     */
    public static long resolveEmcForInput(ItemStack inStack) {
        if (inStack == null || inStack.isEmpty()) return 0;
        IAExpConfig cfg = IAExpConfigHolder.get();
        long count = ItemStackUtil.getCount(inStack);

        // 精确层（始终查询，不依赖 preciseMode 开关）
        ItemVariantKey vk = IAExpServices.variantKeyOf(inStack);
        String vkStr = vk.toStorageString();
        // L1: 玩家精确覆盖
        Long v = PreciseEmcStore.get(vkStr);
        if (v != null) return v * count;
        // L3: 自动精确（仅 autoPricing 开启）
        if (cfg.autoPricingFromRecipes) {
            Long v2 = AutoEmcStore.getPrecise(vkStr);
            if (v2 != null) return v2 * count;
        }
        // L2: 原版按 ID 查（不含 NBT 信息，避免递归）
        String id = resolveItemId(inStack);
        if (EMCManager.contains(id)) {
            return EMCManager.get(inStack.getItem()) * count;
        }
        // L4: 自动通用（仅 autoPricing 开启）
        if (cfg.autoPricingFromRecipes) {
            Long v3 = AutoEmcStore.getGeneral(id);
            if (v3 != null) return v3 * count;
        }
        return 0;
    }

    /**
     * {@link Ingredient} 入口：取第一个匹配堆的 EMC。
     *
     * <p>Ingredient 可能有多个匹配堆（如标签），这里取第一个（{@link Ingredient#getMatchingStacks()}[0]）。
     * 自动定价对多可替代输入取"最便宜的那个"理论上更合理，但会显著增加复杂度；
     * 且配方自动定价本身是"估算"性质，取第一个足够用。
     * 如需更精确，可遍历所有匹配堆取 MIN（未来优化点）。</p>
     *
     * @param ing 输入 Ingredient
     * @return 第一个匹配堆的 EMC；无匹配返回 0
     */
    public static long resolveEmcForIngredient(Ingredient ing) {
        if (ing == null) return 0;
        ItemStack[] stacks = ing.getMatchingItems().map(ItemStack::new).toArray(ItemStack[]::new);
        if (stacks.length == 0) return 0;
        return resolveEmcForInput(stacks[0]);
    }

    /**
     * 解析 ItemStack 的 itemId（含命名空间）。
     *
     * <p>与 {@link MixinEMCManager} 内部的 {@code private resolveItemId} 逻辑一致，
     * 但这是独立副本（Mixin 类的 private 方法无法被外部访问）。</p>
     *
     * @param stack 物品堆
     * @return 形如 {@code "minecraft:stone"} 的 itemId；stack 为空返回 {@code "minecraft:air"}
     */
    public static String resolveItemId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "minecraft:air";
        Identifier id = Registries.ITEM.getId(stack.getItem());
        return id == null ? "minecraft:air" : id.toString();
    }
}
