package itemalchemy.expansion.nbt;

import itemalchemy.expansion.config.IAExpConfig;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 智能匹配规则：决定 SMART 模式下哪些 NBT key 「影响物品身份」需纳入指纹。
 *
 * <p>三层来源（合并去重）：
 * <ol>
 *   <li>内置通用规则（{@link #BUILTIN_KEYS}）：原版药水、附魔、命名、CustomModelData 等</li>
 *   <li>按 namespace 内置规则（{@link #BUILTIN_PER_MOD}）：tacz 等</li>
 *   <li>配置追加（{@code smartNbtKeys} + {@code perModRules}）</li>
 * </ol>
 * 再扣除配置的 {@code ignoreNbtKeys}。</p>
 *
 * <p>不硬编码任何模组的内部类，仅按 key 名匹配，兼容任意模组。</p>
 */
public final class SmartNbtRules {

    private SmartNbtRules() {}

    /** 内置通用重要 key（适用所有物品） */
    private static final Set<String> BUILTIN_KEYS = new LinkedHashSet<>();

    /** 按 namespace 内置重要 key */
    private static final java.util.Map<String, Set<String>> BUILTIN_PER_MOD = new java.util.HashMap<>();

    static {
        // 原版 & 通用
        BUILTIN_KEYS.add("Potion");              // 药水类型 (1.20.1: "Potion" 字符串 tag)
        BUILTIN_KEYS.add("CustomPotionEffects"); // 自定义药水效果
        BUILTIN_KEYS.add("Enchantments");        // 附魔
        BUILTIN_KEYS.add("StoredEnchantments");  // 附魔书
        BUILTIN_KEYS.add("CustomModelData");     // 模型变体（很多模组用此区分外观/类型）
        BUILTIN_KEYS.add("Charged");             // 充能状态（如末影水晶、十字弩）
        BUILTIN_KEYS.add("EntityTag");           // 刷怪蛋/怪物桶的实体数据
        BUILTIN_KEYS.add("BucketEntityTag");     // 鱼桶实体数据
        BUILTIN_KEYS.add("map");                 // 地图 id
        BUILTIN_KEYS.add("Decorations");         // 地图装饰
        BUILTIN_KEYS.add("Recipes");             // 知识之书配方
        BUILTIN_KEYS.add("Fireworks");           // 烟花
        BUILTIN_KEYS.add("Explosion");           // 烟花爆炸
        BUILTIN_KEYS.add("Trim");                // 盔甲纹饰
        BUILTIN_KEYS.add("debug_sphere");        // 调试球
        // display.Name 不全量纳入（命名会变）；用户可在配置里加 display.Name 强制区分命名物品
        // Damage 不纳入（耐久变化太频繁，会让同一工具产生无数变体）；用户可配置加入

        // 按 namespace
        Set<String> taczKeys = new LinkedHashSet<>();
        taczKeys.add("AmmoId");        // tacz 子弹 ID
        taczKeys.add("AmmoCount");     // 弹药数（部分版本）
        taczKeys.add("MaxAmmoCount");
        taczKeys.add("AttachmentId");  // tacz 配件 ID（弹夹、瞄具、握把等）
        taczKeys.add("SkinId");        // tacz 皮肤 ID（部分版本用 Skin 主键）
        taczKeys.add("FireMode");      // tacz 开火模式
        BUILTIN_PER_MOD.put("tacz", taczKeys);
    }

    /**
     * 返回该 namespace 下应纳入指纹的 NBT key 集合（已扣 ignoreNbtKeys）。
     */
    public static Set<String> importantKeysFor(String namespace, IAExpConfig config) {
        Set<String> keys = new LinkedHashSet<>(BUILTIN_KEYS);

        // namespace 内置规则
        Set<String> modBuiltin = BUILTIN_PER_MOD.get(namespace);
        if (modBuiltin != null) keys.addAll(modBuiltin);

        // namespace 配置规则
        if (config.perModRules != null) {
            java.util.List<String> modCfg = config.perModRules.get(namespace);
            if (modCfg != null) keys.addAll(modCfg);
        }

        // 通用配置追加
        if (config.smartNbtKeys != null) keys.addAll(config.smartNbtKeys);

        // 扣除忽略
        if (config.ignoreNbtKeys != null) keys.removeAll(config.ignoreNbtKeys);

        return keys;
    }

    /** 物品的 namespace（从 itemId "ns:path" 提取） */
    public static String namespaceOf(String itemId) {
        int idx = itemId.indexOf(':');
        return idx < 0 ? "minecraft" : itemId.substring(0, idx);
    }
}
