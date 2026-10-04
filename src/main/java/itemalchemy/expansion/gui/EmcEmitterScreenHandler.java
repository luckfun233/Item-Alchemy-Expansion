package itemalchemy.expansion.gui;

import itemalchemy.expansion.block.EmcEmitterBlockEntity;
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
 * EMC 输出器 ScreenHandler：1 张 EMC 卡槽 + 玩家物品栏（右侧列）。
 *
 * <p>左侧为物品选择列表（非容器数据，走自定义网络）；右侧为卡槽 + 玩家物品栏，
 * 卡槽支持直接拖动放入/取出（绑定卡同样可放）。服务端由
 * {@link EmcEmitterBlockEntity#createMenu} 传入 tile 创建；客户端由
 * ScreenHandlerType 工厂用空 inventory 重建（仅布局）。</p>
 */
public class EmcEmitterScreenHandler extends net.pitan76.mcpitanlib.api.gui.SimpleScreenHandler {

    public static final int CARD_SLOT_X = 184;
    public static final int CARD_SLOT_Y = 16;
    public static final int INV_X = 184;
    public static final int INV_Y = 40;
    public static final int HOTBAR_Y = 98;

    public final EmcEmitterBlockEntity tile;

    /** 客户端重建用（tile=null，空 inventory 布局） */
    public EmcEmitterScreenHandler(CreateMenuEvent e) {
        this(e, IInventory.ofSize(EmcEmitterBlockEntity.SLOT_COUNT), null);
    }

    /** 服务端开菜单用 */
    public EmcEmitterScreenHandler(CreateMenuEvent e, EmcEmitterBlockEntity tile) {
        this(e, tile, tile);
    }

    private EmcEmitterScreenHandler(CreateMenuEvent e, Inventory forgeInv, EmcEmitterBlockEntity tile) {
        super(EmcEmitterScreenHandlers.TYPE, e);
        this.tile = tile;

        // 卡槽 + 玩家物品栏
        addSlot(new CardSlot(forgeInv, EmcEmitterBlockEntity.CARD_SLOT, CARD_SLOT_X, CARD_SLOT_Y));
        addPlayerMainInventorySlots(e.getPlayerInventory(), INV_X, INV_Y);
        addPlayerHotbarSlots(e.getPlayerInventory(), INV_X, HOTBAR_Y);
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

        int tileSlotCount = EmcEmitterBlockEntity.SLOT_COUNT;
        if (index < tileSlotCount) {
            // 卡槽 -> 背包
            if (!this.callInsertItem(stackInSlot, tileSlotCount, tileSlotCount + 36, true)) {
                return ItemStackUtil.empty();
            }
        } else {
            // 背包 -> 卡槽（仅 EMC 卡；卡 maxCount=1，插入副本后必须递减原堆栈，否则刷卡）
            if (!(stackInSlot.getItem() instanceof EmcCardItem)) {
                return ItemStackUtil.empty();
            }
            ItemStack target = stackInSlot.copy();
            target.setCount(1);
            if (!this.callInsertItem(target, 0, 1, false)) return ItemStackUtil.empty();
            stackInSlot.decrement(1);
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

    /** 卡槽：仅允许放入 EMC 卡（绑定/关联/普通卡均可） */
    public static class CardSlot extends Slot {
        public CardSlot(Inventory inventory, int index, int x, int y) {
            super(inventory, index, x, y);
        }

        @Override
        public boolean canInsert(ItemStack stack) {
            return stack.getItem() instanceof EmcCardItem;
        }
    }
}
