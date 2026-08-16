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

/**
 * 配置持有者与加载/保存器。
 *
 * <p>无 Cloth Config 时直接用 Gson 读写 {@code config/itemalchemy-expansion.json5}。
 * 有 Cloth Config 时，GUI 修改后会调用 {@link #save()} 持久化并 {@link #reload()} 刷新内存。</p>
 */
public final class IAExpConfigHolder {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String FILE_NAME = "itemalchemy-expansion.json5";

    private static IAExpConfig active = new IAExpConfig();

    /** 配置升级标志：load() 中若发生版本升级则置 true，load 完成后据此决定是否写盘 */
    private static boolean needsSave = false;

    /** 旧版本升级标志：本次 load 是否发生了从 < 5 到 5 的升级（用于客户端 toast 提醒，仅内存） */
    private static boolean upgradedFromLegacy = false;

    private IAExpConfigHolder() {}

    /** 本次 load 是否发生了从旧版本到当前版本的升级（供客户端 toast 检测） */
    public static boolean wasUpgradedFromLegacy() {
        return upgradedFromLegacy;
    }

    /** 客户端 toast 显示后清零，避免重复触发 */
    public static void clearUpgradedFromLegacy() {
        upgradedFromLegacy = false;
    }

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
        upgradedFromLegacy = false;
        if (Files.exists(path)) {
            try {
                String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                // json5 兼容：剥离行注释与块注释（简易处理，足够人工编辑）
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
        if (loaded.displayMode == null) loaded.displayMode = def.displayMode;
        if (loaded.shulkerBoxMode == null) loaded.shulkerBoxMode = def.shulkerBoxMode;
        if (loaded.shulkerNoEmcPolicy == null) loaded.shulkerNoEmcPolicy = def.shulkerNoEmcPolicy;
        if (loaded.ignoreNbtKeys == null) loaded.ignoreNbtKeys = new ArrayList<>();

        // configVersion < 7: 旧版本，补齐默认 true 的字段（Gson Unsafe 会把布尔零初始化为 false）
        if (loaded.configVersion < 7) {
            ItemAlchemyExpansion.LOGGER.info("[IAExp] Upgrading config from version {} -> 7", loaded.configVersion);
            loaded.debugLogging = def.debugLogging;
            loaded.fullIgnoreDamageAndRepairCost = def.fullIgnoreDamageAndRepairCost;
            loaded.builtInShulkerPreview = true;
            loaded.searchShulkerContents = def.searchShulkerContents;
            loaded.shulkerMatchRedFrame = def.shulkerMatchRedFrame;
            if (loaded.autoPricingStrategy == null) loaded.autoPricingStrategy = def.autoPricingStrategy;
            loaded.autoPricingRespectUpstream = def.autoPricingRespectUpstream;
            loaded.preciseMode = true;
            loaded.configVersion = 7;
            needsSave = true;
            upgradedFromLegacy = true;
        }

        // configVersion < 8: 重新定价提示流程已重写（旧的全量确认 → 新的逐个选择 UI），
        // 旧标记 autoPricingRepricePromptShown=true 重置为 false 让新 UI 有机会弹出
        if (loaded.configVersion < 8) {
            ItemAlchemyExpansion.LOGGER.info("[IAExp] Upgrading config from version {} -> 8 (reset reprice prompt flag)", loaded.configVersion);
            loaded.autoPricingRepricePromptShown = false;
            loaded.configVersion = 8;
            needsSave = true;
        }

        // configVersion < 9: 自动定价引入「分批 + 时间片」扫描，新增两个性能调参字段。
        // Gson Unsafe 会把 int 零初始化为 0，0 会导致每 tick 不处理任何配方，故补默认值
        if (loaded.configVersion < 9) {
            ItemAlchemyExpansion.LOGGER.info("[IAExp] Upgrading config from version {} -> 9 (add auto-pricing perf tuning)", loaded.configVersion);
            if (loaded.autoPricingBatchSize <= 0) loaded.autoPricingBatchSize = def.autoPricingBatchSize;
            if (loaded.autoPricingTickBudgetMs <= 0) loaded.autoPricingTickBudgetMs = def.autoPricingTickBudgetMs;
            loaded.configVersion = 9;
            needsSave = true;
        }

        // configVersion < 10: applyFromSnapshot bug 修复（之前客户端 S2C 同步会清空 saveLayer，
        // 导致 reprice check 找不到候选→误标 autoPricingRepricePromptShown=true）。
        // 重置标志让修复后的提示有机会弹出
        if (loaded.configVersion < 10) {
            ItemAlchemyExpansion.LOGGER.info("[IAExp] Upgrading config from version {} -> 10 (reset reprice prompt flag after applyFromSnapshot fix)", loaded.configVersion);
            loaded.autoPricingRepricePromptShown = false;
            loaded.configVersion = 10;
            needsSave = true;
        }

        // configVersion < 11: 新增「自动装置」总开关。Gson Unsafe 会把 boolean 零初始化为 false，
        // 需显式补回默认 true
        if (loaded.configVersion < 11) {
            ItemAlchemyExpansion.LOGGER.info("[IAExp] Upgrading config from version {} -> 11 (add automation master switch)", loaded.configVersion);
            loaded.automationEnabled = true;
            loaded.configVersion = 11;
            needsSave = true;
        }

        // configVersion < 12: 新增自动装置工作间隔。Gson Unsafe 会把 int 零初始化为 0，
        // 0 会导致每 tick 取模异常，故 <=0 时补默认值
        if (loaded.configVersion < 12) {
            ItemAlchemyExpansion.LOGGER.info("[IAExp] Upgrading config from version {} -> 12 (add automation interval)", loaded.configVersion);
            if (loaded.automationIntervalTicks <= 0) loaded.automationIntervalTicks = def.automationIntervalTicks;
            loaded.configVersion = 12;
            needsSave = true;
        }

        // configVersion < 13: 新增自动装置工作模式。Gson 缺省时枚举为 null，需补默认持续模式
        if (loaded.configVersion < 13) {
            ItemAlchemyExpansion.LOGGER.info("[IAExp] Upgrading config from version {} -> 13 (add automation mode)", loaded.configVersion);
            if (loaded.automationMode == null) loaded.automationMode = def.automationMode;
            loaded.configVersion = 13;
            needsSave = true;
        }

        return loaded;
    }

    /** 简易 JSON5 注释剥离：去掉行注释与块注释（不处理字符串内的 //，但配置值几乎不会含此） */
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
