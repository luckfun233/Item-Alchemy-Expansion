package itemalchemy.expansion.block;

import itemalchemy.expansion.config.IAExpConfig;
import itemalchemy.expansion.config.IAExpConfigHolder;
import itemalchemy.expansion.gui.EmcConverterScreenHandler;
import itemalchemy.expansion.item.EmcCardItem;
import itemalchemy.expansion.network.EmcCardBalanceUtil;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntityType;
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
 * EMC 转能器：漏斗输入的物品在有红石信号时转换为 EMC，存入卡槽内的 EMC 卡。
 *
 * <p>槽位：0 = EMC 卡；1-4 = 待转换物品输入。每 tick 在红石信号存在时把输入槽
 * 全部按 EMC 转为卡内余额并清空。自动装置总开关关闭时 tick 直接返回。</p>
 */
public class EmcConverterBlockEntity extends CompatBlockEntity
        implements Inventory, SidedInventory, SimpleScreenHandlerFactory, ExtendBlockEntityTicker<EmcConverterBlockEntity> {

    public static final int SLOT_COUNT = 5;
    public static final int CARD_SLOT = 0;
    public static final int INPUT_START = 1;
    public static final int INPUT_COUNT = 4;

    /** 漏斗等自动化可访问的槽位（仅输入槽；卡槽由 GUI 手工管理，防误抽走） */
    private static final int[] AUTOMATION_SLOTS = {INPUT_START, INPUT_START + 1, INPUT_START + 2, INPUT_START + 3};

    private final ItemStack[] slots = new ItemStack[SLOT_COUNT];

    /** 有红石信号期间的 tick 计数，按 {@code automationIntervalTicks} 降频 */
    private int workTicks = 0;

    /** 上一 tick 是否有红石信号（脉冲模式判断上升沿用） */
    private boolean wasPowered = false;

    public EmcConverterBlockEntity(BlockEntityType<?> type, TileCreateEvent event) {
        super(type, event);
        clearSlots();
    }

    public EmcConverterBlockEntity(BlockPos pos, BlockState state) {
        super(EmcAutoBlocks.CONVERTER_TILE, pos, state);
        clearSlots();
    }

    private void clearSlots() {
        for (int i = 0; i < SLOT_COUNT; i++) {
            slots[i] = ItemStack.EMPTY;
        }
    }

    // ==================== tick ====================

    @Override
    public void tick(TileTickEvent<EmcConverterBlockEntity> event) {
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
                    convert();
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
        convert();
    }

    /** 每 tick 仅转换一件物品（按槽位顺序），转入卡内余额；无 EMC 或入账失败则跳过该槽 */
    private void convert() {
        ItemStack card = slots[CARD_SLOT];
        if (card.isEmpty() || !(card.getItem() instanceof EmcCardItem)) return;
        for (int i = INPUT_START; i < SLOT_COUNT; i++) {
            ItemStack input = slots[i];
            if (input.isEmpty()) continue;
            long emc = EMCManager.get(input);
            if (emc <= 0) continue;
            // 入账失败（如绑卡目标玩家无队伍）时保留物品，避免「物品被吞、余额未入账」
            if (!EmcCardBalanceUtil.add(getServerWorld().getServer(), card, emc)) continue;
            input.decrement(1);
            if (input.isEmpty()) slots[i] = ItemStack.EMPTY;
            markDirty();
            return;
        }
    }

    // ==================== Inventory ====================

    @Override
    public int size() {
        return SLOT_COUNT;
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack s : slots) {
            if (!s.isEmpty()) return false;
        }
        return true;
    }

    @Override
    public ItemStack getStack(int slot) {
        if (slot < 0 || slot >= SLOT_COUNT) return ItemStack.EMPTY;
        return slots[slot];
    }

    @Override
    public ItemStack removeStack(int slot, int amount) {
        if (slot < 0 || slot >= SLOT_COUNT) return ItemStack.EMPTY;
        ItemStack cur = slots[slot];
        if (cur.isEmpty()) return ItemStack.EMPTY;
        ItemStack result = cur.split(amount);
        markDirty();
        return result;
    }

    @Override
    public ItemStack removeStack(int slot) {
        if (slot < 0 || slot >= SLOT_COUNT) return ItemStack.EMPTY;
        ItemStack cur = slots[slot];
        slots[slot] = ItemStack.EMPTY;
        markDirty();
        return cur;
    }

    @Override
    public void setStack(int slot, ItemStack stack) {
        if (slot < 0 || slot >= SLOT_COUNT) return;
        slots[slot] = stack;
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

    /** 卡槽仅接受 EMC 卡；输入槽拒绝 EMC 卡（卡会被当作物品转换消耗） */
    @Override
    public boolean isValid(int slot, ItemStack stack) {
        if (slot == CARD_SLOT) {
            return stack.getItem() instanceof EmcCardItem;
        }
        return !(stack.getItem() instanceof EmcCardItem);
    }

    @Override
    public int[] getAvailableSlots(Direction side) {
        return AUTOMATION_SLOTS;
    }

    @Override
    public boolean canInsert(int slot, ItemStack stack, @Nullable Direction dir) {
        return isValid(slot, stack);
    }

    /** 输入槽可被抽出（无 EMC 的残留物可通过下方漏斗排走）；卡槽不可被自动化抽走 */
    @Override
    public boolean canExtract(int slot, ItemStack stack, Direction dir) {
        return slot != CARD_SLOT;
    }

    @Override
    public void clear() {
        clearSlots();
        markDirty();
    }

    // ==================== NBT ====================

    @Override
    public void writeNbt(WriteNbtArgs args) {
        NbtCompound nbt = args.getNbt();
        for (int i = 0; i < SLOT_COUNT; i++) {
            nbt.put("slot_" + i, slots[i].writeNbt(new NbtCompound()));
        }
    }

    @Override
    public void readNbt(ReadNbtArgs args) {
        NbtCompound nbt = args.getNbt();
        for (int i = 0; i < SLOT_COUNT; i++) {
            slots[i] = ItemStack.fromNbt(nbt.getCompound("slot_" + i));
        }
    }

    // ==================== ScreenHandler ====================

    @Nullable
    @Override
    public ScreenHandler createMenu(CreateMenuEvent e) {
        return new EmcConverterScreenHandler(e, this);
    }

    @Override
    public net.minecraft.text.Text getDisplayName(DisplayNameArgs args) {
        return TextUtil.translatable("block.itemalchemy-expansion.emc_converter");
    }
}