package itemalchemy.expansion.mixin;

import itemalchemy.expansion.IAExpServices;
import itemalchemy.expansion.ItemAlchemyExpansion;
import itemalchemy.expansion.nbt.ComponentNbtView;
import itemalchemy.expansion.nbt.ItemVariantKey;
import net.minecraft.item.ItemStack;
import net.pitan76.itemalchemy.gui.inventory.RegisterInventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 让输入槽注册物品时存「变体键（含 NBT 指纹）」而非纯物品 ID。
 *
 * <p>原 {@code items.add(stack.getItem().getId().toString())} 只存物品 ID，导致同 ID 不同 NBT 的物品（tacz 子弹、药水）坍缩为一条。
 * 本 Mixin 用 {@code @Inject HEAD} 捕获原始 {@code net.minecraft.item.ItemStack}（带组件），
 * 再用 {@code @ModifyArg} 把第 0 处 {@code List.add(Object)} 的参数（纯 ID）替换为变体键。</p>
 *
 * <p>目标方法签名是 {@code setStack(int, net.minecraft.item.ItemStack)}（原版 ItemStack）。
 * 1.21.1 中物品的 NBT 数据已迁移到 data component，但方法捕获的是原版 ItemStack，
 * 通过 {@link ComponentNbtView} 可正常收集组件数据生成指纹。</p>
 */
@Mixin(value = RegisterInventory.class, priority = 500)
public abstract class MixinRegisterInventory {

    @Unique
    private ItemStack iaexp$currentStack = ItemStack.EMPTY;

    @Inject(method = "setStack", at = @At("HEAD"))
    private void iaexp$captureStack(int slot, ItemStack stack, CallbackInfo ci) {
        iaexp$currentStack = stack;
    }

    @ModifyArg(
            method = "setStack",
            at = @At(value = "INVOKE", target = "Ljava/util/List;add(Ljava/lang/Object;)Z", ordinal = 0),
            index = 0
    )
    private Object iaexp$useVariantKey(Object originalId) {
        ItemStack stack = iaexp$currentStack;
        if (stack == null || stack.isEmpty()) {
            return originalId;
        }
        try {
            ItemVariantKey vk = IAExpServices.variantKeyOf(stack);
            String variant = vk.toStorageString();
            ItemAlchemyExpansion.debug("[IAExp] register variant: {} (hasNbt={})",
                    variant, ComponentNbtView.hasEffectiveNbt(stack));
            return variant;
        } catch (Throwable t) {
            ItemAlchemyExpansion.LOGGER.error("[IAExp] register variant failed, fallback to plain id: " + t);
            return originalId;
        }
    }
}
