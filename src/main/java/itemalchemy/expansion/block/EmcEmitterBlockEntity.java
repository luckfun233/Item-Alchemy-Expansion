package itemalchemy.expansion.block;

import itemalchemy.expansion.IAExpServices;
import itemalchemy.expansion.config.IAExpConfig;
import itemalchemy.expansion.config.IAExpConfigHolder;
import itemalchemy.expansion.gui.EmcEmitterScreenHandler;
import itemalchemy.expansion.item.EmcCardItem;
import itemalchemy.expansion.nbt.ItemVariantKey;
import itemalchemy.expansion.network.EmcCardBalanceUtil;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SidedInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import net.pitan76.itemalchemy.EMCManager;
import net.pitan76.mcpitanlib.api.event.block.TileCreateEvent;
import net.pitan76.mcpitanlib.api.event.container.factory.DisplayNameArgs;
import net.pitan76.mcpitanlib.api.event.nbt.ReadNbtArgs;
import net.pitan76.mcpitanlib.api.event.nbt.WriteNbtArgs;
import net.pitan76.mcpitanlib.api.event.tile.TileTickEvent;
import net.pitan76.mcpitanlib.api.gui.args.CreateMenuEvent;
import net.pitan76.mcpitanlib.api.gui.v2.SimpleScreenHandlerFactory;
import net.pitan76.mcpitanlib.api.tile.CompatBlockEntity;
import net.pitan76.mcpitanlib.api.tile.ExtendBlockEntityTicker;
import net.pitan76.mcpitanlib.api.util.TextUtil;
import org.jetbrains.annotations.Nullable;

/**
 * EMC 输出器：卡槽内放 EMC 卡（由漏斗放入），GUI 中从打开者转换桌列表选择要喷出的物品。
 * 有红石信号时，从卡内扣除所选物品的 EMC 并按朝向喷出该物品（保留 NBT）。
 *
 * <p>所选物品存于方块 NBT（共享），他人打开可见同一设置。自动装置总开关关闭时 tick 直接返回。</p>
 */
public class EmcEmitterBlockEntity extends CompatBlockEntity
        implements Inventory, SidedInventory, ExtendBlockEntityTicker<EmcEmitterBlockEntity>, SimpleScreenHandlerFactory {

    public static final int SLOT_COUNT = 1;
    public static final int CARD_SLOT = 0;

    /** 漏斗等自动化可访问的槽位（仅卡槽） */
    private static final int[] AUTOMATION_SLOTS = {CARD_SLOT};

    /** NBT 键：所选物品变体键存储串 */
    public static final String SELECTED_KEY = "selected_variant";

    /** NBT 键：喷出朝向 */
    public static final String FACING_KEY = "facing";

    private final ItemStack[] slots = new ItemStack[SLOT_COUNT];
    private String selectedVariant = null;
    private Direction facing = Direction.UP;

    /** 有红石信号期间的 tick 计数，按 {@code automationIntervalTicks} 降频 */
    private int workTicks = 0;

    /** 上一 tick 是否有红石信号（脉冲模式判断上升沿用） */
    private boolean wasPowered = false;

    public EmcEmitterBlockEntity(BlockEntityType<?> type, TileCreateEvent event) {
        super(type, event);
        slots[0] = ItemStack.EMPTY;
    }

    public EmcEmitterBlockEntity(BlockPos pos, BlockState state) {
        super(EmcAutoBlocks.EMITTER_TILE, pos, state);
        slots[0] = ItemStack.EMPTY;
    }

    // ==================== 所选物品 ====================

    /** 返回当前所选物品的变体键存储串，未选返回 null */
    @Nullable
    public String getSelectedVariant() {
        return selectedVariant;
    }

    /** 设置所选物品变体键（null 表示清除） */
    public void setSelectedVariant(@Nullable String variant) {
        this.selectedVariant = (variant == null || variant.isEmpty()) ? null : variant;
        markDirty();
    }

    /** 返回喷出朝向（默认朝上） */
    public Direction getFacing() {
        return facing;
    }

    /** 设置喷出朝向 */
    public void setFacing(Direction dir) {
        this.facing = (dir == null) ? Direction.UP : dir;
        markDirty();
    }

    // ==================== tick ====================

    @Override
    public void tick(TileTickEvent<EmcEmitterBlockEntity> event) {
        if (event.isClient()) return;
        if (!IAExpConfigHolder.get().automationEnabled) return;
        if (!hasServerWorld()) return;
        World world = event.getWorld();
        if (world == null) return;
        IAExpConfig cfg = IAExpConfigHolder.get();
        boolean powered = world.isReceivingRedstonePower(getPos());
        if (cfg.automationMode == IAExpConfig.AutomationMode.PULSE) {
            // 脉冲模式：每次信号上升沿触发一件（类似投掷器，需高频信号，不持续运行）
            if (powered) {
                if (!wasPowered) {
                    wasPowered = true;
                    eject();
                }
            } else {
                wasPowered = false;
            }
            return;
        }
        // 持续模式：有信号期间按间隔工作
        if (!powered) return;
        int interval = Math.max(1, cfg.automationIntervalTicks);
        if (++workTicks % interval != 0) return;
        eject();
    }

    /** 扣除 EMC 并按朝向喷出所选物品 */
    private void eject() {
        ItemStack card = slots[CARD_SLOT];
        if (card.isEmpty() || !(card.getItem() instanceof EmcCardItem)) return;
        if (selectedVariant == null) return;

        ItemVariantKey vk = ItemVariantKey.fromStorageString(selectedVariant);
        if (vk == null) return;
        ItemStack out = IAExpServices.rebuildStack(vk);
        if (out.isEmpty()) return;

        long cost = EMCManager.get(out);
        if (cost <= 0) return;
        if (!EmcCardBalanceUtil.subtract(getServerWorld().getServer(), card, cost)) return;

        World world = getWorld();
        if (world == null) return;
        BlockPos pos = getPos();
        Direction dir = facing;

        // 生成位置：沿朝向偏移到方块面外（竖直朝向用较小偏移，避免嵌入相邻方块）
        double hOff = dir.getAxis().isHorizontal() ? 0.7 : 0.4;
        double x = pos.getX() + 0.5 + dir.getOffsetX() * hOff;
        double y = pos.getY() + 0.5 + dir.getOffsetY() * hOff;
        double z = pos.getZ() + 0.5 + dir.getOffsetZ() * hOff;

        // 速度参考投掷器：沿朝向 0.2~0.3，附加随机散布；非朝下时附带 0.2 上抛
        double speed = 0.2 + world.getRandom().nextDouble() * 0.1;
        double vx = dir.getOffsetX() * speed;
        double vy = dir.getOffsetY() * speed + (dir.getOffsetY() < 0 ? 0.0 : 0.2);
        double vz = dir.getOffsetZ() * speed;
        if (dir.getAxis().isHorizontal()) {
            double j = (world.getRandom().nextDouble() - world.getRandom().nextDouble()) * 0.1;
            vx += dir.getOffsetZ() * j;
            vz += dir.getOffsetX() * j;
        }

        ItemEntity entity = new ItemEntity(world, x, y, z, out);
        entity.setVelocity(vx, vy, vz);
        world.spawnEntity(entity);
        markDirty();
    }

    // ==================== Inventory ====================

    @Override
    public int size() {
        return SLOT_COUNT;
    }

    @Override
    public boolean isEmpty() {
        return slots[0].isEmpty();
    }

    @Override
    public ItemStack getStack(int slot) {
        return slot == 0 ? slots[0] : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeStack(int slot, int amount) {
        if (slot != 0) return ItemStack.EMPTY;
        ItemStack cur = slots[0];
        if (cur.isEmpty()) return ItemStack.EMPTY;
        ItemStack result = cur.split(amount);
        markDirty();
        return result;
    }

    @Override
    public ItemStack removeStack(int slot) {
        if (slot != 0) return ItemStack.EMPTY;
        ItemStack cur = slots[0];
        slots[0] = ItemStack.EMPTY;
        markDirty();
        return cur;
    }

    @Override
    public void setStack(int slot, ItemStack stack) {
        if (slot != 0) return;
        slots[0] = stack;
        if (!stack.isEmpty() && stack.getCount() > getMaxCountPerStack()) {
            stack.setCount(getMaxCountPerStack());
        }
        markDirty();
    }

    @Override
    public boolean canPlayerUse(PlayerEntity player) {
        if (world == null) return false;
        BlockPos pos = getPos();
        return player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64.0;
    }

    // ==================== 漏斗 / 自动化访问控制 ====================

    /** 卡槽仅接受 EMC 卡 */
    @Override
    public boolean isValid(int slot, ItemStack stack) {
        return stack.getItem() instanceof EmcCardItem;
    }

    @Override
    public int[] getAvailableSlots(Direction side) {
        return AUTOMATION_SLOTS;
    }

    @Override
    public boolean canInsert(int slot, ItemStack stack, @Nullable Direction dir) {
        return isValid(slot, stack);
    }

    /** 卡由漏斗放入后保留在内部（破坏方块时随容器掉落），不允自动化抽出 */
    @Override
    public boolean canExtract(int slot, ItemStack stack, Direction dir) {
        return false;
    }

    @Override
    public void clear() {
        slots[0] = ItemStack.EMPTY;
        markDirty();
    }

    // ==================== NBT ====================

    @Override
    public void writeNbt(WriteNbtArgs args) {
        NbtCompound nbt = args.getNbt();
        nbt.put("slot_0", slots[0].writeNbt(new NbtCompound()));
        if (selectedVariant != null) {
            nbt.putString(SELECTED_KEY, selectedVariant);
        }
        nbt.putString(FACING_KEY, facing.getName());
    }

    @Override
    public void readNbt(ReadNbtArgs args) {
        NbtCompound nbt = args.getNbt();
        slots[0] = ItemStack.fromNbt(nbt.getCompound("slot_0"));
        selectedVariant = nbt.contains(SELECTED_KEY) ? nbt.getString(SELECTED_KEY) : null;
        if (nbt.contains(FACING_KEY)) {
            Direction d = Direction.byName(nbt.getString(FACING_KEY));
            facing = (d == null) ? Direction.UP : d;
        }
    }

    // ==================== SimpleScreenHandlerFactory ====================

    @Nullable
    @Override
    public ScreenHandler createMenu(CreateMenuEvent e) {
        return new EmcEmitterScreenHandler(e, this);
    }

    @Override
    public net.minecraft.text.Text getDisplayName(DisplayNameArgs args) {
        return TextUtil.translatable("block.itemalchemy-expansion.emc_emitter");
    }
}