package itemalchemy.expansion.mixin;

import itemalchemy.expansion.ItemAlchemyExpansion;
import itemalchemy.expansion.config.IAExpConfig;
import itemalchemy.expansion.config.IAExpConfigHolder;
import itemalchemy.expansion.nbt.ShulkerBoxSupport;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.pitan76.itemalchemy.gui.slot.RegisterSlot;
import net.pitan76.mcpitanlib.api.entity.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Method;

/**
 * 输入槽校验层拦截潜影盒：
 * <ol>
 *   <li>{@link IAExpConfig.ShulkerBoxMode#DISABLE}：直接拒绝。</li>
 *   <li>{@link IAExpConfig.ShulkerNoEmcPolicy#REJECT} 且盒内有任何无 EMC 物品：拒绝并提示。</li>
 *   <li>其余情况：放行交给原 {@code canInsert} 判断（已被 {@link MixinEMCManager}
 *       改为 sumEmc，空盒返回 0 仍会被拒绝）。</li>
 * </ol>
 *
 * <p>无 EMC 提示：客户端优先用 Toast 弹窗（反射调用，避免 main 源集依赖 client 类），
 * 服务端或反射失败时回退到聊天消息。</p>
 *
 * <p>目标为模组类，方法名不重映射，{@code remap = false}。</p>
 */
@Mixin(value = RegisterSlot.class, priority = 500)
public abstract class MixinRegisterSlot {

    @Shadow public Player player;

    /** 客户端 Toast 反射方法缓存（懒加载） */
    private static Method clientToastMethod;
    /** Toast 类是否已解析过（true 表示已尝试，无论成功与否不再重试） */
    private static boolean clientToastResolved = false;

    @Inject(method = "canInsert(Lnet/minecraft/item/ItemStack;)Z",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void iaexp$shulkerCanInsert(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (!ShulkerBoxSupport.isShulkerBox(stack)) return;

        IAExpConfig config = IAExpConfigHolder.get();

        if (config.shulkerBoxMode == IAExpConfig.ShulkerBoxMode.DISABLE) {
            cir.setReturnValue(false);
            return;
        }

        if (config.shulkerNoEmcPolicy == IAExpConfig.ShulkerNoEmcPolicy.REJECT) {
            ItemStack noEmcItem = ShulkerBoxSupport.findNoEmcItem(stack);
            if (noEmcItem != null) {
                sendNoEmcRejectMessage(player, noEmcItem);
                cir.setReturnValue(false);
                return;
            }
        }
        // 放行：由原 canInsert 用 EMCManager.get(stack)=sumEmc 判断
    }

    private static void sendNoEmcRejectMessage(Player player, ItemStack noEmcItem) {
        try {
            if (player.isClient()) {
                showClientToast(noEmcItem);
            }
        } catch (Throwable t) {
            ItemAlchemyExpansion.LOGGER.warn(
                    "[IAExp] Shulker box rejected: contains no-EMC item {}",
                    noEmcItem.getItem(), t);
        }
    }

    /**
     * 反射调用客户端 {@code NoEmcShulkerBoxToast.show(ItemStack)}。
     * main 源集不能直接 import client 类，故用反射；服务端玩家（isClient=false）静默跳过。
     */
    private static boolean showClientToast(ItemStack noEmcItem) {
        if (!clientToastResolved) {
            clientToastResolved = true;
            try {
                Class<?> toastClass = Class.forName("itemalchemy.expansion.client.NoEmcShulkerBoxToast");
                clientToastMethod = toastClass.getMethod("show", ItemStack.class);
            } catch (Throwable t) {
                clientToastMethod = null;
            }
        }
        if (clientToastMethod == null) return false;
        try {
            clientToastMethod.invoke(null, noEmcItem);
            return true;
        } catch (Throwable t) {
            ItemAlchemyExpansion.LOGGER.warn("[IAExp] NoEmcShulkerBoxToast failed", t);
            return false;
        }
    }
}
