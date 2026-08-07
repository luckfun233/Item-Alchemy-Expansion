package itemalchemy.expansion;

import itemalchemy.expansion.config.IAExpConfigHolder;
import itemalchemy.expansion.nbt.ItemVariantKey;
import itemalchemy.expansion.nbt.NbtFingerprinter;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

/**
 * 全局服务单例：提供配置、NBT 指纹器，以及 ItemStack ↔ 变体键互转。
 *
 * <p>Mixin 代码通过本类访问配置与重建逻辑，避免在各 Mixin 里重复构造 fingerprinter。</p>
 */
public final class IAExpServices {

    private IAExpServices() {}

    private static NbtFingerprinter fingerprinter;

    /** 在 mod 初始化时调用一次 */
    public static void init() {
        IAExpConfigHolder.load();
        fingerprinter = new NbtFingerprinter(IAExpConfigHolder.get());
        ItemAlchemyExpansion.debug("[IAExp] services initialized (display={}, debug={})",
                IAExpConfigHolder.get().displayMode,
                IAExpConfigHolder.get().debugLogging);
    }

    /** 重新读取配置后调用（reload） */
    public static void refresh() {
        IAExpConfigHolder.reload();
        fingerprinter = new NbtFingerprinter(IAExpConfigHolder.get());
    }

    public static NbtFingerprinter fingerprinter() {
        if (fingerprinter == null) {
            // 防御性初始化（理论上 onInitialize 已调用）
            init();
        }
        return fingerprinter;
    }

    /** 从 ItemStack 生成变体键 */
    public static ItemVariantKey variantKeyOf(ItemStack stack) {
        ItemVariantKey vk = ItemVariantKey.fromStack(stack, fingerprinter());
        ItemAlchemyExpansion.debug("[IAExp] variantKeyOf: item={}, hasNbt={}, nbt={}, variant={}",
                stack.getItem(), stack.hasNbt(),
                stack.hasNbt() ? stack.getNbt() : "{}",
                vk.toStorageString());
        return vk;
    }

    /**
     * 从变体键重建带 NBT 的 ItemStack（count=1）。
     *
     * <p>用于 ExtractInventory.placeExtractSlots：把 registeredItems 里的变体键
     * 还原为可展示/可购买的 ItemStack。解析失败的物品（id 不存在或 SNBT 非法）返回空堆。</p>
     */
    public static ItemStack rebuildStack(ItemVariantKey key) {
        Identifier id = Identifier.tryParse(key.itemId);
        if (id == null || !Registries.ITEM.containsId(id)) {
            ItemAlchemyExpansion.LOGGER.warn("[IAExp] rebuildStack: item id not found: {}", key.itemId);
            return ItemStack.EMPTY;
        }
        Item item = Registries.ITEM.get(id);
        ItemStack stack = new ItemStack(item, 1);
        if (key.nbtFingerprint != null && !key.nbtFingerprint.isEmpty()) {
            NbtCompound nbt = NbtFingerprinter.parseFingerprint(key.nbtFingerprint);
            if (nbt != null) {
                stack.setNbt(nbt);
            } else {
                ItemAlchemyExpansion.LOGGER.warn("[IAExp] rebuildStack: failed to parse fingerprint: {}", key.nbtFingerprint);
            }
        }
        ItemAlchemyExpansion.debug("[IAExp] rebuildStack: key={} -> item={}, hasNbt={}, nbt={}",
                key.toStorageString(), stack.getItem(), stack.hasNbt(),
                stack.hasNbt() ? stack.getNbt() : "{}");
        return stack;
    }
}
