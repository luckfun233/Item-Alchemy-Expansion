package itemalchemy.expansion.mixin;

import itemalchemy.expansion.ItemAlchemyExpansion;
import itemalchemy.expansion.nbt.ShulkerBoxSupport;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.pitan76.itemalchemy.EMCManager;
import net.pitan76.itemalchemy.gui.slot.ExtractSlot;
import net.pitan76.mcpitanlib.api.entity.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 提取槽拿取校验：对潜影盒用「内容物 EMC 之和」判断玩家 EMC 是否足够。
 *
 * <p>原 {@link ExtractSlot#canTakeItems} 用 {@code EMCManager.get(callGetStack().getItem())}
 * （按 Item id 计价），对潜影盒只返回潜影盒本身的 EMC，不含内容物。
 * 故对潜影盒单独用 {@link ShulkerBoxSupport#sumEmc} 判断。</p>
 *
 * <p>实现：通过 cast 到 fabric {@link Slot} 获取槽位当前堆 {@code getStack()}，
 * 避免依赖 {@code callGetStack()} 的具体返回类型（midohra ItemStack）。
 * 潜影盒的槽位堆是 {@code definedStack} 的副本（带完整 BlockEntityTag NBT），
 * 故 {@code getStack()} 能拿到 NBT，sumEmc 可正确计算。</p>
 *
 * <p><b>方法签名</b>：上游 {@code ExtractSlot.canTakeItems} 重写的是 mcpitanlib
 * {@link Player} 参数版本（{@code CompatibleSlot.canTakeItems(Player)}），不是原版
 * {@code Slot.canTakeItems(PlayerEntity)}。本项目无 refmap，Mixin 描述符里写
 * mcpitanlib 类名（{@code net.pitan76.*}）不会被 Loom 重映射，运行时与上游字节码
 * 完全匹配；若误写原版 {@code PlayerEntity}，反而会被 Loom 重映射为
 * intermediary {@code class_1657}，与上游实际签名不一致，导致 mixin apply 失败崩溃。</p>
 */
@Mixin(value = ExtractSlot.class, priority = 500)
public abstract class MixinExtractSlot {

    @Shadow public boolean canTakeItem;

    @Inject(method = "canTakeItems(Lnet/pitan76/mcpitanlib/api/entity/Player;)Z", at = @At("HEAD"), cancellable = true)
    private void iaexp$shulkerCanTake(Player player, CallbackInfoReturnable<Boolean> cir) {
        // ExtractSlot extends CompatibleSlot extends Slot；cast 到 Slot 调原版 getStack()
        ItemStack stack = ((Slot) (Object) this).getStack();
        if (!ShulkerBoxSupport.isShulkerBox(stack)) return;

        long required = ShulkerBoxSupport.sumEmc(stack);
        ItemAlchemyExpansion.debug("[IAExp] ExtractSlot.canTakeItems(shulkerBox) required={}", required);
        cir.setReturnValue(EMCManager.getEmcFromPlayer(player) >= required && canTakeItem);
    }
}
