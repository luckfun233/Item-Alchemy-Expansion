package itemalchemy.expansion.mixin.client;

import itemalchemy.expansion.nbt.ShulkerBoxSupport;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.pitan76.itemalchemy.ItemAlchemyClient;
import net.pitan76.mcpitanlib.api.util.ItemStackUtil;
import net.pitan76.mcpitanlib.api.util.TextUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * 修正潜影盒 tooltip 中的 EMC 显示。
 *
 * <p>原版 {@link ItemAlchemyClient#getEmcText} 直接从 {@code EMCManager.getMap()} 按 item id
 * 查询 EMC，对于潜影盒只会返回潜影盒物品本身的 EMC（通常为 0），而不是内容物之和。
 * 本 Mixin 在方法头部拦截：若 stack 是潜影盒且内容物之和 > 0，直接返回正确的 EMC 文本。</p>
 *
 * <p>文本格式与原版保持一致：{@code §eEMC: §r<值>}，数量 > 1 时追加 {@code §eStack EMC: §r<值>}。</p>
 *
 * <p><b>target 为模组类</b>：{@code ItemAlchemyClient} 是 Item Alchemy 的客户端类，
 * {@code getEmcText} 不是 Minecraft 原版方法，{@code remap = false}。</p>
 */
@Mixin(value = ItemAlchemyClient.class, remap = false)
public abstract class MixinItemAlchemyClient {

    @Inject(method = "getEmcText", at = @At("HEAD"), cancellable = true, remap = false)
    private static void iaexp$fixShulkerBoxEmcTooltip(ItemStack stack, CallbackInfoReturnable<List<Text>> cir) {
        if (stack == null || stack.isEmpty()) return;
        if (!ShulkerBoxSupport.isShulkerBox(stack)) return;

        long sumEmc = ShulkerBoxSupport.sumEmc(stack);
        if (sumEmc <= 0) return;

        List<Text> list = new ArrayList<>();
        list.add(TextUtil.literal("\u00a7eEMC: \u00a7r" + String.format("%,d", sumEmc)));

        int count = ItemStackUtil.getCount(stack);
        if (count > 1) {
            list.add(TextUtil.literal("\u00a7eStack EMC: \u00a7r" + String.format("%,d", sumEmc * count)));
        }

        cir.setReturnValue(list);
    }
}
