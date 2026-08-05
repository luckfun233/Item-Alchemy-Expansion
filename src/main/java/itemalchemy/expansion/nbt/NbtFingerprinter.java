package itemalchemy.expansion.nbt;

import itemalchemy.expansion.config.IAExpConfig;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.StringNbtReader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * NBT 指纹生成器：把物品 NBT 按策略压缩为规范化 SNBT 字符串。
 *
 * <p>SMART 模式：仅保留 {@link SmartNbtRules} 认定的重要 key，输出挑选后 NbtCompound 的 SNBT。
 * FULL 模式：保留全部 NBT（扣除 {@code ignoreNbtKeys} 与可选的 Damage/RepairCost），输出 NbtCompound 的 SNBT。</p>
 *
 * <p>输出的是合法 SNBT（如 {@code {AmmoId:"tacz:556x45"}}），可用 {@link #parseFingerprint(String)}
 * 通过 {@link StringNbtReader} 反解析回 {@link NbtCompound}，从而在提取槽重建带 NBT 的 ItemStack。</p>
 *
 * <p>规范化：key 按字典序复制到新 NbtCompound，确保等价 NBT 产生相同指纹（顺序无关）。</p>
 */
public final class NbtFingerprinter {

    private final IAExpConfig config;

    public NbtFingerprinter(IAExpConfig config) {
        this.config = config;
    }

    /**
     * 生成指纹。
     *
     * @param stack 物品堆（用于查询 namespace）
     * @param nbt   物品 NBT（stack.getNbt()）
     * @return SNBT 字符串；若过滤后无 key 返回空串（表示无需区分，等同于纯 ID）
     */
    public String fingerprint(ItemStack stack, NbtCompound nbt) {
        if (nbt == null || nbt.isEmpty()) return "";

        // FULL 与 SMART 都先计算「应忽略的 key 集合」（两模式通用，保证 ignoreNbtKeys 始终生效）
        Set<String> ignore = collectIgnoreKeys();

        if (config.nbtMode == IAExpConfig.NbtMode.FULL) {
            // FULL：保留全部 NBT，但扣除 ignoreNbtKeys 与（可选）Damage/RepairCost
            if (ignore.isEmpty()) {
                return nbt.toString();
            }
            return filterNbt(nbt, ignore);
        }

        // SMART：挑选重要 key（用 registry id 提取 namespace，而非 getItem().toString()）
        String itemId = net.minecraft.registry.Registries.ITEM.getId(stack.getItem()).toString();
        String namespace = SmartNbtRules.namespaceOf(itemId);
        Set<String> important = SmartNbtRules.importantKeysFor(namespace, config);
        // importantKeysFor 已扣除 ignoreNbtKeys，无需重复扣

        // 按字典序复制到新 NbtCompound（保证顺序无关）
        List<String> present = new ArrayList<>();
        for (String key : important) {
            if (nbt.contains(key)) present.add(key);
        }
        if (present.isEmpty()) {
            // 回退：NBT 非空但没有匹配任何已知 key → 保留全部 NBT（避免丢失），但仍扣除 ignore
            if (ignore.isEmpty()) {
                return nbt.toString();
            }
            return filterNbt(nbt, ignore);
        }
        Collections.sort(present);

        NbtCompound picked = new NbtCompound();
        for (String key : present) {
            picked.put(key, nbt.get(key));
        }
        return picked.toString();
    }

    /**
     * 收集本次应忽略的 NBT key 集合。
     * <ul>
     *   <li>{@code config.ignoreNbtKeys}：用户配置的通用忽略（两模式都生效）</li>
     *   <li>{@code config.fullIgnoreDamageAndRepairCost}：FULL 模式下默认忽略 Damage/RepairCost，
     *       避免工具因耐久不同产生大量变体（仅 FULL 模式生效；SMART 本就不纳入这两个 key）</li>
     * </ul>
     */
    private Set<String> collectIgnoreKeys() {
        Set<String> ignore = new HashSet<>();
        if (config.ignoreNbtKeys != null) {
            ignore.addAll(config.ignoreNbtKeys);
        }
        if (config.fullIgnoreDamageAndRepairCost && config.nbtMode == IAExpConfig.NbtMode.FULL) {
            ignore.add("Damage");
            ignore.add("RepairCost");
        }
        return ignore;
    }

    /** 把 nbt 中不在 ignore 集合中的 key 按字典序复制到新 NbtCompound，返回其 SNBT */
    private String filterNbt(NbtCompound nbt, Set<String> ignore) {
        List<String> keys = new ArrayList<>();
        for (String key : nbt.getKeys()) {
            if (!ignore.contains(key)) keys.add(key);
        }
        if (keys.isEmpty()) return "";
        Collections.sort(keys);
        NbtCompound filtered = new NbtCompound();
        for (String key : keys) {
            filtered.put(key, nbt.get(key));
        }
        return filtered.toString();
    }

    /**
     * 把指纹 SNBT 解析回 NbtCompound。解析失败返回 null（调用方回退为无 NBT 物品）。
     */
    public static NbtCompound parseFingerprint(String snbt) {
        if (snbt == null || snbt.isEmpty()) return null;
        try {
            return StringNbtReader.parse(snbt);
        } catch (Exception e) {
            // StringNbtReader.parse 抛 CommandSyntaxException（checked）；解析失败回退为无 NBT
            return null;
        }
    }
}
