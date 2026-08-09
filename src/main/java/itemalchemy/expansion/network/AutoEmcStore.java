package itemalchemy.expansion.network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import itemalchemy.expansion.ItemAlchemyExpansion;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 自动定价结果存储：精确层 + 通用层，含本存档缓存文件。
 *
 * <p><b>两层结构</b>：
 * <ul>
 *   <li><b>精确层 preciseMap</b>：变体键（{@code ItemVariantKey.toStorageString()}）-> EMC
 *       （按 MIN 聚合，多条配方产同变体取最小值）</li>
 *   <li><b>通用层 generalMap</b>：物品 ID -> EMC（精确层按 ID 派生的 MIN）</li>
 * </ul>
 * </p>
 *
 * <p><b>缓存文件</b>：{@code <world>/itemalchemy_expansion_auto_emc.json}
 * <pre>{ "version": 1, "precise": {...}, "general": {...} }</pre>
 * 命中则跳过重算；配方变更时可手动 {@code /itemalchemy-expansion reprice} 强制重算。</p>
 *
 * <p><b>查询语义</b>：精确模式 ON 时优先查 preciseMap，回退到通用层；OFF 时直接查 generalMap。
 * 详见 {@code MixinEMCManager}。</p>
 */
public final class AutoEmcStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Type MAP_TYPE = new TypeToken<LinkedHashMap<String, Long>>(){}.getType();
    private static final String SAVE_FILE_NAME = "itemalchemy_expansion_auto_emc.json";
    /** 缓存版本：提升此值会让旧缓存失效，强制重新扫描配方。v4: 改用「标签 Ingredient 取最便宜匹配堆」+「本轮中间产物参与下轮解析」，计算结果与旧版不同需重算 */
    private static final int CACHE_VERSION = 4;

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

    /** 返回本存档缓存文件路径 */
    public static Path getSaveFile(MinecraftServer server) {
        Path worldRoot = server.getSavePath(WorldSavePath.ROOT);
        return worldRoot.resolve(SAVE_FILE_NAME);
    }

    /**
     * 尝试从缓存文件加载（命中则填入内存并返回 true）。
     *
     * <p>缓存格式版本不匹配则忽略（返回 false，调用方应触发重算）。</p>
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

    /** 写入缓存文件（启动重算后调用） */
    public static void writeCache(MinecraftServer server) {
        Path file = getSaveFile(server);
        try {
            Files.createDirectories(file.getParent());
            CachePayload payload = new CachePayload();
            payload.version = CACHE_VERSION;
            payload.precise = preciseMap;
            payload.general = generalMap;
            String header = "// Item Alchemy Expansion 自动定价结果缓存。version=" + CACHE_VERSION + "\n"
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

    /** 缓存文件 JSON 结构 */
    private static class CachePayload {
        public int version;
        public Map<String, Long> precise;
        public Map<String, Long> general;
    }
}
