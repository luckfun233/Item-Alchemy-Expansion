package itemalchemy.expansion.block;

import itemalchemy.expansion.ItemAlchemyExpansion;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.pitan76.mcpitanlib.api.block.v2.CompatibleBlockSettings;
import net.pitan76.mcpitanlib.api.util.CompatIdentifier;

/**
 * 制卡台相关注册：方块、方块物品、BlockEntityType。
 *
 * <p>沿用本模组 {@code itemalchemy.expansion.item.IAExpItems} 的 Fabric 原生 {@link Registry#register}
 * 注册方式（与 mcpitanlib 封装一致的既有约定）。</p>
 */
public final class CardForgeBlocks {

    private CardForgeBlocks() {}

    /** 制卡台方块 */
    public static CardForgeBlock FORGE;
    /** 制卡台的 BlockItem（用于在物品栏展示/放置） */
    public static BlockItem FORGE_ITEM;
    /** 制卡台 BlockEntityType */
    public static BlockEntityType<CardForgeBlockEntity> FORGE_TILE;

    public static void init() {
        FORGE = Registry.register(Registries.BLOCK,
                new Identifier(ItemAlchemyExpansion.MOD_ID, "card_forge"),
                new CardForgeBlock(CompatibleBlockSettings.of(
                                CompatIdentifier.of(ItemAlchemyExpansion.MOD_ID, "card_forge"))
                        .strength(2.0f, 6.0f)));

        FORGE_ITEM = Registry.register(Registries.ITEM,
                new Identifier(ItemAlchemyExpansion.MOD_ID, "card_forge"),
                new BlockItem(FORGE, new Item.Settings()
                        .maxCount(64)));

        FORGE_TILE = Registry.register(Registries.BLOCK_ENTITY_TYPE,
                new Identifier(ItemAlchemyExpansion.MOD_ID, "card_forge"),
                BlockEntityType.Builder.create(CardForgeBlockEntity::new, FORGE).build(null));

        ItemAlchemyExpansion.LOGGER.info("[IAExp] card forge registered: block + item + tile");
    }
}