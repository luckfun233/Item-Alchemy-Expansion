package itemalchemy.expansion.gui;

import itemalchemy.expansion.block.EmcConverterBlockEntity;
import itemalchemy.expansion.item.EmcCardItem;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.pitan76.mcpitanlib.api.entity.Player;
import net.pitan76.mcpitanlib.api.gui.args.CreateMenuEvent;
import net.pitan76.mcpitanlib.api.gui.inventory.IInventory;
import net.pitan76.mcpitanlib.api.util.ItemStackUtil;
import net.pitan76.mcpitanlib.api.util.ScreenHandlerUtil;
import net.pitan76.mcpitanlib.api.util.SlotUtil;

/**
 * EMC 转能器 ScreenHandler：1 张 EMC 卡槽 + 4 个输入槽 + 玩家物品栏。
 */
public class EmcConverterScreenHandler extends net.pitan76.mcpitanlib.api.gui.SimpleScreenHandler {

    public static final int CARD_SLOT_X = 80;
    public static final int CARD_SLOT_Y = 53;
    public static final int INPUT_Y = 17;
    public static final int[] INPUT_X = {52, 70, 88, 106};

    public final EmcConverterBlockEntity tile;

    /** 客户端重建用（tile=null，空 inventory 布局） */
    public EmcConverterScreenHandler(CreateMenuEvent e) {
        this(e, IInventory.ofSize(EmcConverterBlockEntity.SLOT_COUNT), null);
    }

    /** 服务端开菜单用 */
    public EmcConverterScreenHandler(CreateMenuEvent e, EmcConverterBlockEntity tile) {
        this(e, tile, tile);
    }

    private EmcConverterScreenHandler(CreateMenuEvent e, Inventory forgeInv, EmcConverterBlockEntity tile) {
        super(EmcConverterScreenHandlers.TYPE, e);
        this.tile = tile;

        // 卡槽 + 4 输入槽
        addSlot(new CardSlot(forgeInv, EmcConverterBlockEntity.CARD_SLOT, CARD_SLOT_X, CARD_SLOT_Y));
        for (int i = 0; i < EmcConverterBlockEntity.INPUT_COUNT; i++) {
            addSlot(new InputSlot(forgeInv, EmcConverterBlockEntity.INPUT_START + i, INPUT_X[i], INPUT_Y));
        }

        addPlayerMainInventorySlots(e.getPlayerInventory(), 8, 96);
        addPlayerHotbarSlots(e.getPlayerInventory(), 8, 154);
    }

    @Override
    public boolean canUse(Player player) {
        return tile == null || tile.canPlayerUse(player.getPlayerEntity());
    }

    @Override
    public ItemStack quickMoveOverride(Player player, int index) {
        ItemStack itemStack = ItemStackUtil.empty();
        Slot slot = ScreenHandlerUtil.getSlot(this, index);
        if (!SlotUtil.hasStack(slot)) return itemStack;

        ItemStack stackInSlot = SlotUtil.getStack(slot);
        itemStack = stackInSlot.copy();

        int tileSlotCount = EmcConverterBlockEntity.SLOT_COUNT;
        if (index < tileSlotCount) {
            // 方块槽 -> 背包
            if (!this.callInsertItem(stackInSlot, tileSlotCount, tileSlotCount + 36, true)) {
                return ItemStackUtil.empty();
            }
        } else {
            // 背包 -> 方块槽（卡只能进卡槽，其余整组进输入槽）
            if (stackInSlot.getItem() instanceof EmcCardItem) {
                // 卡 maxCount=1：插入副本后必须递减原堆栈，否则刷卡
                ItemStack target = stackInSlot.copy();
                target.setCount(1);
                if (!this.callInsertItem(target, 0, 1, false)) return ItemStackUtil.empty();
                stackInSlot.decrement(1);
            } else {
                if (!this.callInsertItem(stackInSlot, 1, tileSlotCount, false)) return ItemStackUtil.empty();
            }
        }

        if (stackInSlot.isEmpty()) {
            SlotUtil.setStack(slot, ItemStackUtil.empty());
        } else {
            SlotUtil.markDirty(slot);
        }
        if (stackInSlot.getCount() == itemStack.getCount()) {
            return ItemStackUtil.empty();
        }
        SlotUtil.onTakeItem(slot, player, stackInSlot);
        return itemStack;
    }

    /** 卡槽：仅允许放入 EMC 卡（instanceof 判定，与 BlockEntity/网络层一致） */
    public static class CardSlot extends Slot {
        public CardSlot(Inventory inventory, int index, int x, int y) {
            super(inventory, index, x, y);
        }

        @Override
        public boolean canInsert(ItemStack stack) {
            return stack.getItem() instanceof EmcCardItem;
        }
    }

    /** 输入槽：拒绝 EMC 卡（卡会被当作物品转换消耗） */
    public static class InputSlot extends Slot {
        public InputSlot(Inventory inventory, int index, int x, int y) {
            super(inventory, index, x, y);
        }

        @Override
        public boolean canInsert(ItemStack stack) {
            return !(stack.getItem() instanceof EmcCardItem);
        }
    }
}