package itemalchemy.expansion.network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import itemalchemy.expansion.ItemAlchemyExpansion;
import itemalchemy.expansion.nbt.ItemVariantKey;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 自动定价结果存储：精确层 + 通用层，含<b>全局</b>缓存文件。
 *
 * <p><b>两层结构</b>：
 * <ul>
 *   <li><b>精确层 preciseMap</b>：变体键（{@code ItemVariantKey.toStorageString()}）-> EMC
 *       （按 MIN 聚合，多条配方产同变体取最小值）</li>
 *   <li><b>通用层 generalMap</b>：物品 ID -> EMC（精确层按 ID 派生的 MIN）</li>
 * </ul>
 * </p>
 *
 * <p><b>缓存文件</b>：{@code config/itemalchemy-expansion/auto_emc.json}（全局，多世界复用）
 * <pre>{ "version": 5, "modFingerprint": "...", "precise": {...}, "general": {...} }</pre>
 * 命中（版本 + mod 指纹一致）则跳过重算；mod 列表变更或版本提升时自动失效重算。
 * 也可手动 {@code /itemalchemy-expansion reprice} 强制重算。</p>
 *
 * <p><b>查询语义</b>：精确模式 ON 时优先查 preciseMap，回退到通用层；OFF 时直接查 generalMap。
 * 详见 {@code MixinEMCManager}。</p>
 */
public final class AutoEmcStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Type MAP_TYPE = new TypeToken<LinkedHashMap<String, Long>>(){}.getType();
    private static final String GLOBAL_DIR = "itemalchemy-expansion";
    private static final String GLOBAL_FILE_NAME = "auto_emc.json";
    /** 缓存版本：提升此值会让旧缓存失效，强制重新扫描配方。v5: 缓存改为全局 + mod 指纹检测，旧 per-world 缓存路径不再使用 */
    private static final int CACHE_VERSION = 5;

    /** 精确层缓存（变体键 -> EMC） */
    private static Map<String, Long> preciseMap = new LinkedHashMap<>();
    /** 通用层缓存（物品 ID -> EMC） */
    private static Map<String, Long> generalMap = new LinkedHashMap<>();

    private AutoEmcStore() {}

    /** 查询变体键的自动精确 EMC；不存在返回 null */
    public static Long getPrecise(String variantKey) {
        return preciseMap.get(variantKey);
    }

    /** 查询物品 ID 的自动通用 EMC；不存在返回 null */
    public static Long getGeneral(String itemId) {
        return generalMap.get(itemId);
    }

    /** 是否两层都为空 */
    public static boolean isEmpty() {
        return preciseMap.isEmpty() && generalMap.isEmpty();
    }

    /**
     * 返回全局缓存文件路径：{@code config/itemalchemy-expansion/auto_emc.json}。
     *
     * <p>全局缓存可多世界复用，避免每个存档重复扫描配方。mod 列表变更时通过
     * {@link #computeModFingerprint()} 指纹比对自动失效。</p>
     */
    public static Path getSaveFile(MinecraftServer server) {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        return configDir.resolve(GLOBAL_DIR).resolve(GLOBAL_FILE_NAME);
    }

    /**
     * 计算当前 mod 列表指纹（modId@version 排序后拼接的 SHA-256 前 16 位）。
     *
     * <p>mod 增删或版本变更时指纹不同，触发缓存失效重算。
     * 用前 16 位足够（碰撞概率极低，最坏情况是多余重算）。</p>
     */
    public static String computeModFingerprint() {
        List<String> entries = new ArrayList<>();
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            entries.add(mod.getMetadata().getId() + "@" + mod.getMetadata().getVersion().getFriendlyString());
        }
        Collections.sort(entries);
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(String.join(";", entries).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", hash[i]));
            return sb.toString();
        } catch (Throwable t) {
            // 兜底：用整串 hashcode，碰撞概率高但不会崩
            return "fallback_" + Integer.toHexString(String.join(";", entries).hashCode());
        }
    }

    /**
     * 尝试从全局缓存文件加载（命中则填入内存并返回 true）。
     *
     * <p>缓存版本或 mod 指纹不匹配则忽略（返回 false，调用方触发重算）。</p>
     */
    public static boolean tryLoadCache(MinecraftServer server) {
        Path file = getSaveFile(server);
        if (!Files.exists(file)) return false;
        try {
            String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            CachePayload payload = GSON.fromJson(content, CachePayload.class);
            if (payload == null || payload.version != CACHE_VERSION) {
                ItemAlchemyExpansion.debug("[IAExp] auto emc cache version mismatch (expected {}, got {}), will recompute",
                        CACHE_VERSION, payload == null ? "null" : payload.version);
                return false;
            }
            String currentFp = computeModFingerprint();
            if (payload.modFingerprint == null || !payload.modFingerprint.equals(currentFp)) {
                ItemAlchemyExpansion.debug("[IAExp] auto emc cache mod fingerprint mismatch (cached={}, current={}), will recompute",
                        payload.modFingerprint, currentFp);
                return false;
            }
            preciseMap = payload.precise == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payload.precise);
            generalMap = payload.general == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payload.general);
            ItemAlchemyExpansion.debug("[IAExp] auto emc cache loaded: precise={} entries, general={} entries",
                    preciseMap.size(), generalMap.size());
            return true;
        } catch (Throwable t) {
            ItemAlchemyExpansion.LOGGER.warn("[IAExp] Failed to load auto emc cache ({}): {}", file, t.toString());
            return false;
        }
    }

    /** 写入全局缓存文件（重算后调用） */
    public static void writeCache(MinecraftServer server) {
        Path file = getSaveFile(server);
        try {
            Files.createDirectories(file.getParent());
            CachePayload payload = new CachePayload();
            payload.version = CACHE_VERSION;
            payload.modFingerprint = computeModFingerprint();
            payload.precise = preciseMap;
            payload.general = generalMap;
            String header = "// Item Alchemy Expansion 自动定价结果缓存（全局）。version=" + CACHE_VERSION + "\n"
                    + "// 删除此文件或运行 /itemalchemy-expansion reprice 可强制重算。\n";
            Files.write(file, (header + GSON.toJson(payload)).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            ItemAlchemyExpansion.LOGGER.error("[IAExp] Failed to write auto emc cache ({}): {}", file, e.getMessage());
        }
    }

    /**
     * 由 {@code RecipeAutoPricer} 调用：替换两层缓存 + 写盘。
     *
     * @param precise 精确层（变体键 -> EMC）
     * @param general 通用层（物品 ID -> EMC）
     */
    public static void store(Map<String, Long> precise, Map<String, Long> general) {
        preciseMap = precise == null ? new LinkedHashMap<>() : new LinkedHashMap<>(precise);
        generalMap = general == null ? new LinkedHashMap<>() : new LinkedHashMap<>(general);
    }

    /** 客户端 S2C 同步用：返回精确层快照 */
    public static Map<String, Long> snapshotPrecise() {
        return new LinkedHashMap<>(preciseMap);
    }

    /** 客户端 S2C 同步用：返回通用层快照 */
    public static Map<String, Long> snapshotGeneral() {
        return new LinkedHashMap<>(generalMap);
    }

    /** 客户端用 S2C 同步快照替换内存 */
    public static void applyFromSnapshot(Map<String, Long> precise, Map<String, Long> general) {
        preciseMap = precise == null ? new LinkedHashMap<>() : new LinkedHashMap<>(precise);
        generalMap = general == null ? new LinkedHashMap<>() : new LinkedHashMap<>(general);
    }

    /** 清空两层（用于 autoPricing 关闭或「重新定价」前） */
    public static void clear() {
        preciseMap.clear();
        generalMap.clear();
    }

    /** 删除缓存文件（用于「重新定价」时强制重算） */
    public static void deleteCache(MinecraftServer server) {
        Path file = getSaveFile(server);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            ItemAlchemyExpansion.LOGGER.warn("[IAExp] Failed to delete auto emc cache ({}): {}", file, e.getMessage());
        }
    }

    /**
     * 删除精确层中所有属于指定 itemId 的变体键条目。
     *
     * <p>用于「通用模式手动定价」：用户明确选择「同 ID 同价」时，必须清除该 ID 下所有变体键的
     * 自动精确值（L3），否则查询时 L3 优先于 L2 会覆盖用户设置的通用值。</p>
     *
     * <p>变体键格式为 {@code <itemId>\u0001<nbtFingerprint>}，用 {@link ItemVariantKey#SEPARATOR}
     * 作为分隔符匹配前缀。纯 itemId 的变体键（无 NBT 物品）也会被清除。</p>
     *
     * @return 实际删除的条目数
     */
    public static int removePreciseByItemId(String itemId) {
        if (itemId == null || itemId.isEmpty()) return 0;
        String prefix = itemId + ItemVariantKey.SEPARATOR;
        int removed = 0;
        for (Iterator<Map.Entry<String, Long>> it = preciseMap.entrySet().iterator(); it.hasNext(); ) {
            String key = it.next().getKey();
            // 匹配 itemId+SOH 前缀（带 NBT 变体）或纯 itemId（无 NBT 变体）
            if (key.startsWith(prefix) || key.equals(itemId)) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            ItemAlchemyExpansion.debug("[IAExp] auto emc precise layer removed {} variants for itemId={}",
                    removed, itemId);
        }
        return removed;
    }

    /** 缓存文件 JSON 结构 */
    private static class CachePayload {
        public int version;
        /** mod 列表指纹（{@link #computeModFingerprint()}），变更时缓存失效 */
        public String modFingerprint;
        public Map<String, Long> precise;
        public Map<String, Long> general;
    }
}
