package itemalchemy.expansion.gui;

import itemalchemy.expansion.block.CardForgeBlockEntity;
import itemalchemy.expansion.item.IAExpItems;
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
 * 制卡台 ScreenHandler：两张卡槽（仅 EMC 卡可放入）+ 玩家物品栏。
 *
 * <p>服务端由 {@link CardForgeBlockEntity#createMenu} 传入 forge 创建（槽绑定 BlockEntity inventory）；
 * 客户端由 ScreenHandlerType 工厂用空 inventory 重建（仅布局，物品由槽同步）。</p>
 */
public class CardForgeScreenHandler extends net.pitan76.mcpitanlib.api.gui.SimpleScreenHandler {

    public final CardForgeBlockEntity forge;

    /** 客户端重建用（forge=null，空 inventory 布局） */
    public CardForgeScreenHandler(CreateMenuEvent e) {
        this(e, IInventory.ofSize(CardForgeBlockEntity.SLOT_COUNT), null);
    }

    /** 服务端开菜单用 */
    public CardForgeScreenHandler(CreateMenuEvent e, CardForgeBlockEntity forge) {
        this(e, forge, forge);
    }

    private CardForgeScreenHandler(CreateMenuEvent e, Inventory forgeInv, CardForgeBlockEntity forge) {
        super(CardForgeScreenHandlers.TYPE, e);
        this.forge = forge;

        // 两张卡槽（服务端绑定 BlockEntity inventory）
        addSlot(new CardSlot(forgeInv, 0, 62, 44));
        addSlot(new CardSlot(forgeInv, 1, 98, 44));

        // 玩家物品栏 + 快捷栏
        addPlayerMainInventorySlots(e.getPlayerInventory(), 8, 148);
        addPlayerHotbarSlots(e.getPlayerInventory(), 8, 211);
    }

    @Override
    public boolean canUse(Player player) {
        return forge == null || forge.canPlayerUse(player.getPlayerEntity());
    }

    @Override
    public ItemStack quickMoveOverride(Player player, int index) {
        ItemStack itemStack = ItemStackUtil.empty();
        Slot slot = ScreenHandlerUtil.getSlot(this, index);
        if (!SlotUtil.hasStack(slot)) return itemStack;

        ItemStack stackInSlot = SlotUtil.getStack(slot);
        itemStack = stackInSlot.copy();

        if (index < 2) {
            // 卡槽 -> 背包
            if (!this.callInsertItem(stackInSlot, 2, 38, true)) {
                return ItemStackUtil.empty();
            }
        } else {
            // 背包 -> 卡槽（仅 EMC 卡）
            ItemStack target = stackInSlot.copy();
            target.setCount(1);
            if (target.getItem() == IAExpItems.EMC_CARD && !this.callInsertItem(target, 0, 2, false)) {
                return ItemStackUtil.empty();
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

    /** 制卡台卡槽：仅允许放入 EMC 卡 */
    public static class CardSlot extends Slot {
        public CardSlot(Inventory inventory, int index, int x, int y) {
            super(inventory, index, x, y);
        }

        @Override
        public boolean canInsert(ItemStack stack) {
            return stack.getItem() == IAExpItems.EMC_CARD;
        }
    }
}