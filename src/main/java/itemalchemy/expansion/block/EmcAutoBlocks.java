package itemalchemy.expansion.block;

import itemalchemy.expansion.ItemAlchemyExpansion;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.util.Identifier;
import net.minecraft.util.registry.Registry;
import net.pitan76.itemalchemy.item.ItemGroups;
import net.pitan76.mcpitanlib.api.block.v2.CompatibleBlockSettings;
import net.pitan76.mcpitanlib.api.util.CompatIdentifier;

/**
 * 自动装置（EMC 分解器 / EMC 构物器）注册：方块、方块物品、BlockEntityType。
 * 沿用制卡台的 Fabric 原生 {@link Registry#register} 注册方式。
 *
 * <p>1.19.2 无 ItemGroupEvents：总开关关闭时只是不能合成/不能使用（见方块右键与 tick 闸门），
 * 物品栏条目仍会在注册时列出，开关变更后需重启才反映到物品栏。</p>
 */
public final class EmcAutoBlocks {

    private EmcAutoBlocks() {}

    /** EMC 分解器方块 */
    public static EmcConverterBlock CONVERTER;
    /** EMC 分解器 BlockItem */
    public static BlockItem CONVERTER_ITEM;
    /** EMC 分解器 BlockEntityType */
    public static BlockEntityType<EmcConverterBlockEntity> CONVERTER_TILE;

    /** EMC 构物器方块 */
    public static EmcEmitterBlock EMITTER;
    /** EMC 构物器 BlockItem */
    public static BlockItem EMITTER_ITEM;
    /** EMC 构物器 BlockEntityType */
    public static BlockEntityType<EmcEmitterBlockEntity> EMITTER_TILE;

    public static void init() {
        CONVERTER = Registry.register(Registry.BLOCK,
                new Identifier(ItemAlchemyExpansion.MOD_ID, "emc_converter"),
                new EmcConverterBlock(CompatibleBlockSettings.of(
                                CompatIdentifier.of(ItemAlchemyExpansion.MOD_ID, "emc_converter"))
                        .strength(2.0f, 6.0f)));

        CONVERTER_ITEM = Registry.register(Registry.ITEM,
                new Identifier(ItemAlchemyExpansion.MOD_ID, "emc_converter"),
                new BlockItem(CONVERTER, new Item.Settings()
                        .maxCount(64)
                        .group(ItemGroups.ITEM_ALCHEMY.build())));

        CONVERTER_TILE = Registry.register(Registry.BLOCK_ENTITY_TYPE,
                new Identifier(ItemAlchemyExpansion.MOD_ID, "emc_converter"),
                BlockEntityType.Builder.create(EmcConverterBlockEntity::new, CONVERTER).build(null));

        EMITTER = Registry.register(Registry.BLOCK,
                new Identifier(ItemAlchemyExpansion.MOD_ID, "emc_emitter"),
                new EmcEmitterBlock(CompatibleBlockSettings.of(
                                CompatIdentifier.of(ItemAlchemyExpansion.MOD_ID, "emc_emitter"))
                        .strength(2.0f, 6.0f)));

        EMITTER_ITEM = Registry.register(Registry.ITEM,
                new Identifier(ItemAlchemyExpansion.MOD_ID, "emc_emitter"),
                new BlockItem(EMITTER, new Item.Settings()
                        .maxCount(64)
                        .group(ItemGroups.ITEM_ALCHEMY.build())));

        EMITTER_TILE = Registry.register(Registry.BLOCK_ENTITY_TYPE,
                new Identifier(ItemAlchemyExpansion.MOD_ID, "emc_emitter"),
                BlockEntityType.Builder.create(EmcEmitterBlockEntity::new, EMITTER).build(null));

        ItemAlchemyExpansion.LOGGER.info("[IAExp] emc automation blocks registered: converter + emitter");
    }
}
