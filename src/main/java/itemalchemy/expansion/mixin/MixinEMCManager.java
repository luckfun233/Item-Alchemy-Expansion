package itemalchemy.expansion.mixin;

import itemalchemy.expansion.IAExpServices;
import itemalchemy.expansion.ItemAlchemyExpansion;
import itemalchemy.expansion.config.IAExpConfig;
import itemalchemy.expansion.config.IAExpConfigHolder;
import itemalchemy.expansion.nbt.ItemVariantKey;
import itemalchemy.expansion.nbt.ShulkerBoxSupport;
import itemalchemy.expansion.network.AutoEmcStore;
import itemalchemy.expansion.network.PreciseEmcStore;
import itemalchemy.expansion.util.EmcQueryUtil;
import net.minecraft.item.ItemStack;
import net.pitan76.itemalchemy.EMCManager;
import net.pitan76.mcpitanlib.api.util.ItemStackUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让 {@link EMCManager} 支持「精确 EMC」与「配方自动定价」。
 *
 * <p>原 {@code EMCManager.get(ItemStack)} 按「物品 id × 数量」计价（同 id 同价）。
 * 本 Mixin 改为按变体键优先查询，使同 ID 不同 NBT 的物品可各自有价，
 * 同时为潜影盒特例返回 sumEmc（内容物之和）。</p>
 *
 * <h3>查询优先级（始终生效，不再依赖 preciseMode 开关）</h3>
 * <ul>
 *   <li><b>潜影盒</b>：sumEmc（内容物之和）—— 永远最高优先级</li>
 *   <li><b>L1</b> 玩家精确覆盖（{@link PreciseEmcStore}，按变体键）</li>
 *   <li><b>L3</b> 自动精确（{@link AutoEmcStore#getPrecise}，仅 autoPricing 开启）</li>
 *   <li><b>L2</b> 通用原方法（{@code EMCManager.get(Item) × count}，含玩家通用 + 上游）</li>
 *   <li><b>L4</b> 自动通用（{@link AutoEmcStore#getGeneral}，仅 autoPricing 开启且 L2 未定义时）</li>
 * </ul>
 *
 * <p>精确 EMC（手动或自动）<b>始终</b>优先于通用。玩家在 K 键 GUI 选择「精确」即可为特定变体定价，无需全局开关。
 * {@code preciseMode} 配置字段已弃用但保留以兼容旧配置文件。回退到 L2 时不 cancel，让原方法执行（{@code get(Item) × count}）。
 * 自动通用（L4）只在原方法会返回 0 且物品 id 未定义时查询，避免重复计算。</p>
 *
 * <p>{@code get(Item)} 重载<b>不拦截</b>：避免按 id 计价语义被破坏，且不携带 NBT 信息。
 * 自动定价计算输入材料 EMC 时调用 {@link itemalchemy.expansion.util.EmcQueryUtil#resolveEmcForInput}
 * （独立工具类，绕过本 Mixin，避免递归）。</p>
 *
 * <p><b>注意</b>：Mixin 规则要求 Mixin 类内的静态方法必须是 {@code private} 的，
 * 因此原本放在这里的 {@code resolveEmcForInput} / {@code resolveEmcForIngredient}
 * 已搬到 {@link itemalchemy.expansion.util.EmcQueryUtil}。</p>
 */
@Mixin(value = EMCManager.class, priority = 500)
public abstract class MixinEMCManager {

    /**
     * 原版 {@code ItemStack} 重载：潜影盒 / 精确 / 自动定价分支。
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

        // 2. 精确层查询（始终生效，不再依赖 preciseMode 开关）
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

        // 3. 通用层查询
        // L4: 自动通用（仅 autoPricing 开启且 L2 未定义时提前 cancel，避免原方法返回 0）
        if (cfg.autoPricingFromRecipes) {
            String itemId = EmcQueryUtil.resolveItemId(stack);
            if (!EMCManager.contains(itemId)) {
                Long autoGeneral = AutoEmcStore.getGeneral(itemId);
                if (autoGeneral != null) {
                    // L3 miss 回退 L4：若物品有 NBT，说明变体键未命中精确层，可能存在未忽略的运行时 NBT key
                    if (stack.hasNbt()) {
                        ItemAlchemyExpansion.debug("[IAExp] emc L3 miss -> L4 fallback: itemId={}, variant={}, nbtKeys={}, general={} x {}",
                                itemId, vkStr, stack.getNbt().getKeys(), autoGeneral, count);
                    } else {
                        ItemAlchemyExpansion.debug("[IAExp] emc hit L4 (general auto): {} -> {} x {}",
                                itemId, autoGeneral, count);
                    }
                    cir.setReturnValue(autoGeneral * count);
                    return;
                }
            }
        }
        // L2: 走原方法（不 cancel，让 EMCManager.get(Item) × count 执行）
    }
}
