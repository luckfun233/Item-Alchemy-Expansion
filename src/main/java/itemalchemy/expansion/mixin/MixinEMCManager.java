package itemalchemy.expansion.mixin;

import itemalchemy.expansion.IAExpServices;
import itemalchemy.expansion.ItemAlchemyExpansion;
import itemalchemy.expansion.config.IAExpConfig;
import itemalchemy.expansion.config.IAExpConfigHolder;
import itemalchemy.expansion.nbt.ItemVariantKey;
import itemalchemy.expansion.nbt.ShulkerBoxSupport;
import itemalchemy.expansion.network.AutoEmcStore;
import itemalchemy.expansion.network.PreciseEmcStore;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.Ingredient;
import net.minecraft.util.Identifier;
import net.minecraft.registry.Registries;
import net.pitan76.itemalchemy.EMCManager;
import net.pitan76.mcpitanlib.api.util.ItemStackUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让 {@link EMCManager} 支持「精确模式」与「配方自动定价」。
 *
 * <p>原 {@code EMCManager.get(ItemStack)} 按「物品 id × 数量」计价（同 id 同价，F2 契约）。
 * 本 Mixin 在精确模式开启时改为按变体键查询，使同 ID 不同 NBT 的物品可各自有价。
 * 同时为潜影盒特例返回 sumEmc（内容物之和），保留原有行为。</p>
 *
 * <h3>查询优先级</h3>
 * <ul>
 *   <li><b>潜影盒</b>：sumEmc（内容物之和）—— 永远最高优先级</li>
 *   <li><b>精确模式 ON</b>：玩家精确（L1）→ 自动精确（L3，仅 autoPricing 开启）→ 回退通用原方法（L2）
 *       → 自动通用（L4，仅 autoPricing 开启）→ 0</li>
 *   <li><b>精确模式 OFF</b>（旧版兼容行为）：原方法（L2）→ 自动通用（L4，仅 autoPricing 开启）→ 0</li>
 * </ul>
 *
 * <p>回退到 L2 时<b>不</b> cancel，让原方法执行（{@code get(Item) × count}）。
 * 自动通用（L4）只在原方法返回 0 且物品 id 未定义时查询，避免重复计算。</p>
 *
 * <p>{@code get(Item)} 重载<b>不拦截</b>：避免按 id 计价语义被破坏，且不携带 NBT 信息。
 * 自动定价计算输入材料 EMC 时直接调 {@link #resolveEmcForInput}（绕过本 Mixin，避免递归）。</p>
 */
@Mixin(value = EMCManager.class, priority = 500)
public abstract class MixinEMCManager {

    /**
     * 原版 {@code ItemStack} 重载：潜影盒 / 精确模式 / 自动定价分支。
     */
    @Inject(method = "get(Lnet/minecraft/item/ItemStack;)J", at = @At("HEAD"), cancellable = true)
    private static void iaexp$getEmc(ItemStack stack, CallbackInfoReturnable<Long> cir) {
        if (stack == null || stack.isEmpty()) return;

        // 1. 潜影盒：sumEmc（永远最高优先级，不依赖配置）
        if (ShulkerBoxSupport.isShulkerBox(stack)) {
            long sum = ShulkerBoxSupport.sumEmc(stack);
            ItemAlchemyExpansion.debug("[IAExp] EMCManager.get(shulkerBox) -> sumEmc={} (item={})", sum, stack.getItem());
            cir.setReturnValue(sum);
            return;
        }

        IAExpConfig cfg = IAExpConfigHolder.get();
        long count = ItemStackUtil.getCount(stack);

        // 2. 精确模式：L1 → L3 → 回退 L2（原方法）
        if (cfg.preciseMode) {
            ItemVariantKey vk = IAExpServices.variantKeyOf(stack);
            String vkStr = vk.toStorageString();

            // L1: 玩家精确覆盖
            Long preciseManual = PreciseEmcStore.get(vkStr);
            if (preciseManual != null) {
                ItemAlchemyExpansion.debug("[IAExp] emc hit L1 (precise manual): {} -> {} x {}",
                        vkStr, preciseManual, count);
                cir.setReturnValue(preciseManual * count);
                return;
            }
            // L3: 自动精确（仅 autoPricing 开启）
            if (cfg.autoPricingFromRecipes) {
                Long preciseAuto = AutoEmcStore.getPrecise(vkStr);
                if (preciseAuto != null) {
                    ItemAlchemyExpansion.debug("[IAExp] emc hit L3 (precise auto): {} -> {} x {}",
                            vkStr, preciseAuto, count);
                    cir.setReturnValue(preciseAuto * count);
                    return;
                }
            }
            // L2: 回退通用（原方法 get(Item) * count，不 cancel）
            // 但若原方法会返回 0（物品未定义）且 autoPricing 开启，提前 cancel 走 L4
            if (cfg.autoPricingFromRecipes) {
                String itemId = resolveItemId(stack);
                if (!EMCManager.contains(itemId)) {
                    Long autoGeneral = AutoEmcStore.getGeneral(itemId);
                    if (autoGeneral != null) {
                        ItemAlchemyExpansion.debug("[IAExp] emc hit L4 (general auto, precise fallback): {} -> {} x {}",
                                itemId, autoGeneral, count);
                        cir.setReturnValue(autoGeneral * count);
                        return;
                    }
                }
            }
            // 否则走原方法
            return;
        }

        // 3. 通用模式：L2 → L4
        // L2 走原方法。若 autoPricing 开启且原方法会返回 0，查 L4。
        if (cfg.autoPricingFromRecipes) {
            String itemId = resolveItemId(stack);
            if (!EMCManager.contains(itemId)) {
                Long autoGeneral = AutoEmcStore.getGeneral(itemId);
                if (autoGeneral != null) {
                    ItemAlchemyExpansion.debug("[IAExp] emc hit L4 (general auto): {} -> {} x {}",
                            itemId, autoGeneral, count);
                    cir.setReturnValue(autoGeneral * count);
                    return;
                }
            }
        }
        // 否则走原方法
    }

    /** 解析 ItemStack 的 itemId（含命名空间） */
    private static String resolveItemId(ItemStack stack) {
        Identifier id = Registries.ITEM.getId(stack.getItem());
        return id == null ? "minecraft:air" : id.toString();
    }

    /**
     * 计算输入物品的 EMC（用于配方自动定价）。
     *
     * <p><b>不触发本 Mixin</b>（避免递归）：直接查各层，不调用 {@link EMCManager#get(ItemStack)}。</p>
     *
     * <p>查询顺序：精确模式时 L1 → L3 → L2（{@link EMCManager#get(Item)}）→ L4；
     * 否则 L2 → L4。</p>
     *
     * <p>对 {@link Ingredient} 也提供入口（取 matchingStacks[0]）。</p>
     */
    public static long resolveEmcForInput(ItemStack inStack) {
        if (inStack == null || inStack.isEmpty()) return 0;
        IAExpConfig cfg = IAExpConfigHolder.get();
        long count = ItemStackUtil.getCount(inStack);

        if (cfg.preciseMode) {
            ItemVariantKey vk = IAExpServices.variantKeyOf(inStack);
            String vkStr = vk.toStorageString();
            Long v = PreciseEmcStore.get(vkStr);
            if (v != null) return v * count;
            if (cfg.autoPricingFromRecipes) {
                Long v2 = AutoEmcStore.getPrecise(vkStr);
                if (v2 != null) return v2 * count;
            }
        }
        // L2
        String id = resolveItemId(inStack);
        if (EMCManager.contains(id)) {
            return EMCManager.get(inStack.getItem()) * count;
        }
        // L4
        if (cfg.autoPricingFromRecipes) {
            Long v = AutoEmcStore.getGeneral(id);
            if (v != null) return v * count;
        }
        return 0;
    }

    /** {@link Ingredient} 入口：取第一个匹配堆 */
    public static long resolveEmcForIngredient(Ingredient ing) {
        if (ing == null) return 0;
        ItemStack[] stacks = ing.getMatchingStacks();
        if (stacks.length == 0) return 0;
        return resolveEmcForInput(stacks[0]);
    }
}
