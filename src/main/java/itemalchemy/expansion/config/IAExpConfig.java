package itemalchemy.expansion.config;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

/**
 * Item Alchemy Expansion 配置模型。
 *
 * <p>序列化为 {@code config/itemalchemy-expansion.json5}（实际是标准 JSON，扩展名仅便于人工编辑）。
 * 无 Cloth Config 时用 Gson 读写；有 Cloth Config 时由 {@code ModMenuIntegration} 渲染 GUI。</p>
 */
public class IAExpConfig {

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

    /** 多条配方产出相同变体时的取值策略（实验性功能） */
    public enum AutoPricingStrategy {
        @SerializedName("min") MIN,
        @SerializedName("max") MAX,
        @SerializedName("avg") AVG,
        @SerializedName("first") FIRST
    }

    /** 配置版本号，用于旧配置自动升级。缺省（旧配置）视为 0，当前为 9。 */
    public int configVersion = 9;

    /** 转换桌展示方式，默认 图标+名称 */
    public DisplayMode displayMode = DisplayMode.ICON_AND_NAME;

    /** 潜影盒功能，默认开启 */
    public ShulkerBoxMode shulkerBoxMode = ShulkerBoxMode.ALLOW;

    /** 潜影盒含无 EMC 物品时默认禁止放入并提示 */
    public ShulkerNoEmcPolicy shulkerNoEmcPolicy = ShulkerNoEmcPolicy.REJECT;

    /**
     * 搜索时是否同时检索潜影盒内部内容物（默认 true）。
     * 开启后搜索词会匹配潜影盒内每个内容物的 id/翻译名/显示名，匹配的潜影盒会出现在搜索结果中
     * （排在直接物品之后），并在槽位右下角显示第一个匹配物的小标志。
     */
    public boolean searchShulkerContents = true;

    /**
     * Shift 预览时是否对匹配搜索词的内容物格子画红框标记（默认 true）。
     * 焦点白框优先级高于红框（同一格既是焦点又匹配时只显示白框）。
     */
    public boolean shulkerMatchRedFrame = true;

    /** 强制忽略的 NBT key（从指纹中排除，避免运行时状态变化导致指纹失配） */
    public List<String> ignoreNbtKeys = new ArrayList<>();

    /** 搜索时是否匹配翻译名（默认 true） */
    public boolean searchByTranslatedName = true;

    /** 转换桌里展示物品名称小字（仅 DisplayMode=ICON_AND_NAME 时生效，控制是否在槽位下渲染名称缩写） */
    public boolean renderNameUnderSlot = false;

    /** 是否在未安装 ShulkerBoxTooltip 时启用内置 Shift 预览（默认 true） */
    public boolean builtInShulkerPreview = true;

    /**
     * 调试日志开关（默认 false）。开启后输出变体键生成、提取槽重建、注册/移除等详细 INFO 日志，
     * 便于排查兼容性问题；关闭时仅输出 warn/error。
     */
    public boolean debugLogging = false;

    /**
     * 是否忽略 Damage 与 RepairCost（默认 true）。
     * 工具/武器的 Damage（耐久）和 RepairCost（修复花费）会让同一工具因耐久不同产生大量变体，
     * 默认忽略以避免变体爆炸；想要严格全 NBT 区分时设为 false。
     */
    public boolean fullIgnoreDamageAndRepairCost = true;

    // ===== 实验性功能（默认全部关闭，行为与旧版完全一致）=====

    /**
     * 精确模式（默认 true）。
     * EMC 按变体键（itemId + NBT 指纹）查询：同 ID 不同 NBT 的物品可各自有价。
     * 已从 GUI 移除开关——自动定价需要它，手动定价在 SetEmcScreen 中可选精确/通用，
     * 全局精确模式始终开启不影响「通用」手动定价（查询时精确层 miss 自然落到通用层）。
     */
    public boolean preciseMode = true;

    /**
     * 配方自动定价总开关（实验性，默认 false）。
     * 开启后扫描所有注册到原版 RecipeManager 的配方（含模组自定义类型），
     * 对未定义 EMC 的物品按材料 EMC 之和定价。建议同时开启 {@link #preciseMode}，
     * 否则同 ID 不同 NBT 的物品（如不同 AmmoId 的子弹）无法各自定价。
     * GUI 保存时若 preciseMode 仍为 false，会自动置 true。
     * 优先级：手动（精确/通用）> 自动（精确/通用）；上游已定义值不被覆盖。
     */
    public boolean autoPricingFromRecipes = false;

    /**
     * 多条配方产出相同变体时的取值策略（实验性，默认 MIN）。
     * MIN 取最便宜配方（推荐，防套利）；MAX 取最贵；AVG 平均；FIRST 第一个找到。
     * 当前实现仅 MIN，其余预留枚举位。
     */
    public AutoPricingStrategy autoPricingStrategy = AutoPricingStrategy.MIN;

    /**
     * 自动定价是否跳过上游 defaultEMCMap 已定义的物品（实验性，默认 true）。
     * 开启后原版/上游默认值不被自动定价覆盖。强烈不建议关闭。
     */
    public boolean autoPricingRespectUpstream = true;

    /**
     * 自动定价每个服务器 tick 处理的最大配方数（实验性，默认 256）。
     * 自动定价采用「分批 + 时间片」扫描：每 tick 最多处理这么多条配方，
     * 同时受 {@link #autoPricingTickBudgetMs} 时间预算限制（取两者先达上限），
     * 这样即使 300+ 模组、数万条配方也不会阻塞主线程。配方越多可适当调大；TPS 紧张时调小。
     */
    public int autoPricingBatchSize = 256;

    /**
     * 自动定价每 tick 的时间预算（毫秒，实验性，默认 8）。
     * 每 tick 处理配方时一旦耗时超过此值就提前让出主线程，保护 TPS。
     * 调大可加快首次定价，调小更保护 TPS。
     */
    public int autoPricingTickBudgetMs = 8;

    /**
     * 「重新定价」对话框是否已弹过（默认 false）。
     * 仅在 autoPricingFromRecipes 首次从 OFF→ON 时弹一次；弹过置 true 写盘。
     * 想再次触发可用命令 {@code /itemalchemy-expansion reprice}。
     */
    public boolean autoPricingRepricePromptShown = false;

    /**
     * 新功能提醒是否已显示（默认 false）。
     * 检测到 configVersion 从旧版升级时进世界弹一次 toast，弹过置 true 写盘。
     */
    public boolean featureNoticeShown = false;

    /** 返回一份副本（不修改本对象） */
    public IAExpConfig copy() {
        IAExpConfig c = new IAExpConfig();
        c.displayMode = displayMode;
        c.shulkerBoxMode = shulkerBoxMode;
        c.shulkerNoEmcPolicy = shulkerNoEmcPolicy;
        c.ignoreNbtKeys = new ArrayList<>(ignoreNbtKeys);
        c.searchByTranslatedName = searchByTranslatedName;
        c.renderNameUnderSlot = renderNameUnderSlot;
        c.builtInShulkerPreview = builtInShulkerPreview;
        c.debugLogging = debugLogging;
        c.fullIgnoreDamageAndRepairCost = fullIgnoreDamageAndRepairCost;
        c.searchShulkerContents = searchShulkerContents;
        c.shulkerMatchRedFrame = shulkerMatchRedFrame;
        c.preciseMode = preciseMode;
        c.autoPricingFromRecipes = autoPricingFromRecipes;
        c.autoPricingStrategy = autoPricingStrategy;
        c.autoPricingRespectUpstream = autoPricingRespectUpstream;
        c.autoPricingBatchSize = autoPricingBatchSize;
        c.autoPricingTickBudgetMs = autoPricingTickBudgetMs;
        c.autoPricingRepricePromptShown = autoPricingRepricePromptShown;
        c.featureNoticeShown = featureNoticeShown;
        return c;
    }
}
