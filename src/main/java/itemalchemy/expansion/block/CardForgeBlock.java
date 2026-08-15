package itemalchemy.expansion.block;

import itemalchemy.expansion.ItemAlchemyExpansion;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.util.shape.VoxelShape;
import net.pitan76.mcpitanlib.api.block.ExtendBlockEntityProvider;
import net.pitan76.mcpitanlib.api.block.args.v2.PlacementStateArgs;
import net.pitan76.mcpitanlib.api.block.v2.CompatBlock;
import net.pitan76.mcpitanlib.api.block.v2.CompatibleBlockSettings;
import net.pitan76.mcpitanlib.api.event.block.BlockUseEvent;
import net.pitan76.mcpitanlib.api.event.block.StateReplacedEvent;
import net.pitan76.mcpitanlib.api.util.CompatActionResult;
import net.pitan76.mcpitanlib.api.util.VoxelShapeUtil;
import net.pitan76.mcpitanlib.core.serialization.CompatMapCodec;
import net.pitan76.mcpitanlib.core.serialization.codecs.CompatBlockMapCodecUtil;
import net.pitan76.mcpitanlib.midohra.block.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * 制卡台：EMC 卡配置方块。右键打开 GUI，可设置卡为私有/公有、关联两张卡、合并两卡。
 * BlockEntity 持有卡槽并实现 {@code SimpleScreenHandlerFactory}。
 */
public class CardForgeBlock extends CompatBlock implements ExtendBlockEntityProvider {

    protected final CompatMapCodec<? extends CompatBlock> codec =
            CompatBlockMapCodecUtil.createCodec(CardForgeBlock::new);

    public CardForgeBlock(CompatibleBlockSettings settings) {
        super(settings);
    }

    @Override
    public CompatMapCodec<? extends Block> getCompatCodec() {
        return codec;
    }

    @Override
    public CompatActionResult onRightClick(BlockUseEvent e) {
        if (e.isClient()) return e.success();
        BlockEntity be = e.getBlockEntity();
        if (be instanceof CardForgeBlockEntity forge) {
            e.player.openGuiScreen(forge);
            return e.consume();
        }
        return e.pass();
    }

    @Override
    public void onStateReplaced(StateReplacedEvent e) {
        if (e.isSameState()) return;
        e.spawnDropsInContainer();
        super.onStateReplaced(e);
    }

    @Override
    public @Nullable BlockState getPlacementState(PlacementStateArgs args) {
        return super.getPlacementState(args);
    }

    @Override
    public VoxelShape getOutlineShape(net.pitan76.mcpitanlib.api.block.args.v2.OutlineShapeEvent e) {
        return VoxelShapeUtil.cuboid(0, 0, 0, 1, 0.75, 1);
    }

    @Override
    public @Nullable <T extends BlockEntity> BlockEntityType<T> getBlockEntityType() {
        return (BlockEntityType<T>) CardForgeBlocks.FORGE_TILE;
    }

    @Override
    public boolean isTick() {
        return false;
    }
}