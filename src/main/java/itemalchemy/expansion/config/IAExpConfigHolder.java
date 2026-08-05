package itemalchemy.expansion.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import itemalchemy.expansion.ItemAlchemyExpansion;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * 配置持有者与加载/保存器。
 *
 * <p>无 Cloth Config 时的回退路径：直接用 Gson 读写 {@code config/itemalchemy-expansion.json5}。
 * 有 Cloth Config 时，GUI 修改后会调用 {@link #save()} 持久化，并 {@link #reload()} 刷新内存。</p>
 */
public final class IAExpConfigHolder {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String FILE_NAME = "itemalchemy-expansion.json5";

    private static IAExpConfig active = new IAExpConfig();

    /** 配置升级标志：load() 中若发生版本升级则置 true，load 完成后据此决定是否写盘 */
    private static boolean needsSave = false;

    private IAExpConfigHolder() {}

    /** 获取当前生效的配置（只读视图，修改后需调用 {@link #save()}） */
    public static IAExpConfig get() {
        return active;
    }

    /** 配置文件路径：&lt;game&gt;/config/itemalchemy-expansion.json5 */
    public static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    /** 从磁盘加载；文件不存在或解析失败时写回默认配置 */
    public static void load() {
        Path path = configPath();
        needsSave = false;
        if (Files.exists(path)) {
            try {
                String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                // json5 兼容：剥离行注释 // 与块注释 /* */（简易处理，足够人工编辑）
                content = stripJson5Comments(content);
                IAExpConfig loaded = GSON.fromJson(content, IAExpConfig.class);
                if (loaded != null) {
                    active = mergeWithDefaults(loaded);
                }
            } catch (Exception e) {
                ItemAlchemyExpansion.LOGGER.warn("[IAExp] Failed to read config, using defaults: " + e.getMessage());
                active = new IAExpConfig();
                save();
            }
        } else {
            active = new IAExpConfig();
            save();
        }
        // 配置版本升级后写盘，让用户看到新配置
        if (needsSave) {
            save();
            needsSave = false;
        }
    }

    /** 保存当前配置到磁盘 */
    public static void save() {
        Path path = configPath();
        try {
            Files.createDirectories(path.getParent());
            String header = "// Item Alchemy Expansion 配置。JSON5 语法（支持 // 注释）。修改后 /itemalchemy reload 或重启生效。\n";
            Files.write(path, (header + GSON.toJson(active)).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            ItemAlchemyExpansion.LOGGER.error("[IAExp] Failed to save config: " + e.getMessage());
        }
    }

    /** 重新从磁盘加载（用于 reload 命令） */
    public static void reload() {
        load();
    }

    /** 用默认值补齐加载后可能缺失的字段（向前兼容），并执行配置版本升级 */
    private static IAExpConfig mergeWithDefaults(IAExpConfig loaded) {
        IAExpConfig def = new IAExpConfig();
        if (loaded.nbtMode == null) loaded.nbtMode = def.nbtMode;
        if (loaded.displayMode == null) loaded.displayMode = def.displayMode;
        if (loaded.shulkerBoxMode == null) loaded.shulkerBoxMode = def.shulkerBoxMode;
        if (loaded.shulkerNoEmcPolicy == null) loaded.shulkerNoEmcPolicy = def.shulkerNoEmcPolicy;
        if (loaded.smartNbtKeys == null) loaded.smartNbtKeys = new ArrayList<>();
        if (loaded.ignoreNbtKeys == null) loaded.ignoreNbtKeys = new ArrayList<>();
        if (loaded.perModRules == null) loaded.perModRules = new HashMap<>();

        // 配置版本升级：configVersion 0 = 旧配置（默认 SMART），升级为 FULL 并保存
        if (loaded.configVersion < 1) {
            ItemAlchemyExpansion.LOGGER.info("[IAExp] Upgrading config from version {} -> 1: nbtMode SMART -> FULL", loaded.configVersion);
            loaded.nbtMode = IAExpConfig.NbtMode.FULL;
            loaded.configVersion = 1;
            // 标记需要保存（在 load() 中检测此标志后写盘）
            needsSave = true;
        }

        // configVersion 1 -> 2: 新增 debugLogging / fullIgnoreDamageAndRepairCost 字段
        // Gson 用 Unsafe 分配对象时布尔原始类型默认为 false，需把默认 true 的字段补回
        if (loaded.configVersion < 2) {
            ItemAlchemyExpansion.LOGGER.info("[IAExp] Upgrading config from version {} -> 2: add debugLogging / fullIgnoreDamageAndRepairCost", loaded.configVersion);
            loaded.debugLogging = def.debugLogging;
            loaded.fullIgnoreDamageAndRepairCost = def.fullIgnoreDamageAndRepairCost;
            loaded.configVersion = 2;
            needsSave = true;
        }

        // configVersion 2 -> 3: 强制默认 全量模式 + 开启内置预览
        // 旧配置可能因 Gson Unsafe 分配导致 builtInShulkerPreview=false，升级时补回默认值
        if (loaded.configVersion < 3) {
            ItemAlchemyExpansion.LOGGER.info("[IAExp] Upgrading config from version {} -> 3: force nbtMode=FULL, builtInShulkerPreview=true", loaded.configVersion);
            loaded.nbtMode = IAExpConfig.NbtMode.FULL;
            loaded.builtInShulkerPreview = true;
            loaded.configVersion = 3;
            needsSave = true;
        }

        return loaded;
    }

    // 需要的 import 已在顶部

    /** 简易 JSON5 注释剥离：去掉 // 行注释与 /* 块注释（不处理字符串内的 //，但配置值几乎不会含此） */
    private static String stripJson5Comments(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            char next = (i + 1 < n) ? s.charAt(i + 1) : '\0';
            if (c == '/' && next == '/') {
                i += 2;
                while (i < n && s.charAt(i) != '\n' && s.charAt(i) != '\r') i++;
            } else if (c == '/' && next == '*') {
                i += 2;
                while (i < n && !(s.charAt(i) == '*' && i + 1 < n && s.charAt(i + 1) == '/')) i++;
                i += 2;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
