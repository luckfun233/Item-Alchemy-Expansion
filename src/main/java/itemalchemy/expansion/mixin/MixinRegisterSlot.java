package itemalchemy.expansion.mixin;

import itemalchemy.expansion.ItemAlchemyExpansion;
import itemalchemy.expansion.config.IAExpConfig;
import itemalchemy.expansion.config.IAExpConfigHolder;
import itemalchemy.expansion.nbt.ShulkerBoxSupport;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.pitan76.mcpitanlib.api.entity.Player;
import net.pitan76.itemalchemy.gui.slot.RegisterSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Method;

/**
 * 在「输入槽校验」层拦截潜影盒：
 * <ol>
 *   <li>配置 {@link IAExpConfig.ShulkerBoxMode#DISABLE}：直接拒绝放入。</li>
 *   <li>配置 {@link IAExpConfig.ShulkerNoEmcPolicy#REJECT} 且盒内有任何无 EMC 物品：
 *       拒绝放入并弹出 Toast 弹窗（客户端）/ 聊天消息（服务端后备）。</li>
 *   <li>其余情况（ALLOW 或无无 EMC 物品）：不干预，交给原 {@code canInsert}
 *       逻辑判断（{@code EMCManager.get(stack) != 0}，已被 {@link MixinEMCManager}
 *       改为 sumEmc，故空盒 sumEmc=0 仍会被原逻辑拒绝）。</li>
 * </ol>
 *
 * <p><b>无 EMC 提示方式</b>：原版用 {@code player.sendMessage} 发聊天消息，在激烈的物品交互场景下容易被忽略。
 * 改为：客户端通过反射调用 {@code itemalchemy.expansion.client.NoEmcShulkerBoxToast.show(ItemStack)}
 * 显示 Toast 弹窗；服务端保留 {@code sendMessage} 作为后备（客户端未装本模组时不会看到 Toast）。</p>
 *
 * <p><b>反射调用原因</b>：本类在 main 源集中（服务端也加载），不能直接 import 客户端类
 * （{@code NoEmcShulkerBoxToast} 依赖 {@code MinecraftClient}，服务端运行时
 * 会 {@code NoClassDefFoundError}）。反射 + {@code Class.forName} 在服务端
 * 找不到该类时静默 catch，不影响服务端逻辑。</p>
 */
@Mixin(value = RegisterSlot.class, priority = 500)
public abstract class MixinRegisterSlot {

    @Shadow public Player player;

    /** 客户端 Toast 通知类的反射 Method（懒加载缓存） */
    private static Method clientToastMethod;
    /** 客户端 Toast 类是否已确认可用（false 表示反射失败，不再尝试） */
    private static boolean clientToastResolved = false;

    @Inject(method = "canInsert(Lnet/minecraft/item/ItemStack;)Z", at = @At("HEAD"), cancellable = true)
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

        // 放行：不 cancel，让原 canInsert 用 EMCManager.get(stack)=sumEmc 判断
        // 空潜影盒 sumEmc=0 会被原逻辑拒绝；有内容物 sumEmc>0 可放入
    }

    private static void sendNoEmcRejectMessage(Player player, ItemStack noEmcItem) {
        try {
            // 客户端：尝试通过反射调用 Toast 弹窗
            if (player.isClient()) {
                if (showClientToast(noEmcItem)) {
                    return; // Toast 显示成功，不再发聊天消息
                }
            }
            // 服务端 / Toast 调用失败：用原版聊天消息作为后备
            Text msg = Text.translatable(
                    "itemalchemy-expansion.shulker_box.no_emc_item",
                    noEmcItem.getName());
            player.sendMessage(msg);
        } catch (Throwable t) {
            ItemAlchemyExpansion.LOGGER.warn(
                    "[IAExp] Shulker box rejected: contains no-EMC item {}",
                    noEmcItem.getItem(), t);
        }
    }

    /**
     * 反射调用客户端 {@code NoEmcShulkerBoxToast.show(ItemStack)} 显示 Toast 弹窗。
     * 反射 Method 缓存，仅首次调用时查找。返回 false 表示当前是服务端环境（类不存在）或反射调用失败，
     * 调用方应回退到 {@code sendMessage}。
     */
    private static boolean showClientToast(ItemStack noEmcItem) {
        if (!clientToastResolved) {
            clientToastResolved = true;
            try {
                Class<?> toastClass = Class.forName("itemalchemy.expansion.client.NoEmcShulkerBoxToast");
                clientToastMethod = toastClass.getMethod("show", ItemStack.class);
            } catch (Throwable t) {
                // 服务端环境：类不存在，正常情况
                clientToastMethod = null;
            }
        }
        if (clientToastMethod == null) return false;
        try {
            clientToastMethod.invoke(null, noEmcItem);
            return true;
        } catch (Throwable t) {
            ItemAlchemyExpansion.LOGGER.warn("[IAExp] Failed to show NoEmcShulkerBoxToast", t);
            return false;
        }
    }
}
