package itemalchemy.expansion.compat.clothconfig;

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
 * {@code addEntry(...)} 调用，无需改动其它基础设施。每个条目自带默认值（用于「重置」按钮）
 * 与 saveConsumer（写入待保存副本）。</p>
 *
 * <p>本类仅在已安装 cloth-config 时被 {@code ModMenuIntegration} 反射/直接调用，
 * 不会在无 cloth-config 的环境被加载（避免 NoClassDefFoundError）。</p>
 *
 * <p>编辑流程：进入界面时复制一份当前配置 → 各条目 saveConsumer 写入副本 →
 * 点击「完成」时 setSavingRunnable 把副本写回 {@link IAExpConfigHolder} 的 active 并落盘 +
 * {@link IAExpServices#refresh()} 刷新指纹器等服务。</p>
 *
 * <p><b>枚举显示名</b>：用 {@link EnumSelectorBuilder#setEnumNamingProvider} 把
 * 枚举常量（如 {@code ALLOW}/{@code REJECT}）映射为直观的本地化文本（如「开启」「拒绝」），
 * 避免直接暴露字段名给玩家。</p>
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

        // 透明背景，让 modmenu 背景透出（视觉更统一）
        builder.setTransparentBackground(true);

        ConfigEntryBuilder entries = builder.entryBuilder();

        buildCategories(builder, entries, editing);

        // 保存：写回 active + 落盘 + 刷新运行时服务
        builder.setSavingRunnable(() -> {
            boolean wasAutoPricingOn = IAExpConfigHolder.get().autoPricingFromRecipes;
            boolean wasPreciseMode = IAExpConfigHolder.get().preciseMode;

            IAExpConfigHolder.get().nbtMode = editing.nbtMode;
            IAExpConfigHolder.get().displayMode = editing.displayMode;
            IAExpConfigHolder.get().shulkerBoxMode = editing.shulkerBoxMode;
            IAExpConfigHolder.get().shulkerNoEmcPolicy = editing.shulkerNoEmcPolicy;
            IAExpConfigHolder.get().smartNbtKeys = editing.smartNbtKeys;
            IAExpConfigHolder.get().ignoreNbtKeys = editing.ignoreNbtKeys;
            IAExpConfigHolder.get().searchByTranslatedName = editing.searchByTranslatedName;
            IAExpConfigHolder.get().renderNameUnderSlot = editing.renderNameUnderSlot;
            IAExpConfigHolder.get().builtInShulkerPreview = editing.builtInShulkerPreview;
            IAExpConfigHolder.get().debugLogging = editing.debugLogging;
            IAExpConfigHolder.get().fullIgnoreDamageAndRepairCost = editing.fullIgnoreDamageAndRepairCost;
            IAExpConfigHolder.get().autoPricingStrategy = editing.autoPricingStrategy;
            IAExpConfigHolder.get().autoPricingRespectUpstream = editing.autoPricingRespectUpstream;
            // 精确模式与自动定价单独处理（涉及联动与对话框）
            IAExpConfigHolder.get().preciseMode = editing.preciseMode;
            IAExpConfigHolder.get().autoPricingFromRecipes = editing.autoPricingFromRecipes;
            // perModRules 暂不在 GUI 编辑（Map 结构复杂），保留 active 中的值
            IAExpConfigHolder.save();
            IAExpServices.refresh();

            // 自动定价首次 OFF→ON：自动开启精确模式 + 弹重新定价对话框
            if (!wasAutoPricingOn && editing.autoPricingFromRecipes) {
                if (!editing.preciseMode) {
                    // 自动定价需要精确模式才能区分 NBT 物品
                    IAExpConfigHolder.get().preciseMode = true;
                    IAExpConfigHolder.save();
                    IAExpServices.refresh();
                }
                // 触发重新定价检查（客户端发起 C2S，服务端扫描 PerSaveEmcStore 候选并回 S2C 弹窗）
                RepriceTriggerClient.sendRepriceCheck();
            }
        });

        return builder.build();
    }

    // ============ 枚举名映射工具 ============
    // Cloth Config 11.1.136 的 EnumSelectorBuilder.setEnumNameProvider 接收
    // Function<Enum, Text>（注意是原始 Enum 类型，不是泛型 T），因此这里用 Function<Enum, Text>。

    /** NbtMode 直观名：SMART→「智能（兼容性差）」, FULL→「全量（推荐）」 */
    private static final Function<Enum, Text> NBT_MODE_NAMING = e ->
            Text.translatable("itemalchemy-expansion.config.nbtMode.option." + e.name().toLowerCase());

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

    /**
     * 注册所有分类与条目。新增配置项时在此追加即可。
     *
     * <p>分类组织：
     * <ul>
     *   <li>general — NBT 策略、展示、搜索</li>
     *   <li>shulker_box — 潜影盒相关</li>
     *   <li>advanced — 调试日志、FULL 模式过滤、NBT key 列表</li>
     *   <li>experimental — 实验性功能（精确模式 / 配方自动定价，默认全部关闭）</li>
     * </ul>
     * </p>
     */
    private static void buildCategories(ConfigBuilder builder, ConfigEntryBuilder entries, IAExpConfig c) {
        // ===== General =====
        ConfigCategory general = builder.getOrCreateCategory(
                Text.translatable("itemalchemy-expansion.config.category.general"));

        general.addEntry(entries
                .startEnumSelector(Text.translatable("itemalchemy-expansion.config.nbtMode"),
                        IAExpConfig.NbtMode.class, c.nbtMode)
                .setDefaultValue(IAExpConfig.NbtMode.FULL)
                .setTooltip(Text.translatable("itemalchemy-expansion.config.nbtMode.tooltip"))
                .setEnumNameProvider(NBT_MODE_NAMING)
                .setSaveConsumer(v -> c.nbtMode = v)
                .build());

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
                .startStrList(Text.translatable("itemalchemy-expansion.config.smartNbtKeys"),
                        new ArrayList<>(c.smartNbtKeys))
                .setDefaultValue(new ArrayList<>())
                .setTooltip(Text.translatable("itemalchemy-expansion.config.smartNbtKeys.tooltip"))
                .setSaveConsumer((List<String> v) -> c.smartNbtKeys = v)
                .build());

        advanced.addEntry(entries
                .startStrList(Text.translatable("itemalchemy-expansion.config.ignoreNbtKeys"),
                        new ArrayList<>(c.ignoreNbtKeys))
                .setDefaultValue(new ArrayList<>())
                .setTooltip(Text.translatable("itemalchemy-expansion.config.ignoreNbtKeys.tooltip"))
                .setSaveConsumer((List<String> v) -> c.ignoreNbtKeys = v)
                .build());

        advanced.addEntry(entries
                .startTextDescription(Text.translatable("itemalchemy-expansion.config.perModRules.note"))
                .build());

        // ===== Experimental =====
        ConfigCategory experimental = builder.getOrCreateCategory(
                Text.translatable("itemalchemy-expansion.config.category.experimental"));

        experimental.addEntry(entries
                .startTextDescription(Text.translatable("itemalchemy-expansion.config.experimental.note"))
                .build());

        experimental.addEntry(entries
                .startBooleanToggle(Text.translatable("itemalchemy-expansion.config.preciseMode"),
                        c.preciseMode)
                .setDefaultValue(false)
                .setTooltip(Text.translatable("itemalchemy-expansion.config.preciseMode.tooltip"))
                .setSaveConsumer(v -> c.preciseMode = v)
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
    }
}
