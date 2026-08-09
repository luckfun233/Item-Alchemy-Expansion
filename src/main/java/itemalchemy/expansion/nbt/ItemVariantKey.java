package itemalchemy.expansion.nbt;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.Identifier;
import net.minecraft.registry.Registries;

import java.util.Objects;

/**
 * 物品变体键 = itemId + 可选的 NBT 指纹。
 *
 * <p>用于在 {@code TeamState.registeredItems}（原本是 {@code List<String>} 纯物品 ID）中区分
 * 同 ID 不同 NBT 的物品（如 tacz 子弹、药水、附魔工具）。</p>
 *
 * <p>存储格式：{@code <itemId>} 或 {@code <itemId>\u0001<nbtFingerprint>}。
 * 分隔符 {@code \u0001}（SOH）不会出现在合法 Identifier 中，安全。</p>
 *
 * <p>EMC 仍按 {@code <itemId>} 查 {@code EMCManager}，同 ID 同价（F2）。</p>
 */
public final class ItemVariantKey {

    /** 存储分隔符（SOH，合法 Identifier 不含此字符） */
    public static final char SEPARATOR = '\u0001';

    public final String itemId;
    /** NBT 指纹，{@code null} 或空串表示纯 ID 物品（无 NBT 区分需求） */
    public final String nbtFingerprint;

    private ItemVariantKey(String itemId, String nbtFingerprint) {
        this.itemId = itemId;
        this.nbtFingerprint = (nbtFingerprint == null || nbtFingerprint.isEmpty()) ? null : nbtFingerprint;
    }

    /** 纯 ID 变体（无 NBT 区分） */
    public static ItemVariantKey ofId(String itemId) {
        return new ItemVariantKey(itemId, null);
    }

    /** 带 NBT 指纹的变体 */
    public static ItemVariantKey of(String itemId, String nbtFingerprint) {
        return new ItemVariantKey(itemId, nbtFingerprint);
    }

    /**
     * 从 ItemStack 生成变体键（按配置策略计算 NBT 指纹）。
     *
     * <p>1.21.1 适配：用 {@link ComponentNbtView#collectEffectiveNbt(ItemStack)} 收集关键
     * data component 为 NbtCompound，再交给 {@link NbtFingerprinter} 生成指纹。</p>
     */
    public static ItemVariantKey fromStack(ItemStack stack, NbtFingerprinter fingerprinter) {
        Item item = stack.getItem();
        Identifier id = Registries.ITEM.getId(item);
        String itemId = id.toString();
        NbtCompound nbt = ComponentNbtView.collectEffectiveNbt(stack);
        if (nbt.isEmpty()) {
            return new ItemVariantKey(itemId, null);
        }
        String fp = fingerprinter.fingerprint(stack, nbt);
        return new ItemVariantKey(itemId, fp);
    }

    /** 解析存储字符串为变体键 */
    public static ItemVariantKey fromStorageString(String s) {
        if (s == null) return null;
        int idx = s.indexOf(SEPARATOR);
        if (idx < 0) {
            return new ItemVariantKey(s, null);
        }
        String itemId = s.substring(0, idx);
        String fp = s.substring(idx + 1);
        return new ItemVariantKey(itemId, fp.isEmpty() ? null : fp);
    }

    /** 是否为纯 ID 变体（无 NBT 指纹） */
    public boolean isPlainId() {
        return nbtFingerprint == null;
    }

    /** 序列化为存储字符串 */
    public String toStorageString() {
        if (nbtFingerprint == null) return itemId;
        return itemId + SEPARATOR + nbtFingerprint;
    }

    /** 仅物品 ID（用于查 EMC，同 ID 同价） */
    public String itemId() {
        return itemId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ItemVariantKey)) return false;
        ItemVariantKey that = (ItemVariantKey) o;
        return Objects.equals(itemId, that.itemId) && Objects.equals(nbtFingerprint, that.nbtFingerprint);
    }

    @Override
    public int hashCode() {
        return Objects.hash(itemId, nbtFingerprint);
    }

    @Override
    public String toString() {
        return isPlainId() ? itemId : (itemId + SEPARATOR + nbtFingerprint);
    }
}
