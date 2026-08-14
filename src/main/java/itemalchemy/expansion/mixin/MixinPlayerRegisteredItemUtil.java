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
 * <p>原版假设 {@code TeamState.registeredItems} 里都是纯物品 ID，直接用
 * {@code CompatIdentifier.of(id)} + {@code ItemUtil.fromId} 解析。但本模组扩展后 registeredItems
 * 里可能存变体键（如 {@code tacz:ammo\u0001\{AmmoId:"tacz:22wmr"\}}），其中分隔符 {@code \u0001}
 * 不是合法 Identifier 字符，会抛 {@code InvalidIdentifierException}（{@code class_151}）。</p>
 *
 * <p><b>修复策略</b>：HEAD cancel + 重写。对每个 {@code id}：
 * <ol>
 *   <li>用 {@link ItemVariantKey#fromStorageString(String)} 解析出 itemId 部分（{@code \u0001} 之前）。</li>
 *   <li>用 itemId（纯 ID）走 {@code CompatIdentifier.of} + {@code ItemUtil.fromId}。</li>
 *   <li>解析失败的变体键跳过（防御性，避免单个坏键导致整个调用崩溃）。</li>
 * </ol>
 * 这样多个同 ID 变体会解析出同一个 Item，但 {@code AlchemyTableScreen.keyReleased} 只用
 * Item 的 translationKey 做搜索匹配，重复项无影响（{@code translations.put} 覆写同 key）。</p>
 *
 * <p><b>target 为模组类</b>：{@code PlayerRegisteredItemUtil} 是 Item Alchemy 的类，
 * 方法名 {@code getItems} 不会被重映射，{@code remap = false}。</p>
 */
@Mixin(value = PlayerRegisteredItemUtil.class, remap = false)
public class MixinPlayerRegisteredItemUtil {

    @Inject(method = "getItems", at = @At("HEAD"), cancellable = true, remap = false)
    private static void iaexp$getItemsSafe(Player player, CallbackInfoReturnable<List<Item>> cir) {
        // 取原始 registeredItems 字符串列表（这个方法本身不会崩溃，直接复用）
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
                // 解析变体键：取 \u0001 之前的 itemId 部分
                ItemVariantKey vk = ItemVariantKey.fromStorageString(raw);
                String itemId = (vk != null) ? vk.itemId : raw;
                Item item = ItemUtil.fromId(CompatIdentifier.of(itemId));
                items.add(item);
            } catch (Throwable t) {
                // 单个变体键解析失败不阻塞整体；返回列表长度可能小于 ids.size()，
                // 但调用方（如 keyReleased 的翻译名匹配）对此容忍。
            }
        }
        cir.setReturnValue(items);
    }
}
