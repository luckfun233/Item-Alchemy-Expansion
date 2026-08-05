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
 * 单存档 EMC 覆盖存储：把本存档的 EMC 覆盖持久化到
 * {@code <world>/itemalchemy_expansion_overrides.json}。
 *
 * <p><b>设计目的</b>：玩家在 SetEmcScreen 选择「仅本存档」时，EMC 修改只影响当前存档，
 * 不污染全局 {@code config/itemalchemy/emc_config.json}。每次世界加载时（服务端启动），
 * 通过 {@link #load(MinecraftServer)} 读取本文件并把覆盖应用到 {@link EMCManager} 内存 map。</p>
 *
 * <p><b>与全局配置的关系</b>：
 * <ul>
 *   <li>全局 {@code emc_config.json} 由前置模组在 {@link EMCManager#init} 时加载到内存 map。</li>
 *   <li>本存档 overrides 在全局加载之后应用，相同 itemId 覆盖全局值。</li>
 *   <li>「仅本存档」写本文件；「全局」写 {@code emc_config.json}（见 {@link GlobalEmcStore}）。</li>
 * </ul>
 * </p>
 *
 * <p><b>文件格式</b>：简单的 {@code Map<String, Long>} JSON，如：
 * <pre>{"minecraft:stone": 2, "minecraft:diamond": 8192}</pre></p>
 *
 * <p><b>线程安全</b>：所有方法在服务端主线程调用（由 {@link SetEmcNetwork#applyOnServer}
 * 通过 {@code server.execute} 调度）。</p>
 */
public final class PerSaveEmcStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Type MAP_TYPE = new TypeToken<LinkedHashMap<String, Long>>(){}.getType();
    private static final String FILE_NAME = "itemalchemy_expansion_overrides.json";

    private PerSaveEmcStore() {}

    /** 返回本存档 overrides 文件路径：{@code <world>/itemalchemy_expansion_overrides.json} */
    public static Path getFile(MinecraftServer server) {
        // WorldSavePath.ROOT = 世界存档根目录（如 ./<saves>/<world>/）
        Path worldRoot = server.getSavePath(WorldSavePath.ROOT);
        return worldRoot.resolve(FILE_NAME);
    }

    /**
     * 设置单个物品的本存档 EMC 覆盖：更新内存 map + 写文件。
     *
     * @param server Minecraft 服务端（用于定位存档目录）
     * @param itemId 物品 ID（已规范化，含命名空间）
     * @param emc    新 EMC 值（>= 0）
     */
    public static void set(MinecraftServer server, String itemId, long emc) {
        // 1. 读现有 overrides（不存在则空 map）
        Map<String, Long> overrides = read(server);
        // 2. 更新条目
        overrides.put(itemId, emc);
        // 3. 写回文件
        write(server, overrides);
        ItemAlchemyExpansion.debug("[IAExp] per-save emc override set: {}={} (file={})",
                itemId, emc, getFile(server));
    }

    /**
     * 世界加载时调用：读取本存档 overrides，应用到 {@link EMCManager} 内存 map。
     *
     * <p>在全局 emc_config.json 加载之后调用，相同 itemId 覆盖全局值。
     * 文件不存在或解析失败时静默跳过（无覆盖）。</p>
     */
    public static void load(MinecraftServer server) {
        Map<String, Long> overrides = read(server);
        if (overrides.isEmpty()) {
            ItemAlchemyExpansion.debug("[IAExp] per-save emc overrides: none (file not found or empty)");
            return;
        }
        for (Map.Entry<String, Long> e : overrides.entrySet()) {
            // 直接 set 到内存 map（覆盖全局值）
            EMCManager.set(e.getKey(), e.getValue());
        }
        ItemAlchemyExpansion.debug("[IAExp] per-save emc overrides applied: {} entries", overrides.size());
    }

    /** 读取本存档 overrides 文件；文件不存在或解析失败返回空 map（不抛异常） */
    private static Map<String, Long> read(MinecraftServer server) {
        Path file = getFile(server);
        if (!Files.exists(file)) return new LinkedHashMap<>();
        try {
            String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            Map<String, Long> map = GSON.fromJson(content, MAP_TYPE);
            return map == null ? new LinkedHashMap<>() : map;
        } catch (Throwable t) {
            ItemAlchemyExpansion.LOGGER.warn("[IAExp] Failed to read per-save emc overrides ({}): {}",
                    file, t.toString());
            return new LinkedHashMap<>();
        }
    }

    /** 写入本存档 overrides 文件 */
    private static void write(MinecraftServer server, Map<String, Long> overrides) {
        Path file = getFile(server);
        try {
            Files.createDirectories(file.getParent());
            String header = "// Item Alchemy Expansion 单存档 EMC 覆盖。仅对当前存档生效。\n";
            Files.write(file, (header + GSON.toJson(overrides)).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            ItemAlchemyExpansion.LOGGER.error("[IAExp] Failed to write per-save emc overrides ({}): {}",
                    file, e.getMessage());
        }
    }
}
