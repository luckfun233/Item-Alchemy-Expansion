package itemalchemy.expansion.mixin;

import itemalchemy.expansion.IAExpServices;
import itemalchemy.expansion.ItemAlchemyExpansion;
import itemalchemy.expansion.nbt.ItemVariantKey;
import net.minecraft.item.ItemStack;
import net.pitan76.itemalchemy.gui.slot.RemoveSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 移除槽按变体键移除 registeredItems 条目。
 *
 * <p>原 {@code items.add(ItemUtil.toId(stack.getItem()).toString())} 只存纯物品 ID，
 * 与 {@link MixinRegisterInventory} 写入的变体键（含 \u0001）无法匹配。
 * 本 Mixin 把该 add 参数替换为变体键，使移除精确到 NBT 变体。</p>
 */
@Mixin(value = RemoveSlot.class, priority = 500)
public abstract class MixinRemoveSlot {

    @Unique
    private ItemStack iaexp$currentStack = ItemStack.EMPTY;

    @Inject(method = "callSetStack", at = @At("HEAD"))
    private void iaexp$captureStack(ItemStack stack, CallbackInfo ci) {
        iaexp$currentStack = stack;
    }

    @ModifyArg(
            method = "callSetStack",
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
            ItemAlchemyExpansion.debug("[IAExp] remove variant: {}", vk.toStorageString());
            return vk.toStorageString();
        } catch (Throwable t) {
            ItemAlchemyExpansion.LOGGER.error("[IAExp] remove variant failed: " + t);
            return originalId;
        }
    }
}
