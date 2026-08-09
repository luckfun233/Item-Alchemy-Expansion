package itemalchemy.expansion.mixin;

import itemalchemy.expansion.nbt.ItemVariantKey;
import net.minecraft.item.Item;
import net.pitan76.itemalchemy.api.PlayerRegisteredItemUtil;
import net.pitan76.mcpitanlib.api.entity.Player;
import net.pitan76.mcpitanlib.api.util.CompatIdentifier;
import net.pitan76.mcpitanlib.api.util.item.ItemUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * 修复 {@link PlayerRegisteredItemUtil#getItems(Player)} 对变体键的解析崩溃。
 *
 * <p>原版假设 registeredItems 都是纯物品 ID，直接 {@code CompatIdentifier.of(id)} 解析。
 * 本模组写入的变体键含分隔符 \u0001（非法 Identifier 字符），会抛
 * {@code InvalidIdentifierException}。本 Mixin HEAD cancel 重写：对每个 id 用
 * {@link ItemVariantKey#fromStorageString(String)} 取 \u0001 之前的 itemId 部分再解析，
 * 解析失败的条目跳过，避免单个坏键导致整个调用崩溃。</p>
 *
 * <p>目标为模组类，方法名不重映射，{@code remap = false}。</p>
 */
@Mixin(value = PlayerRegisteredItemUtil.class, remap = false)
public class MixinPlayerRegisteredItemUtil {

    @Inject(method = "getItems", at = @At("HEAD"), cancellable = true, remap = false)
    private static void iaexp$getItemsSafe(Player player, CallbackInfoReturnable<List<Item>> cir) {
        List<String> ids;
        try {
            ids = PlayerRegisteredItemUtil.getItemsAsString(player);
        } catch (Throwable t) {
            cir.setReturnValue(new ArrayList<>());
            return;
        }

        List<Item> items = new ArrayList<>(ids.size());
        for (String raw : ids) {
            try {
                ItemVariantKey vk = ItemVariantKey.fromStorageString(raw);
                String itemId = (vk != null) ? vk.itemId : raw;
                Item item = ItemUtil.fromId(CompatIdentifier.of(itemId));
                items.add(item);
            } catch (Throwable t) {
                // 单个变体键解析失败不阻塞整体；调用方对返回列表长度容忍
            }
        }
        cir.setReturnValue(items);
    }
}
