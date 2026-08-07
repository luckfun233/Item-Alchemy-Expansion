package itemalchemy.expansion.mixin.client;

import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.pitan76.itemalchemy.ItemAlchemyClient;
import net.pitan76.itemalchemy.EMCManager;
import net.pitan76.mcpitanlib.api.util.ItemStackUtil;
import net.pitan76.mcpitanlib.api.util.TextUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * 修正 EMC tooltip 显示：用 {@link EMCManager#get(ItemStack)} 替代原版的 {@code getMap().get(id)}。
 *
 * <p>原版 {@link ItemAlchemyClient#getEmcText} 直接从 {@code EMCManager.getMap()} 按 item id
 * 查询 EMC，只能查到通用层（L2）的值。这导致：
 * <ul>
 *   <li>精确模式设置的变体 EMC（L1）不显示</li>
 *   <li>自动定价的精确/通用 EMC（L3/L4）不显示</li>
 *   <li>潜影盒内容物之和（MixinEMCManager 逻辑）不显示</li>
 * </ul>
 * </p>
 *
 * <p>本 Mixin 在方法头部拦截，统一调用 {@link EMCManager#get(ItemStack)}（已被 MixinEMCManager
 * 拦截，走 L1→L3→L2→L4 优先级链），使 tooltip 显示的 EMC 与实际转换行为一致。</p>
 *
 * <p>文本格式与原版保持一致：{@code §eEMC: §r<单价>}，数量 > 1 时追加 {@code §eStack EMC: §r<总价>}。</p>
 *
 * <p><b>target 为模组类</b>：{@code ItemAlchemyClient} 是 Item Alchemy 的客户端类，
 * {@code getEmcText} 不是 Minecraft 原版方法，{@code remap = false}。</p>
 */
@Mixin(value = ItemAlchemyClient.class, remap = false)
public abstract class MixinItemAlchemyClient {

    @Inject(method = "getEmcText", at = @At("HEAD"), cancellable = true, remap = false)
    private static void iaexp$fixEmcTooltip(ItemStack stack, CallbackInfoReturnable<List<Text>> cir) {
        if (stack == null || stack.isEmpty()) return;

        // 用 EMCManager.get(ItemStack) 查询——已被 MixinEMCManager 拦截，
        // 走 L1(精确手动) → L3(精确自动) → L2(通用) → L4(通用自动) 优先级链
        long totalEmc;
        try {
            totalEmc = EMCManager.get(stack);
        } catch (Throwable t) {
            return; // 异常时回退原方法
        }
        if (totalEmc <= 0) return; // EMC=0 时不显示，回退原方法（原方法也会 return 空 list）

        int count = ItemStackUtil.getCount(stack);
        long unitEmc = count > 1 ? totalEmc / count : totalEmc;

        List<Text> list = new ArrayList<>();
        list.add(TextUtil.literal("\u00a7eEMC: \u00a7r" + String.format("%,d", unitEmc)));
        if (count > 1) {
            list.add(TextUtil.literal("\u00a7eStack EMC: \u00a7r" + String.format("%,d", totalEmc)));
        }
        cir.setReturnValue(list);
    }
}
