package itemalchemy.expansion.nbt;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * NBT 忽略规则：按物品 namespace 排除易变状态键，确保存储/查询指纹一致。
 *
 * <p>全量模式下保留全部 NBT，但某些模组物品携带频繁变化的运行时状态
 * （如 TACZ 枪械的弹药数、开火模式、热量、配件等），这些状态不影响物品身份，
 * 却会导致存储时（配方构建的新物品）和查询时（玩家手持的旧物品）指纹失配。</p>
 *
 * <p>本类维护按 namespace 的内置忽略 key 列表，在指纹生成时自动排除这些 key。</p>
 */
public final class NbtIgnoreRules {

    private NbtIgnoreRules() {}

    /** 按 namespace 内置忽略 key（易变状态键，不影响物品身份） */
    private static final java.util.Map<String, Set<String>> BUILTIN_IGNORE_PER_MOD = new java.util.HashMap<>();

    static {
        // TACZ：枪械/弹药/配件的易变状态键
        Set<String> taczVolatile = new LinkedHashSet<>();
        taczVolatile.add("GunCurrentAmmoCount");  // 弹药数（频繁变化）
        taczVolatile.add("GunFireMode");          // 开火模式（可切换）
        taczVolatile.add("HasBulletInBarrel");    // 膛内是否有弹
        taczVolatile.add("GunLevelExp");          // 枪械经验
        taczVolatile.add("HeatAmount");           // 过热量
        taczVolatile.add("OverHeated");           // 过热锁定状态
        taczVolatile.add("LaserColor");           // 激光颜色
        taczVolatile.add("GunDisplayId");         // 显示模型 ID（可能随皮肤变化）
        taczVolatile.add("DummyAmmo");            // 虚拟弹药
        taczVolatile.add("MaxDummyAmmo");         // 虚拟弹药上限（运行时写入，init 新枪无此 key）
        taczVolatile.add("AttachmentLock");       // 配件锁定状态（玩家可切换，init 新枪无此 key）
        // 枪上安装的配件（可拆装，不应影响身份指纹）
        taczVolatile.add("AttachmentSCOPE");
        taczVolatile.add("AttachmentGRIP");
        taczVolatile.add("AttachmentMUZZLE");
        taczVolatile.add("AttachmentSTOCK");
        taczVolatile.add("AttachmentEXTENDED_MAG");
        taczVolatile.add("AttachmentLASER");
        BUILTIN_IGNORE_PER_MOD.put("tacz", taczVolatile);
    }

    /** 物品的 namespace（从 itemId "ns:path" 提取） */
    public static String namespaceOf(String itemId) {
        int idx = itemId.indexOf(':');
        return idx < 0 ? "minecraft" : itemId.substring(0, idx);
    }

    /**
     * 返回该 namespace 下应始终忽略的易变 NBT key 集合。
     */
    public static Set<String> builtinIgnoreKeysFor(String namespace) {
        Set<String> result = BUILTIN_IGNORE_PER_MOD.get(namespace);
        return result != null ? new LinkedHashSet<>(result) : new LinkedHashSet<>();
    }
}
