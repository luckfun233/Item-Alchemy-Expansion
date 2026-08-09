package itemalchemy.expansion.nbt;

import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.collection.DefaultedList;
import net.pitan76.itemalchemy.EMCManager;

import java.util.stream.Stream;

/**
 * 潜影盒支持工具：解析内容物、计算内容物 EMC 之和、查找无 EMC 物品。
 *
 * <p>1.21.1 适配：潜影盒内容物从旧 {@code BlockEntityTag.Items} NBT 迁移到
 * {@link DataComponentTypes#CONTAINER} data component（{@link ContainerComponent}）。
 * 本类只做「读」操作，不修改潜影盒组件，确保取出时属性完整保留。</p>
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
     * 计算装了物品的潜影盒的总 EMC = 内容物 EMC 之和 + 潜影盒本身的 EMC。
     *
     * <p>定价规则：
     * <ul>
     *   <li>空潜影盒（无内容物）：返回 0。空盒被注册槽原逻辑（{@code EMCManager.get(stack) != 0}）拒绝。</li>
     *   <li>有内容物的潜影盒：返回 {@code 内容物 EMC 之和 + 盒子本身的 EMC}，
     *       盒子本身的 EMC 按 id 查 {@link EMCManager#get(Item)}（同 id 同价，与上游口径一致）。</li>
     * </ul>
     * </p>
     *
     * <p>{@code EMCManager.get(Item)} 不会触发本模组 Mixin（拦截的是 {@code get(ItemStack)} 重载），避免递归。
     * 盒子本体不在自身的 CONTAINER 组件内，故不会与内容物重复计算。</p>
     */
    public static long sumEmc(ItemStack stack) {
        if (!isShulkerBox(stack)) return 0;
        Stream<ItemStack> contents = streamContents(stack);
        if (contents == null) return 0;
        long[] sumHolder = {0};
        boolean[] hasContents = {false};
        contents.forEach(contentStack -> {
            if (contentStack.isEmpty()) return;
            hasContents[0] = true;
            long itemEmc = EMCManager.get(contentStack.getItem());
            sumHolder[0] += itemEmc * contentStack.getCount();
        });
        // 有内容物时加上潜影盒本身的 EMC；空盒 hasContents=false 返回 0
        if (hasContents[0]) {
            sumHolder[0] += EMCManager.get(stack.getItem());
        }
        return sumHolder[0];
    }

    /**
     * 同时读取内容物列表和 EMC 总和（单次组件读取）。
     *
     * <p>供渲染热点使用：避免同一帧内分别调用 {@link #getContents} 和 {@link #sumEmc}
     * 导致同一潜影盒 CONTAINER 组件被读取两次。</p>
     *
     * @return {@link ContentsAndEmc}；非潜影盒返回空内容物 + sum=0
     */
    public static ContentsAndEmc getContentsAndSumEmc(ItemStack stack) {
        ItemStack[] contents = new ItemStack[27];
        for (int i = 0; i < 27; i++) contents[i] = ItemStack.EMPTY;
        long sum = 0;
        boolean hasContents = false;
        if (!isShulkerBox(stack)) return new ContentsAndEmc(contents, sum);

        DefaultedList<ItemStack> list = getContentsAsList(stack);
        if (list == null) return new ContentsAndEmc(contents, sum);

        for (int i = 0; i < Math.min(27, list.size()); i++) {
            ItemStack contentStack = list.get(i);
            if (contentStack.isEmpty()) continue;
            contents[i] = contentStack;
            hasContents = true;
            long itemEmc = EMCManager.get(contentStack.getItem());
            sum += itemEmc * contentStack.getCount();
        }
        if (hasContents) {
            sum += EMCManager.get(stack.getItem());
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
        Stream<ItemStack> contents = streamContents(stack);
        if (contents == null) return null;
        return contents
                .filter(contentStack -> !contentStack.isEmpty())
                .filter(contentStack -> EMCManager.get(contentStack.getItem()) == 0)
                .findFirst()
                .orElse(null);
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

        DefaultedList<ItemStack> list = getContentsAsList(stack);
        if (list == null) return result;

        for (int i = 0; i < Math.min(27, list.size()); i++) {
            result[i] = list.get(i);
        }
        return result;
    }

    /**
     * 获取潜影盒的 CONTAINER 组件内容物流。非潜影盒或无 CONTAINER 组件返回 null。
     */
    private static Stream<ItemStack> streamContents(ItemStack stack) {
        ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
        if (container == null) return null;
        return container.streamNonEmpty();
    }

    /**
     * 用 {@link ContainerComponent#copyInto} 把内容物复制到 DefaultedList。
     * 返回的列表长度为 27，空位为 ItemStack.EMPTY。无 CONTAINER 组件返回 null。
     */
    private static DefaultedList<ItemStack> getContentsAsList(ItemStack stack) {
        ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
        if (container == null) return null;
        DefaultedList<ItemStack> list = DefaultedList.ofSize(27, ItemStack.EMPTY);
        container.copyInto(list);
        return list;
    }
}
