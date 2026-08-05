package itemalchemy.expansion.config;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Item Alchemy Expansion 配置模型。
 *
 * <p>序列化为 {@code config/itemalchemy-expansion.json5}（实际是标准 JSON，扩展名仅便于人工编辑）。
 * 无 Cloth Config 时用 Gson 读写；有 Cloth Config 时由 {@code ModMenuIntegration} 渲染 GUI（阶段 6）。</p>
 *
 * <p>所有默认值即用户主需求（见 plan.md §5）。</p>
 */
public class IAExpConfig {

    /** NBT 区分策略 */
    public enum NbtMode {
        @SerializedName("smart") SMART,
        @SerializedName("full") FULL
    }

    /** 转换桌展示方式 */
    public enum DisplayMode {
        @SerializedName("icon_only") ICON_ONLY,
        @SerializedName("name_only") NAME_ONLY,
        @SerializedName("icon_and_name") ICON_AND_NAME
    }

    /** 潜影盒功能开关 */
    public enum ShulkerBoxMode {
        @SerializedName("allow") ALLOW,
        @SerializedName("disable") DISABLE
    }

    /** 潜影盒内含无 EMC 物品时的策略 */
    public enum ShulkerNoEmcPolicy {
        @SerializedName("reject") REJECT,
        @SerializedName("allow_as_zero") ALLOW_AS_ZERO
    }

    /** 配置版本号，用于旧配置自动升级。缺省（旧配置）视为 0，当前为 3。 */
    public int configVersion = 3;

    /** NBT 区分策略，默认 FULL（全 NBT 区分，最大兼容性，任意模组的不同 NBT 物品都能区分） */
    public NbtMode nbtMode = NbtMode.FULL;

    /** 转换桌展示方式，默认 图标+名称 */
    public DisplayMode displayMode = DisplayMode.ICON_AND_NAME;

    /** 潜影盒功能，默认开启 */
    public ShulkerBoxMode shulkerBoxMode = ShulkerBoxMode.ALLOW;

    /** 潜影盒含无 EMC 物品时默认禁止放入并提示 */
    public ShulkerNoEmcPolicy shulkerNoEmcPolicy = ShulkerNoEmcPolicy.REJECT;

    /** 额外纳入智能匹配的 NBT key（在内置规则之外追加） */
    public List<String> smartNbtKeys = new ArrayList<>();

    /** 强制忽略的 NBT key（即使内置规则纳入也会被排除） */
    public List<String> ignoreNbtKeys = new ArrayList<>();

    /**
     * 按物品 namespace 自定义重要 NBT key。
     * key = namespace（如 "tacz"），value = 该 namespace 下需纳入的 NBT key 列表。
     */
    public Map<String, List<String>> perModRules = new HashMap<>();

    /** 搜索时是否匹配翻译名（默认 true） */
    public boolean searchByTranslatedName = true;

    /** 转换桌里展示物品名称小字（仅 DisplayMode=ICON_AND_NAME 时生效，控制是否在槽位下渲染名称缩写） */
    public boolean renderNameUnderSlot = false;

    /** 是否在未安装 ShulkerBoxTooltip 时启用内置 Shift 预览（默认 true） */
    public boolean builtInShulkerPreview = true;

    /**
     * 调试日志开关（默认 false）。
     * <p>开启后输出变体键生成、提取槽重建、注册/移除等详细 INFO 日志，便于排查兼容性问题；
     * 关闭时仅输出 warn/error。配置页可切换。</p>
     */
    public boolean debugLogging = false;

    /**
     * FULL 模式下是否忽略 Damage 与 RepairCost（默认 true）。
     * <p>FULL 模式会保留全部 NBT，但工具/武器的 Damage（耐久）和 RepairCost（修复花费）
     * 会让同一工具因耐久不同产生大量变体。默认忽略这两个 key 以避免变体爆炸；
     * 想要严格全 NBT 区分时设为 false。{@link #ignoreNbtKeys} 中的 key 始终被忽略（两模式通用）。</p>
     */
    public boolean fullIgnoreDamageAndRepairCost = true;

    /** 返回一份带默认内置规则副本的配置（不修改本对象） */
    public IAExpConfig copy() {
        IAExpConfig c = new IAExpConfig();
        c.nbtMode = nbtMode;
        c.displayMode = displayMode;
        c.shulkerBoxMode = shulkerBoxMode;
        c.shulkerNoEmcPolicy = shulkerNoEmcPolicy;
        c.smartNbtKeys = new ArrayList<>(smartNbtKeys);
        c.ignoreNbtKeys = new ArrayList<>(ignoreNbtKeys);
        c.perModRules = new HashMap<>();
        for (Map.Entry<String, List<String>> e : perModRules.entrySet()) {
            c.perModRules.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        c.searchByTranslatedName = searchByTranslatedName;
        c.renderNameUnderSlot = renderNameUnderSlot;
        c.builtInShulkerPreview = builtInShulkerPreview;
        c.debugLogging = debugLogging;
        c.fullIgnoreDamageAndRepairCost = fullIgnoreDamageAndRepairCost;
        return c;
    }
}
