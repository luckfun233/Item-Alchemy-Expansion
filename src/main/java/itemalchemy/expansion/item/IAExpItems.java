package itemalchemy.expansion.item;

import itemalchemy.expansion.ItemAlchemyExpansion;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.pitan76.itemalchemy.item.ItemGroups;
import net.pitan76.mcpitanlib.api.item.v2.CompatibleItemSettings;
import net.pitan76.mcpitanlib.api.util.CompatIdentifier;
import net.pitan76.mcpitanlib.api.util.CompatRarity;

/**
 * 本扩展模组注册的物品。
 *
 * <p>用 Fabric 原生 {@link Registry#register} 注册 {@link EmcCardItem}（继承 mcpitanlib {@code CompatItem}），
 * settings 仍走 mcpitanlib {@link CompatibleItemSettings} 以保留 rarity/maxCount 链式 API。
 * CompatItem 的事件回调（onRightClick/appendTooltip）通过重写 Item 方法实现，无需额外事件注册。</p>
 */
public final class IAExpItems {

    private IAExpItems() {}

    /** EMC 卡：便携 EMC 存储介质 */
    public static EmcCardItem EMC_CARD;

    public static void init() {
        EMC_CARD = Registry.register(Registries.ITEM,
                new Identifier(ItemAlchemyExpansion.MOD_ID, "emc_card"),
                new EmcCardItem(CompatibleItemSettings.of(
                                CompatIdentifier.of(ItemAlchemyExpansion.MOD_ID, "emc_card"))
                        .maxCount(1)
                        .rarity(CompatRarity.UNCOMMON)
                        .addGroup(ItemGroups.ITEM_ALCHEMY)));

        ItemAlchemyExpansion.LOGGER.info("[IAExp] items registered: emc_card");
    }

    /** 判断 stack 是否为 EMC 卡（供 Mixin / 网络层快速判定） */
    public static boolean isEmcCard(Item item) {
        return item == EMC_CARD;
    }
}
