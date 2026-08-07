package itemalchemy.expansion.network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import itemalchemy.expansion.ItemAlchemyExpansion;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;
import net.pitan76.itemalchemy.EMCManager;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 玩家「精确覆盖」存储：按变体键（itemId + NBT 指纹）记录玩家手动设定的 EMC 值。
 *
 * <p><b>设计目的</b>：精确模式（{@code preciseMode=true}）下，EMC 查询优先级最高的是
 * 玩家手动设定的精确值。本类提供本存档 + 全局两层持久化：
 * <ul>
 *   <li>本存档：{@code <world>/itemalchemy_expansion_precise_overrides.json}</li>
 *   <li>全局：{@code config/itemalchemy-expansion/precise_emc.json}</li>
 *   <li>内存：合并 map，本存档覆盖全局</li>
 * </ul>
 * </p>
 *
 * <p><b>key 格式</b>：{@code ItemVariantKey.toStorageString()}，
 * 即 {@code <itemId>} 或 {@code <itemId>\u0001<nbtFingerprint>}。</p>
 *
 * <p><b>与通用 EMC 的关系</b>：
 * <ul>
 *   <li>通用层（{@link EMCManager#map} + {@link PerSaveEmcStore} + {@link GlobalEmcStore}）按物品 ID 计价。</li>
 *   <li>精确层（本类）按变体键计价，仅精确模式开启时参与查询。</li>
 *   <li>玩家在 K 键 GUI 选择「精确」作用范围时写入本类；选「通用」时走原有 {@link PerSaveEmcStore}/{@link GlobalEmcStore}。</li>
 * </ul>
 * </p>
 *
 * <p><b>线程安全</b>：所有方法在服务端主线程调用。</p>
 */
public final class PreciseEmcStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Type MAP_TYPE = new TypeToken<LinkedHashMap<String, Long>>(){}.getType();
    private static final String SAVE_FILE_NAME = "itemalchemy_expansion_precise_overrides.json";
    private static final String GLOBAL_DIR = "itemalchemy-expansion";
    private static final String GLOBAL_FILE_NAME = "precise_emc.json";

    /** 内存合并 map：本存档覆盖全局。key = 变体键存储串 */
    private static Map<String, Long> merged = new LinkedHashMap<>();

    /** 全局层（仅供 setGlobal 写盘用，加载时合并到 merged） */
    private static Map<String, Long> globalLayer = new LinkedHashMap<>();
    /** 本存档层（仅供 setSave 写盘用） */
    private static Map<String, Long> saveLayer = new LinkedHashMap<>();

    /** 当前服务端（用于 set 时定位存档目录） */
    private static MinecraftServer currentServer;

    private PreciseEmcStore() {}

    /** 返回本存档精确覆盖文件路径 */
    public static Path getSaveFile(MinecraftServer server) {
        Path worldRoot = server.getSavePath(WorldSavePath.ROOT);
        return worldRoot.resolve(SAVE_FILE_NAME);
    }

    /** 返回全局精确覆盖文件路径：{@code config/itemalchemy-expansion/precise_emc.json} */
    public static Path getGlobalFile() {
        Path configDir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir();
        return configDir.resolve(GLOBAL_DIR).resolve(GLOBAL_FILE_NAME);
    }

    /** 查询变体键的精确 EMC（合并层）；不存在返回 null */
    public static Long get(String variantKey) {
        return merged.get(variantKey);
    }

    /** 返回合并层副本（仅供客户端 S2C 同步用） */
    public static Map<String, Long> snapshot() {
        return new LinkedHashMap<>(merged);
    }

    /** 是否有任何精确覆盖 */
    public static boolean isEmpty() {
        return merged.isEmpty();
    }

    /**
     * 设置变体键的精确 EMC。
     *
     * @param server    服务端
     * @param variantKey 变体键存储串（{@code ItemVariantKey.toStorageString()}）
     * @param emc       新 EMC（>= 0）
     * @param global    true=写全局层，false=写本存档层
     */
    public static void set(MinecraftServer server, String variantKey, long emc, boolean global) {
        if (global) {
            globalLayer.put(variantKey, emc);
            writeGlobal();
        } else {
            saveLayer.put(variantKey, emc);
            writeSave(server);
        }
        merged.put(variantKey, emc);
        ItemAlchemyExpansion.debug("[IAExp] precise emc set: {}={} (global={})", variantKey, emc, global);
    }

    /**
     * 删除变体键的精确 EMC（若存在）。
     *
     * @return true 若实际删除了一条
     */
    public static boolean remove(MinecraftServer server, String variantKey) {
        boolean removed = false;
        if (saveLayer.remove(variantKey) != null) {
            writeSave(server);
            removed = true;
        }
        if (globalLayer.remove(variantKey) != null) {
            writeGlobal();
            removed = true;
        }
        if (removed) {
            merged.remove(variantKey);
        }
        return removed;
    }

    /**
     * 服务端启动时调用：加载全局层 + 本存档层，合并到内存。
     *
     * <p>本存档层覆盖全局层（同变体键）。</p>
     */
    public static void load(MinecraftServer server) {
        currentServer = server;
        globalLayer = readMap(getGlobalFile());
        saveLayer = readMap(getSaveFile(server));

        merged = new LinkedHashMap<>();
        merged.putAll(globalLayer);
        // 本存档覆盖全局
        for (Map.Entry<String, Long> e : saveLayer.entrySet()) {
            merged.put(e.getKey(), e.getValue());
        }
        ItemAlchemyExpansion.debug("[IAExp] precise emc loaded: global={} entries, save={} entries, merged={} entries",
                globalLayer.size(), saveLayer.size(), merged.size());
    }

    /**
     * 清空本存档层中所有条目（用于「重新定价」对话框：玩家选「是」时清空候选并重算）。
     *
     * <p>不动全局层（来源混杂，可能含上游/其它存档的设定）。</p>
     */
    public static void clearSaveLayer(MinecraftServer server) {
        if (saveLayer.isEmpty()) return;
        saveLayer = new LinkedHashMap<>();
        writeSave(server);
        // 重建合并层
        merged = new LinkedHashMap<>();
        merged.putAll(globalLayer);
        ItemAlchemyExpansion.debug("[IAExp] precise emc save layer cleared");
    }

    /** 返回本存档层所有变体键（用于「重新定价」对话框扫描候选） */
    public static Map<String, Long> getSaveLayerSnapshot() {
        return new LinkedHashMap<>(saveLayer);
    }

    // ============ 私有：读写文件 ============

    private static Map<String, Long> readMap(Path file) {
        if (!Files.exists(file)) return new LinkedHashMap<>();
        try {
            String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            Map<String, Long> map = GSON.fromJson(content, MAP_TYPE);
            return map == null ? new LinkedHashMap<>() : new LinkedHashMap<>(map);
        } catch (Throwable t) {
            ItemAlchemyExpansion.LOGGER.warn("[IAExp] Failed to read precise emc ({}): {}", file, t.toString());
            return new LinkedHashMap<>();
        }
    }

    private static void writeSave(MinecraftServer server) {
        Path file = getSaveFile(server);
        try {
            Files.createDirectories(file.getParent());
            String header = "// Item Alchemy Expansion 精确 EMC 覆盖（本存档）。key = <itemId>\\u0001<nbtFingerprint>。\n";
            Files.write(file, (header + GSON.toJson(saveLayer)).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            ItemAlchemyExpansion.LOGGER.error("[IAExp] Failed to write precise emc save ({}): {}", file, e.getMessage());
        }
    }

    private static void writeGlobal() {
        Path file = getGlobalFile();
        try {
            Files.createDirectories(file.getParent());
            String header = "// Item Alchemy Expansion 精确 EMC 覆盖（全局）。key = <itemId>\\u0001<nbtFingerprint>。\n";
            Files.write(file, (header + GSON.toJson(globalLayer)).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            ItemAlchemyExpansion.LOGGER.error("[IAExp] Failed to write precise emc global ({}): {}", file, e.getMessage());
        }
    }

    /** 用 S2C 同步的快照替换内存（客户端专用） */
    public static void applyFromSnapshot(Map<String, Long> snapshot) {
        merged = snapshot == null ? new LinkedHashMap<>() : new LinkedHashMap<>(snapshot);
        // 客户端不区分 global/save 层（写入仍走 C2S 包到服务端处理）
        globalLayer = new LinkedHashMap<>();
        saveLayer = new LinkedHashMap<>();
    }
}
