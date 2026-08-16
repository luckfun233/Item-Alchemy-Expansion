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
import net.pitan76.mcpitanlib.api.event.block.AppendPropertiesArgs;
import net.pitan76.mcpitanlib.api.event.block.BlockPlacedEvent;
import net.pitan76.mcpitanlib.api.event.block.BlockUseEvent;
import net.pitan76.mcpitanlib.api.event.block.StateReplacedEvent;
import net.pitan76.mcpitanlib.api.state.property.CompatProperties;
import net.pitan76.mcpitanlib.api.state.property.DirectionProperty;
import net.pitan76.mcpitanlib.api.util.CompatActionResult;
import net.pitan76.mcpitanlib.api.util.VoxelShapeUtil;
import net.pitan76.mcpitanlib.core.serialization.CompatMapCodec;
import net.pitan76.mcpitanlib.core.serialization.codecs.CompatBlockMapCodecUtil;
import net.pitan76.mcpitanlib.midohra.block.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * EMC 输出器方块：卡内选择物品，有红石信号时按放置朝向喷出该物品。
 * 自动装置总开关关闭时右键提示且不可用。放置朝向为玩家视线方向（类似发射器，含上下）。
 */
public class EmcEmitterBlock extends CompatBlock implements ExtendBlockEntityProvider {

    /** 喷出朝向（全向，含上下） */
    public static final DirectionProperty FACING = CompatProperties.FACING;

    protected final CompatMapCodec<? extends CompatBlock> codec =
            CompatBlockMapCodecUtil.createCodec(EmcEmitterBlock::new);

    public EmcEmitterBlock(CompatibleBlockSettings settings) {
        super(settings);
        setDefaultState(getDefaultMidohraState().with(FACING, net.pitan76.mcpitanlib.midohra.util.math.Direction.NORTH));
    }

    @Override
    public void appendProperties(AppendPropertiesArgs args) {
        args.addProperty(FACING);
        super.appendProperties(args);
    }

    @Override
    public CompatMapCodec<? extends Block> getCompatCodec() {
        return codec;
    }

    @Override
    public void onPlaced(BlockPlacedEvent event) {
        if (event.isClient()) return;
        if (event.getBlockEntity() instanceof EmcEmitterBlockEntity tile) {
            // 直接取已放置状态的 FACING，保证喷口与模型正面一致
            // （getPlacementDirections 在潜行等场景与视线方向可能不一致，勿各自取方向源）
            Direction dir = Direction.NORTH;
            try {
                if (event.getState() != null) {
                    Direction d = event.getState().get(FACING.getProperty());
                    if (d != null) dir = d;
                }
            } catch (Throwable ignored) {
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
        if (e.getBlockEntity() instanceof EmcEmitterBlockEntity tile) {
            e.player.openGuiScreen(tile);
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
        BlockState state = super.getPlacementState(args);
        if (state == null) return null;
        // 参考投掷器：正面朝向放置者视线方向（含俯仰），水平方向翻转 180°（喷口面朝放置者）
        net.pitan76.mcpitanlib.midohra.util.math.Direction facing;
        try {
            net.pitan76.mcpitanlib.midohra.util.math.Direction look =
                    net.pitan76.mcpitanlib.midohra.util.math.Direction.of(args.getCtx().getPlayerLookDirection());
            facing = look.isHorizontal() ? look.getOpposite() : look;
        } catch (Throwable t) {
            facing = net.pitan76.mcpitanlib.midohra.util.math.Direction.NORTH;
        }
        return state.with(FACING, facing);
    }

    @Override
    public VoxelShape getOutlineShape(net.pitan76.mcpitanlib.api.block.args.v2.OutlineShapeEvent e) {
        return VoxelShapeUtil.cuboid(0, 0, 0, 1, 1, 1);
    }

    @Override
    public VoxelShape getCollisionShape(net.pitan76.mcpitanlib.api.block.args.v2.CollisionShapeEvent e) {
        // 满方块碰撞：保证按钮/拉杆等可附着在六个面上
        return VoxelShapeUtil.cuboid(0, 0, 0, 1, 1, 1);
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