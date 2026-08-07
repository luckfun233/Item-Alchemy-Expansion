package itemalchemy.expansion.nbt;

import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.pitan76.itemalchemy.EMCManager;

/**
 * 潜影盒支持工具：解析内容物、计算内容物 EMC 之和、查找无 EMC 物品。
 *
 * <p>1.20.1 潜影盒 NBT 结构：
 * <pre>
 * {
 *   BlockEntityTag: {
 *     Items: [
 *       {Slot: 0b, id: "minecraft:stone", count: 64},  // count 在 1.20.1 为小写 int
 *       ...
 *     ]
 *   },
 *   display: { Name: '{"text":"Custom Name"}' }
 * }
 * </pre>
 * 颜色由物品 id 决定（minecraft:white_shulker_box 等），名称在 display.Name，
 * 内容物在 BlockEntityTag.Items。</p>
 *
 * <p>本类只做「读」操作，不修改潜影盒 NBT，确保取出时属性（颜色/名称/内容物）完整保留。</p>
 */
public final class ShulkerBoxSupport {

    private ShulkerBoxSupport() {}

    /** 判断物品堆是否是潜影盒（含 16 色） */
    public static boolean isShulkerBox(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        return isShulkerBox(stack.getItem());
    }

    /** 判断物品是否是潜影盒 */
    public static boolean isShulkerBox(Item item) {
        if (item == null) return false;
        if (!(item instanceof BlockItem)) return false;
        return ((BlockItem) item).getBlock() instanceof ShulkerBoxBlock;
    }

    /**
     * 计算潜影盒内容物 EMC 之和。
     *
     * <p>非潜影盒返回 0；空潜影盒返回 0。
     * EMC 按「物品 id × 数量」累加（与 {@link EMCManager#get(Item)} 同口径，按 id 计价）。</p>
     *
     * <p>注意：此处 {@code EMCManager.get(Item)} 不会触发本模组对潜影盒的 Mixin
     * （Mixin 拦截的是 {@code get(ItemStack)} 重载），避免递归。</p>
     */
    public static long sumEmc(ItemStack stack) {
        if (!isShulkerBox(stack)) return 0;
        NbtCompound blockEntityTag = getBlockEntityTag(stack);
        if (blockEntityTag == null) return 0;
        NbtList items = blockEntityTag.getList("Items", NbtElement.COMPOUND_TYPE);
        if (items.isEmpty()) return 0;
        long sum = 0;
        for (int i = 0; i < items.size(); i++) {
            NbtCompound entry = items.getCompound(i);
            ItemStack contentStack = ItemStack.fromNbt(entry);
            if (contentStack.isEmpty()) continue;
            // 按 id 查 EMC（同 id 同价），不递归潜影盒判定
            long itemEmc = EMCManager.get(contentStack.getItem());
            sum += itemEmc * contentStack.getCount();
        }
        return sum;
    }

    /**
     * 同时读取内容物列表和 EMC 总和（单次 NBT 解析）。
     *
     * <p>供渲染热点使用：避免同一帧内分别调用 {@link #getContents} 和 {@link #sumEmc}
     * 导致同一潜影盒 NBT 被解析两次。</p>
     *
     * @return {@link ContentsAndEmc}；非潜影盒返回空内容物 + sum=0
     */
    public static ContentsAndEmc getContentsAndSumEmc(ItemStack stack) {
        ItemStack[] contents = new ItemStack[27];
        for (int i = 0; i < 27; i++) contents[i] = ItemStack.EMPTY;
        long sum = 0;
        if (!isShulkerBox(stack)) return new ContentsAndEmc(contents, sum);
        NbtCompound blockEntityTag = getBlockEntityTag(stack);
        if (blockEntityTag == null) return new ContentsAndEmc(contents, sum);
        NbtList items = blockEntityTag.getList("Items", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < items.size(); i++) {
            NbtCompound entry = items.getCompound(i);
            ItemStack contentStack = ItemStack.fromNbt(entry);
            if (contentStack.isEmpty()) continue;
            // 放入槽位
            int slot = entry.getByte("Slot") & 0xFF;
            if (slot >= 0 && slot < 27) {
                contents[slot] = contentStack;
            } else {
                for (int j = 0; j < 27; j++) {
                    if (contents[j].isEmpty()) {
                        contents[j] = contentStack;
                        break;
                    }
                }
            }
            // 累加 EMC（同 sumEmc 逻辑，但复用已解析的 contentStack）
            long itemEmc = EMCManager.get(contentStack.getItem());
            sum += itemEmc * contentStack.getCount();
        }
        return new ContentsAndEmc(contents, sum);
    }

    /** getContentsAndSumEmc 的返回值：内容物数组 + EMC 总和 */
    public static final class ContentsAndEmc {
        public final ItemStack[] contents;
        public final long sumEmc;
        public ContentsAndEmc(ItemStack[] contents, long sumEmc) {
            this.contents = contents;
            this.sumEmc = sumEmc;
        }
    }

    /**
     * 查找潜影盒内第一个无 EMC 值的内容物。
     *
     * @return 第一个 EMC=0 的内容物 ItemStack；若全部有 EMC 或非潜影盒/空盒，返回 null。
     */
    public static ItemStack findNoEmcItem(ItemStack stack) {
        if (!isShulkerBox(stack)) return null;
        NbtCompound blockEntityTag = getBlockEntityTag(stack);
        if (blockEntityTag == null) return null;
        NbtList items = blockEntityTag.getList("Items", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < items.size(); i++) {
            NbtCompound entry = items.getCompound(i);
            ItemStack contentStack = ItemStack.fromNbt(entry);
            if (contentStack.isEmpty()) continue;
            if (EMCManager.get(contentStack.getItem()) == 0) {
                return contentStack;
            }
        }
        return null;
    }

    /** 读取潜影盒的 BlockEntityTag；不存在返回 null */
    private static NbtCompound getBlockEntityTag(ItemStack stack) {
        NbtCompound nbt = stack.getNbt();
        if (nbt == null) return null;
        if (!nbt.contains("BlockEntityTag", NbtElement.COMPOUND_TYPE)) return null;
        return nbt.getCompound("BlockEntityTag");
    }

    /**
     * 读取潜影盒内容物列表（用于客户端预览渲染）。
     *
     * @return 27 格内容物数组（与潜影盒容量一致），空位为 ItemStack.EMPTY。
     */
    public static ItemStack[] getContents(ItemStack stack) {
        ItemStack[] result = new ItemStack[27];
        for (int i = 0; i < 27; i++) result[i] = ItemStack.EMPTY;
        if (!isShulkerBox(stack)) return result;
        NbtCompound blockEntityTag = getBlockEntityTag(stack);
        if (blockEntityTag == null) return result;
        NbtList items = blockEntityTag.getList("Items", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < items.size(); i++) {
            NbtCompound entry = items.getCompound(i);
            int slot = entry.getByte("Slot") & 0xFF;
            if (slot >= 0 && slot < 27) {
                result[slot] = ItemStack.fromNbt(entry);
            } else {
                // 无效槽位也尝试放入（容错）
                for (int j = 0; j < 27; j++) {
                    if (result[j].isEmpty()) {
                        result[j] = ItemStack.fromNbt(entry);
                        break;
                    }
                }
            }
        }
        return result;
    }
}
