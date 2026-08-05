package itemalchemy.expansion.mixin;

import itemalchemy.expansion.ItemAlchemyExpansion;
import itemalchemy.expansion.nbt.ShulkerBoxSupport;
import net.minecraft.item.ItemStack;
import net.pitan76.itemalchemy.EMCManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让 {@link EMCManager} 对潜影盒返回「内容物 EMC 之和」而非潜影盒本身的 EMC。
 *
 * <p>原 {@code EMCManager.get(ItemStack)} 按「物品 id × 数量」计价（同 id 同价）。
 * 但潜影盒作为特例：同 id（如 minecraft:white_shulker_box）不同内容物的潜影盒
 * 应有不同 EMC（内容物之和）。用户明确要求「这个情况下面同 id 就不是同样的 emc 价格了」。</p>
 *
 * <p>本 Mixin 拦截两个 {@code get} 重载：
 * <ul>
 *   <li>{@code get(net.minecraft.item.ItemStack)} — 被 {@code writeEmcToPlayer}（存入给 EMC）
 *       与 {@code onSlotClick}（购买扣费校验/扣费）调用。</li>
 *   <li>{@code get(net.pitan76.mcpitanlib.midohra.item.ItemStack)} — mcpitanlib 包装版本，
 *       先 {@code toMinecraft()} 再委托原版 {@code get(ItemStack)}，故只需拦截原版即可。</li>
 * </ul>
 * </p>
 *
 * <p>{@code get(Item)} 重载<b>不拦截</b>：它没有 NBT 信息，且被 {@code ExtractSlot.canTakeItems}
 * 调用——后者由 {@code MixinExtractSlot} 单独处理（用 sumEmc 判断）。
 * 避免对 {@code get(Item)} 改动影响所有物品的按 id 计价语义。</p>
 *
 * <p>递归防护：{@link ShulkerBoxSupport#sumEmc} 内部用 {@code EMCManager.get(Item)}
 * （按 id，不触发本 Mixin），不会递归回 {@code get(ItemStack)}。</p>
 */
@Mixin(value = EMCManager.class, priority = 500)
public abstract class MixinEMCManager {

    /**
     * 原版 {@code ItemStack} 重载：潜影盒返回 sumEmc。
     *
     * <p>非潜影盒不干预（{@code cir.cancel()} 不调用，原方法继续执行）。</p>
     */
    @Inject(method = "get(Lnet/minecraft/item/ItemStack;)J", at = @At("HEAD"), cancellable = true)
    private static void iaexp$shulkerBoxEmc(ItemStack stack, CallbackInfoReturnable<Long> cir) {
        if (stack == null || stack.isEmpty()) return;
        if (!ShulkerBoxSupport.isShulkerBox(stack)) return;
        long sum = ShulkerBoxSupport.sumEmc(stack);
        ItemAlchemyExpansion.debug("[IAExp] EMCManager.get(shulkerBox) -> sumEmc={} (item={})", sum, stack.getItem());
        cir.setReturnValue(sum);
    }
}
