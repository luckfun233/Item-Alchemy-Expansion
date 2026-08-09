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
 * <p>原 {@code ExtractSlot.canTakeItems} 用 {@code EMCManager.get(callGetStack().getItem())}
 * （按 Item id 计价），对潜影盒只返回潜影盒本身的 EMC，不含内容物。
 * 故对潜影盒单独用 {@link ShulkerBoxSupport#sumEmc} 判断。
 * 通过 cast 到 fabric {@link Slot} 获取槽位当前堆 {@code getStack()}，
 * 避免依赖 {@code callGetStack()} 的具体返回类型（midohra ItemStack）。
 * 潜影盒的槽位堆是 {@code definedStack} 的副本（带完整 CONTAINER 组件），故 {@code getStack()} 能拿到组件，sumEmc 可正确计算。</p>
 *
 * <p><b>1.21.1 方法签名变化</b>：上游 item-alchemy 1.3.3（1.21.1 版本）的
 * {@code canTakeItems} 参数类型是 midohra {@link Player}（mcpitanlib 包装类），
 * 而非 1.20.1 版本的 {@code PlayerEntity}。Mixin 描述符必须用 midohra Player
 * 才能正确匹配目标方法。由于目标是模组类，方法名不会被重映射。</p>
 */
@Mixin(value = ExtractSlot.class, priority = 500)
public abstract class MixinExtractSlot {

    @Shadow public boolean canTakeItem;

    /** 上游 ExtractSlot 的 mcpitanlib Player 字段，用于调用 EMCManager.getEmcFromPlayer */
    @Shadow public Player player;

    @Inject(method = "canTakeItems(Lnet/pitan76/mcpitanlib/api/entity/Player;)Z",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void iaexp$shulkerCanTake(Player playerArg, CallbackInfoReturnable<Boolean> cir) {
        // ExtractSlot extends CompatibleSlot extends Slot；cast 到 Slot 调原版 getStack()
        ItemStack stack = ((Slot) (Object) this).getStack();
        if (!ShulkerBoxSupport.isShulkerBox(stack)) return;

        long required = ShulkerBoxSupport.sumEmc(stack);
        ItemAlchemyExpansion.debug("[IAExp] ExtractSlot.canTakeItems(shulkerBox) required={}", required);
        // 用 Shadow 的 player 字段（mcpitanlib Player），而非方法参数（同样为 midohra Player）
        cir.setReturnValue(EMCManager.getEmcFromPlayer(player) >= required && canTakeItem);
    }
}
