package itemalchemy.expansion.block;

import itemalchemy.expansion.ItemAlchemyExpansion;
import itemalchemy.expansion.config.IAExpConfigHolder;
import itemalchemy.expansion.network.EmcAutoNetwork;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.text.Text;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.pitan76.mcpitanlib.api.block.ExtendBlockEntityProvider;
import net.pitan76.mcpitanlib.api.block.args.v2.PlacementStateArgs;
import net.pitan76.mcpitanlib.api.block.v2.CompatBlock;
import net.pitan76.mcpitanlib.api.block.v2.CompatibleBlockSettings;
import net.pitan76.mcpitanlib.api.event.block.BlockPlacedEvent;
import net.pitan76.mcpitanlib.api.event.block.BlockUseEvent;
import net.pitan76.mcpitanlib.api.event.block.StateReplacedEvent;
import net.pitan76.mcpitanlib.api.util.CompatActionResult;
import net.pitan76.mcpitanlib.api.util.VoxelShapeUtil;
import net.pitan76.mcpitanlib.core.serialization.CompatMapCodec;
import net.pitan76.mcpitanlib.core.serialization.codecs.CompatBlockMapCodecUtil;
import net.pitan76.mcpitanlib.midohra.block.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * EMC 输出器方块：卡内选择物品，有红石信号时按放置朝向喷出该物品。
 * 自动装置总开关关闭时右键提示且不可用。
 */
public class EmcEmitterBlock extends CompatBlock implements ExtendBlockEntityProvider {

    protected final CompatMapCodec<? extends CompatBlock> codec =
            CompatBlockMapCodecUtil.createCodec(EmcEmitterBlock::new);

    public EmcEmitterBlock(CompatibleBlockSettings settings) {
        super(settings);
    }

    @Override
    public CompatMapCodec<? extends Block> getCompatCodec() {
        return codec;
    }

    @Override
    public void onPlaced(BlockPlacedEvent event) {
        if (event.isClient()) return;
        if (event.getBlockEntity() instanceof EmcEmitterBlockEntity tile) {
            // 朝向 = 玩家放置朝向的反向（面朝玩家）
            Direction dir = Direction.UP;
            if (event.getPlacer() != null) {
                dir = event.getPlacer().getHorizontalFacing().getOpposite();
            }
            tile.setFacing(dir);
        }
        super.onPlaced(event);
    }

    @Override
    public CompatActionResult onRightClick(BlockUseEvent e) {
        if (e.isClient()) return e.success();
        if (!IAExpConfigHolder.get().automationEnabled) {
            e.player.getServerPlayer().ifPresent(p -> p.sendMessage(
                    Text.translatable("itemalchemy-expansion.automation.disabled"), true));
            return e.success();
        }
        if (e.getBlockEntity() instanceof EmcEmitterBlockEntity) {
            e.player.getServerPlayer().ifPresent(p ->
                    EmcAutoNetwork.sendOpen(p, e.getPos()));
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
        return (BlockEntityType<T>) EmcAutoBlocks.EMITTER_TILE;
    }

    @Override
    public boolean isTick() {
        return true;
    }
}