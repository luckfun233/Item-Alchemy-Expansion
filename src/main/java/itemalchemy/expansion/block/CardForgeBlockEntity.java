package itemalchemy.expansion.block;

import itemalchemy.expansion.gui.CardForgeScreenHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.math.BlockPos;
import net.pitan76.mcpitanlib.api.event.block.TileCreateEvent;
import net.pitan76.mcpitanlib.api.event.container.factory.DisplayNameArgs;
import net.pitan76.mcpitanlib.api.event.nbt.ReadNbtArgs;
import net.pitan76.mcpitanlib.api.event.nbt.WriteNbtArgs;
import net.pitan76.mcpitanlib.api.gui.args.CreateMenuEvent;
import net.pitan76.mcpitanlib.api.gui.v2.SimpleScreenHandlerFactory;
import net.pitan76.mcpitanlib.api.tile.CompatBlockEntity;
import net.pitan76.mcpitanlib.api.util.TextUtil;
import org.jetbrains.annotations.Nullable;

/**
 * 制卡台 BlockEntity：持有两张 EMC 卡槽，实现 {@link SimpleScreenHandlerFactory} 供右键打开 GUI。
 * 卡槽持久化到 NBT；配置操作（私有/公有、关联、合并）由 ScreenHandler 通过 C2S 触发。
 */
public class CardForgeBlockEntity extends CompatBlockEntity implements Inventory, SimpleScreenHandlerFactory {

    /** 卡槽数量 */
    public static final int SLOT_COUNT = 2;

    private final ItemStack[] cardSlots = new ItemStack[SLOT_COUNT];

    public CardForgeBlockEntity(BlockEntityType<?> type, TileCreateEvent event) {
        super(type, event);
        for (int i = 0; i < SLOT_COUNT; i++) {
            cardSlots[i] = ItemStack.EMPTY;
        }
    }

    /** BlockEntityType.Builder.create 用（pos/state 构造） */
    public CardForgeBlockEntity(BlockPos pos, BlockState state) {
        super(CardForgeBlocks.FORGE_TILE, pos, state);
        for (int i = 0; i < SLOT_COUNT; i++) {
            cardSlots[i] = ItemStack.EMPTY;
        }
    }

    // ==================== Inventory ====================

    @Override
    public int size() {
        return SLOT_COUNT;
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack s : cardSlots) {
            if (!s.isEmpty()) return false;
        }
        return true;
    }

    @Override
    public ItemStack getStack(int slot) {
        if (slot < 0 || slot >= SLOT_COUNT) return ItemStack.EMPTY;
        return cardSlots[slot];
    }

    @Override
    public ItemStack removeStack(int slot, int amount) {
        if (slot < 0 || slot >= SLOT_COUNT) return ItemStack.EMPTY;
        ItemStack cur = cardSlots[slot];
        if (cur.isEmpty()) return ItemStack.EMPTY;
        ItemStack result = cur.split(amount);
        markDirty();
        return result;
    }

    @Override
    public ItemStack removeStack(int slot) {
        if (slot < 0 || slot >= SLOT_COUNT) return ItemStack.EMPTY;
        ItemStack cur = cardSlots[slot];
        cardSlots[slot] = ItemStack.EMPTY;
        markDirty();
        return cur;
    }

    @Override
    public void setStack(int slot, ItemStack stack) {
        if (slot < 0 || slot >= SLOT_COUNT) return;
        cardSlots[slot] = stack;
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

    @Override
    public void clear() {
        for (int i = 0; i < SLOT_COUNT; i++) {
            cardSlots[i] = ItemStack.EMPTY;
        }
        markDirty();
    }

    // ==================== NBT 持久化 ====================

    @Override
    public void writeNbt(WriteNbtArgs args) {
        NbtCompound nbt = args.getNbt();
        for (int i = 0; i < SLOT_COUNT; i++) {
            nbt.put("slot_" + i, cardSlots[i].writeNbt(new NbtCompound()));
        }
    }

    @Override
    public void readNbt(ReadNbtArgs args) {
        NbtCompound nbt = args.getNbt();
        for (int i = 0; i < SLOT_COUNT; i++) {
            cardSlots[i] = ItemStack.fromNbt(nbt.getCompound("slot_" + i));
        }
    }

    // ==================== SimpleScreenHandlerFactory ====================

    @Nullable
    @Override
    public ScreenHandler createMenu(CreateMenuEvent e) {
        return new CardForgeScreenHandler(e, this);
    }

    @Override
    public net.minecraft.text.Text getDisplayName(DisplayNameArgs args) {
        return TextUtil.translatable("block.itemalchemy-expansion.card_forge");
    }
}