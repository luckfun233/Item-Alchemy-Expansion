package itemalchemy.expansion.compat.clothconfig;

import itemalchemy.expansion.client.EmcAutoClientNetwork;
import itemalchemy.expansion.client.RepriceTriggerClient;
import itemalchemy.expansion.config.IAExpConfig;
import itemalchemy.expansion.config.IAExpConfigHolder;
import itemalchemy.expansion.IAExpServices;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.impl.builders.EnumSelectorBuilder;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Cloth Config GUI 构建器：把 {@link IAExpConfig} 的字段渲染为可编辑界面。
 *
 * <p>设计目标：<b>可扩展</b>。新增配置项时只需在 {@link #buildCategories} 里追加一个
 * {@code addEntry(...)} 调用。每个条目自带默认值（用于「重置」按钮）与 saveConsumer（写入待保存副本）。</p>
 *
 * <p>本类仅在已安装 cloth-config 时被 {@code ModMenuIntegration} 反射/直接调用，
 * 不会在无 cloth-config 的环境被加载（避免 NoClassDefFoundError）。</p>
 *
 * <p>编辑流程：进入界面时复制一份当前配置 → 各条目 saveConsumer 写入副本 →
 * 点击「完成」时 setSavingRunnable 把副本写回 {@link IAExpConfigHolder} 的 active 并落盘 +
 * {@link IAExpServices#refresh()} 刷新指纹器等服务。</p>
 *
 * <p>枚举显示名通过 {@link EnumSelectorBuilder#setEnumNamingProvider} 把枚举常量
 * 映射为本地化文本，避免直接暴露字段名给玩家。</p>
 */
public final class IAExpClothConfigScreen {

    private IAExpClothConfigScreen() {}

    /**
     * 构建配置界面。
     *
     * @param parent 返回到此父界面（通常为 ModMenu 列表）
     * @return Cloth Config {@link Screen}
     */
    public static Screen create(Screen parent) {
        IAExpConfig current = IAExpConfigHolder.get();
        // 编辑副本：所有 saveConsumer 写入这里，避免取消时污染 active
        IAExpConfig editing = current.copy();

        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Text.translatable("itemalchemy-expansion.config.title"));

        // 用 cloth-config 默认背景（深色半透 + 渲染游戏世界）。
        // 之前用 setTransparentBackground(true) 会让配置页背景透明，但父界面
        // （ModMenu 列表）在 Screen 切换后已停止渲染，导致配置页后方只剩下
        // 游戏世界或上一帧残影，看起来「模糊不清后方的内容」。
        ConfigEntryBuilder entries = builder.entryBuilder();

        buildCategories(builder, entries, editing);

        // 保存：写回 active + 落盘 + 刷新运行时服务
        builder.setSavingRunnable(() -> {
            boolean wasAutoPricingOn = IAExpConfigHolder.get().autoPricingFromRecipes;

            IAExpConfigHolder.get().displayMode = editing.displayMode;
            IAExpConfigHolder.get().shulkerBoxMode = editing.shulkerBoxMode;
            IAExpConfigHolder.get().shulkerNoEmcPolicy = editing.shulkerNoEmcPolicy;
            IAExpConfigHolder.get().ignoreNbtKeys = editing.ignoreNbtKeys;
            IAExpConfigHolder.get().searchByTranslatedName = editing.searchByTranslatedName;
            IAExpConfigHolder.get().renderNameUnderSlot = editing.renderNameUnderSlot;
            IAExpConfigHolder.get().builtInShulkerPreview = editing.builtInShulkerPreview;
            IAExpConfigHolder.get().debugLogging = editing.debugLogging;
            IAExpConfigHolder.get().fullIgnoreDamageAndRepairCost = editing.fullIgnoreDamageAndRepairCost;
            IAExpConfigHolder.get().autoPricingStrategy = editing.autoPricingStrategy;
            IAExpConfigHolder.get().autoPricingRespectUpstream = editing.autoPricingRespectUpstream;
            IAExpConfigHolder.get().autoPricingBatchSize = editing.autoPricingBatchSize;
            IAExpConfigHolder.get().autoPricingTickBudgetMs = editing.autoPricingTickBudgetMs;
            // 精确模式始终开启
            IAExpConfigHolder.get().preciseMode = true;
            IAExpConfigHolder.get().autoPricingFromRecipes = editing.autoPricingFromRecipes;
            IAExpConfigHolder.get().automationEnabled = editing.automationEnabled;
            IAExpConfigHolder.get().automationIntervalTicks = editing.automationIntervalTicks;
            IAExpConfigHolder.get().automationMode = editing.automationMode;
            IAExpConfigHolder.save();
            IAExpServices.refresh();

            // 自动装置开关变化可能影响合成配方：通知服务端即时增删配方，无需重启
            EmcAutoClientNetwork.sendConfigSync();

            // 自动定价 OFF→ON：重置「已弹过」标志并弹重新定价对话框
            // 每次从关闭切回开启都可能覆盖新的手动定价条目，必须每次都弹
            if (!wasAutoPricingOn && editing.autoPricingFromRecipes) {
                IAExpConfigHolder.get().autoPricingRepricePromptShown = false;
                RepriceTriggerClient.sendRepriceCheck();
            }
        });

        return builder.build();
    }

    // ============ 枚举名映射工具 ============
    // Cloth Config 11.1.136 的 EnumSelectorBuilder.setEnumNameProvider 接收
    // Function<Enum, Text>（注意是原始 Enum 类型，不是泛型 T），因此这里用 Function<Enum, Text>。

    /** DisplayMode 直观名 */
    private static final Function<Enum, Text> DISPLAY_MODE_NAMING = e ->
            Text.translatable("itemalchemy-expansion.config.displayMode.option." + e.name().toLowerCase());

    /** ShulkerBoxMode 直观名：ALLOW→「开启」, DISABLE→「关闭」 */
    private static final Function<Enum, Text> SHULKER_MODE_NAMING = e ->
            Text.translatable("itemalchemy-expansion.config.shulkerBoxMode.option." + e.name().toLowerCase());

    /** ShulkerNoEmcPolicy 直观名：REJECT→「拒绝放入」, ALLOW_AS_ZERO→「按 0 计并允许」 */
    private static final Function<Enum, Text> SHULKER_POLICY_NAMING = e ->
            Text.translatable("itemalchemy-expansion.config.shulkerNoEmcPolicy.option." + e.name().toLowerCase());

    /** AutoPricingStrategy 直观名：MIN→「最小值」, MAX→「最大值」, AVG→「平均值」, FIRST→「首个」 */
    private static final Function<Enum, Text> AUTO_PRICING_STRATEGY_NAMING = e ->
            Text.translatable("itemalchemy-expansion.config.autoPricingStrategy.option." + e.name().toLowerCase());

    /** AutomationMode 直观名：CONTINUOUS→「持续」, PULSE→「脉冲」 */
    private static final Function<Enum, Text> AUTOMATION_MODE_NAMING = e ->
            Text.translatable("itemalchemy-expansion.config.automationMode.option." + e.name().toLowerCase());

    /**
     * 注册所有分类与条目。新增配置项时在此追加即可。
     *
     * <p>分类组织：
     * <ul>
     *   <li>general — 展示、搜索</li>
     *   <li>shulker_box — 潜影盒相关</li>
     *   <li>advanced — 调试日志、NBT 过滤、忽略 key 列表</li>
     *   <li>experimental — 实验性功能（配方自动定价，默认关闭）</li>
     * </ul>
     * </p>
     */
    private static void buildCategories(ConfigBuilder builder, ConfigEntryBuilder entries, IAExpConfig c) {
        // ===== General =====
        ConfigCategory general = builder.getOrCreateCategory(
                Text.translatable("itemalchemy-expansion.config.category.general"));

        general.addEntry(entries
                .startEnumSelector(Text.translatable("itemalchemy-expansion.config.displayMode"),
                        IAExpConfig.DisplayMode.class, c.displayMode)
                .setDefaultValue(IAExpConfig.DisplayMode.ICON_AND_NAME)
                .setTooltip(Text.translatable("itemalchemy-expansion.config.displayMode.tooltip"))
                .setEnumNameProvider(DISPLAY_MODE_NAMING)
                .setSaveConsumer(v -> c.displayMode = v)
                .build());

        general.addEntry(entries
                .startBooleanToggle(Text.translatable("itemalchemy-expansion.config.searchByTranslatedName"),
                        c.searchByTranslatedName)
                .setDefaultValue(true)
                .setTooltip(Text.translatable("itemalchemy-expansion.config.searchByTranslatedName.tooltip"))
                .setSaveConsumer(v -> c.searchByTranslatedName = v)
                .build());

        general.addEntry(entries
                .startBooleanToggle(Text.translatable("itemalchemy-expansion.config.renderNameUnderSlot"),
                        c.renderNameUnderSlot)
                .setDefaultValue(false)
                .setTooltip(Text.translatable("itemalchemy-expansion.config.renderNameUnderSlot.tooltip"))
                .setSaveConsumer(v -> c.renderNameUnderSlot = v)
                .build());

        // ===== Shulker Box =====
        ConfigCategory shulker = builder.getOrCreateCategory(
                Text.translatable("itemalchemy-expansion.config.category.shulker_box"));

        shulker.addEntry(entries
                .startEnumSelector(Text.translatable("itemalchemy-expansion.config.shulkerBoxMode"),
                        IAExpConfig.ShulkerBoxMode.class, c.shulkerBoxMode)
                .setDefaultValue(IAExpConfig.ShulkerBoxMode.ALLOW)
                .setTooltip(Text.translatable("itemalchemy-expansion.config.shulkerBoxMode.tooltip"))
                .setEnumNameProvider(SHULKER_MODE_NAMING)
                .setSaveConsumer(v -> c.shulkerBoxMode = v)
                .build());

        shulker.addEntry(entries
                .startEnumSelector(Text.translatable("itemalchemy-expansion.config.shulkerNoEmcPolicy"),
                        IAExpConfig.ShulkerNoEmcPolicy.class, c.shulkerNoEmcPolicy)
                .setDefaultValue(IAExpConfig.ShulkerNoEmcPolicy.REJECT)
                .setTooltip(Text.translatable("itemalchemy-expansion.config.shulkerNoEmcPolicy.tooltip"))
                .setEnumNameProvider(SHULKER_POLICY_NAMING)
                .setSaveConsumer(v -> c.shulkerNoEmcPolicy = v)
                .build());

        shulker.addEntry(entries
                .startBooleanToggle(Text.translatable("itemalchemy-expansion.config.builtInShulkerPreview"),
                        c.builtInShulkerPreview)
                .setDefaultValue(true)
                .setTooltip(Text.translatable("itemalchemy-expansion.config.builtInShulkerPreview.tooltip"))
                .setSaveConsumer(v -> c.builtInShulkerPreview = v)
                .build());

        // ===== Advanced =====
        ConfigCategory advanced = builder.getOrCreateCategory(
                Text.translatable("itemalchemy-expansion.config.category.advanced"));

        advanced.addEntry(entries
                .startBooleanToggle(Text.translatable("itemalchemy-expansion.config.debugLogging"),
                        c.debugLogging)
                .setDefaultValue(false)
                .setTooltip(Text.translatable("itemalchemy-expansion.config.debugLogging.tooltip"))
                .setSaveConsumer(v -> c.debugLogging = v)
                .build());

        advanced.addEntry(entries
                .startBooleanToggle(Text.translatable("itemalchemy-expansion.config.fullIgnoreDamageAndRepairCost"),
                        c.fullIgnoreDamageAndRepairCost)
                .setDefaultValue(true)
                .setTooltip(Text.translatable("itemalchemy-expansion.config.fullIgnoreDamageAndRepairCost.tooltip"))
                .setSaveConsumer(v -> c.fullIgnoreDamageAndRepairCost = v)
                .build());

        advanced.addEntry(entries
                .startStrList(Text.translatable("itemalchemy-expansion.config.ignoreNbtKeys"),
                        new ArrayList<>(c.ignoreNbtKeys))
                .setDefaultValue(new ArrayList<>())
                .setTooltip(Text.translatable("itemalchemy-expansion.config.ignoreNbtKeys.tooltip"))
                .setSaveConsumer((List<String> v) -> c.ignoreNbtKeys = v)
                .build());

        // ===== Automation（自动装置）=====
        ConfigCategory automation = builder.getOrCreateCategory(
                Text.translatable("itemalchemy-expansion.config.category.automation"));

        automation.addEntry(entries
                .startBooleanToggle(Text.translatable("itemalchemy-expansion.config.automationEnabled"),
                        c.automationEnabled)
                .setDefaultValue(true)
                .setTooltip(Text.translatable("itemalchemy-expansion.config.automationEnabled.tooltip"))
                .setSaveConsumer(v -> c.automationEnabled = v)
                .build());

        automation.addEntry(entries
                .startIntField(Text.translatable("itemalchemy-expansion.config.automationIntervalTicks"),
                        c.automationIntervalTicks)
                .setDefaultValue(5)
                .setTooltip(Text.translatable("itemalchemy-expansion.config.automationIntervalTicks.tooltip"))
                .setSaveConsumer(v -> c.automationIntervalTicks = Math.max(1, v))
                .build());

        automation.addEntry(entries
                .startEnumSelector(Text.translatable("itemalchemy-expansion.config.automationMode"),
                        IAExpConfig.AutomationMode.class, c.automationMode)
                .setDefaultValue(IAExpConfig.AutomationMode.CONTINUOUS)
                .setTooltip(Text.translatable("itemalchemy-expansion.config.automationMode.tooltip"))
                .setEnumNameProvider(AUTOMATION_MODE_NAMING)
                .setSaveConsumer(v -> c.automationMode = v)
                .build());

        // ===== Experimental =====
        ConfigCategory experimental = builder.getOrCreateCategory(
                Text.translatable("itemalchemy-expansion.config.category.experimental"));

        experimental.addEntry(entries
                .startTextDescription(Text.translatable("itemalchemy-expansion.config.experimental.note"))
                .build());

        experimental.addEntry(entries
                .startBooleanToggle(Text.translatable("itemalchemy-expansion.config.autoPricingFromRecipes"),
                        c.autoPricingFromRecipes)
                .setDefaultValue(false)
                .setTooltip(Text.translatable("itemalchemy-expansion.config.autoPricingFromRecipes.tooltip"))
                .setSaveConsumer(v -> c.autoPricingFromRecipes = v)
                .build());

        experimental.addEntry(entries
                .startEnumSelector(Text.translatable("itemalchemy-expansion.config.autoPricingStrategy"),
                        IAExpConfig.AutoPricingStrategy.class, c.autoPricingStrategy)
                .setDefaultValue(IAExpConfig.AutoPricingStrategy.MIN)
                .setTooltip(Text.translatable("itemalchemy-expansion.config.autoPricingStrategy.tooltip"))
                .setEnumNameProvider(AUTO_PRICING_STRATEGY_NAMING)
                .setSaveConsumer(v -> c.autoPricingStrategy = v)
                .build());

        experimental.addEntry(entries
                .startBooleanToggle(Text.translatable("itemalchemy-expansion.config.autoPricingRespectUpstream"),
                        c.autoPricingRespectUpstream)
                .setDefaultValue(true)
                .setTooltip(Text.translatable("itemalchemy-expansion.config.autoPricingRespectUpstream.tooltip"))
                .setSaveConsumer(v -> c.autoPricingRespectUpstream = v)
                .build());

        experimental.addEntry(entries
                .startIntField(Text.translatable("itemalchemy-expansion.config.autoPricingBatchSize"),
                        c.autoPricingBatchSize)
                .setDefaultValue(256)
                .setTooltip(Text.translatable("itemalchemy-expansion.config.autoPricingBatchSize.tooltip"))
                .setSaveConsumer(v -> c.autoPricingBatchSize = v)
                .build());

        experimental.addEntry(entries
                .startIntField(Text.translatable("itemalchemy-expansion.config.autoPricingTickBudgetMs"),
                        c.autoPricingTickBudgetMs)
                .setDefaultValue(8)
                .setTooltip(Text.translatable("itemalchemy-expansion.config.autoPricingTickBudgetMs.tooltip"))
                .setSaveConsumer(v -> c.autoPricingTickBudgetMs = v)
                .build());
    }
}
