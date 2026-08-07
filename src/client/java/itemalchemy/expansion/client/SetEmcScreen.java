package itemalchemy.expansion.client;

import itemalchemy.expansion.IAExpServices;
import itemalchemy.expansion.client.util.GuiRenderUtil;
import itemalchemy.expansion.config.IAExpConfigHolder;
import itemalchemy.expansion.nbt.ItemVariantKey;
import itemalchemy.expansion.network.PreciseEmcStore;
import itemalchemy.expansion.network.SetEmcNetwork;
import itemalchemy.expansion.util.EmcQueryUtil;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.pitan76.itemalchemy.EMCManager;

/**
 * 「设置物品 EMC」GUI：手持物品按快捷键打开。
 *
 * <p>界面布局（简约居中面板）：
 * <pre>
 *   ┌──────────────────────────────────────┐
 *   │           设置物品 EMC 值              │  标题
 *   │                                       │
 *   │   [物品图标]  物品名称                  │  目标物品
 *   │              minecraft:stone          │  物品 ID（灰色小字）
 *   │              当前 EMC: 1               │  当前值（黄色）
 *   │              变体: {AmmoId:...}         │  精确模式 ON 时显示（灰色小字）
 *   │                                       │
 *   │   新 EMC 值: [________________]       │  输入框
 *   │   定价精度: [精确 ⇄ 通用]               │  循环按钮（新增）
 *   │   作用范围: [仅本存档 ⇄]               │  循环按钮
 *   │                                       │
 *   │      [确认]        [取消]              │  操作按钮
 *   └──────────────────────────────────────┘
 * </pre></p>
 *
 * <p><b>定价精度</b>：
 * <ul>
 *   <li>{@link Precision#PRECISE}：写入 {@link PreciseEmcStore}（按变体键，精确模式 ON 时使用）</li>
 *   <li>{@link Precision#GENERAL}：写入原有 {@code PerSaveEmcStore}/{@code GlobalEmcStore}（按 id，通用模式使用）</li>
 * </ul>
 * 初始值跟随全局 {@code preciseMode} 配置；无 NBT 物品两者等价。
 * </p>
 *
 * <p><b>暂停世界</b>：覆写 {@link #shouldPause()} 返回 true，行为类似 ESC 菜单，
 * 单人世界中打开此界面时世界 tick 暂停。</p>
 */
public class SetEmcScreen extends Screen {

    /** 面板宽度（内容区） */
    private static final int PANEL_WIDTH = 260;
    /** 面板内边距 */
    private static final int PADDING = 12;

    /** 目标物品（手持物品的副本，仅用于展示） */
    private final ItemStack targetStack;
    /** 目标物品 ID（如 "minecraft:stone"） */
    private final String itemId;
    /** 当前变体键存储串（{@code ItemVariantKey.toStorageString()}） */
    private final String variantKey;
    /** 当前 NBT 简要串（用于显示，可能为空） */
    private final String nbtBrief;
    /** 当前 EMC 值（按定价精度查询，用于输入框默认值与展示） */
    private final long currentEmc;

    /** EMC 值输入框 */
    private TextFieldWidget emcField;
    /** 定价精度切换按钮 */
    private CyclingButtonWidget<Precision> precisionButton;
    /** 作用范围切换按钮 */
    private CyclingButtonWidget<Scope> scopeButton;
    /** 当前选择的定价精度 */
    private Precision precision;
    /** 当前选择的作用范围 */
    private Scope scope = Scope.THIS_SAVE;

    /** 校验错误提示（null 表示无错误）；点击确认后若非法则设置，渲染时显示红色 */
    private Text errorText;

    public SetEmcScreen(ItemStack targetStack) {
        super(Text.translatable("itemalchemy-expansion.set_emc.title"));
        this.targetStack = targetStack.copy();
        this.itemId = EmcQueryUtil.resolveItemId(targetStack);
        // 算变体键（依赖 IAExpServices 已 init）
        ItemVariantKey vk = IAExpServices.variantKeyOf(targetStack);
        this.variantKey = vk.toStorageString();
        this.nbtBrief = extractNbtBrief(targetStack);
        // 初始定价精度：有 NBT 的物品默认 PRECISE（精确配置的意义所在），
        // 无 NBT 的物品默认 GENERAL（两者等价，更简单）。
        // 不再跟随全局 preciseMode 开关——精确配置始终可用。
        NbtCompound nbt = targetStack.getNbt();
        this.precision = (nbt != null && !nbt.isEmpty()) ? Precision.PRECISE : Precision.GENERAL;
        this.currentEmc = resolveCurrentEmc(this.itemId, this.variantKey, this.precision);
    }

    /** 提取 NBT 简要串（截断到 30 字符）用于显示。无 NBT 返回空串 */
    private static String extractNbtBrief(ItemStack stack) {
        NbtCompound nbt = stack.getNbt();
        if (nbt == null || nbt.isEmpty()) return "";
        String s = nbt.toString();
        // 截断过长 NBT
        return s.length() > 30 ? s.substring(0, 30) + "..." : s;
    }

    /** 按定价精度查询当前 EMC：精确 → 精确 map → 通用 map；通用 → 通用 map */
    private static long resolveCurrentEmc(String itemId, String variantKey, Precision precision) {
        try {
            if (precision == Precision.PRECISE) {
                Long v = PreciseEmcStore.get(variantKey);
                if (v != null) return v;
                // 回退到通用
                Long g = EMCManager.getMap().get(itemId);
                return g == null ? 0L : g;
            }
            Long v = EMCManager.getMap().get(itemId);
            return v == null ? 0L : v;
        } catch (Throwable t) {
            return 0L;
        }
    }

    @Override
    public boolean shouldPause() {
        // 单人世界中打开此界面时暂停世界 tick（与 ESC 菜单一致）
        return true;
    }

    @Override
    protected void init() {
        errorText = null;
        int centerX = this.width / 2;

        // ===== EMC 输入框 =====
        int fieldWidth = 160;
        int fieldX = centerX - fieldWidth / 2;
        int fieldY = this.height / 2 - 18;
        emcField = new TextFieldWidget(this.textRenderer, fieldX, fieldY, fieldWidth, 16,
                Text.translatable("itemalchemy-expansion.set_emc.emc_field"));
        emcField.setMaxLength(18);
        emcField.setText(String.valueOf(currentEmc));
        emcField.setFocused(true);
        emcField.setTextPredicate(this::isValidEmcInput);
        addDrawableChild(emcField);

        // ===== 定价精度循环按钮 =====
        int precisionY = fieldY + 26;
        int precisionWidth = 160;
        precisionButton = CyclingButtonWidget.<Precision>builder(SetEmcScreen::precisionDisplayName)
                .values(Precision.PRECISE, Precision.GENERAL)
                .initially(precision)
                .build(centerX - precisionWidth / 2, precisionY, precisionWidth, 20,
                        Text.translatable("itemalchemy-expansion.set_emc.precision_label"),
                        (button, value) -> {
                            precision = value;
                            // 切换精度时刷新输入框默认值（按当前精度查）
                            long refreshed = resolveCurrentEmc(itemId, variantKey, precision);
                            emcField.setText(String.valueOf(refreshed));
                        });
        addDrawableChild(precisionButton);

        // ===== 作用范围循环按钮 =====
        int scopeY = precisionY + 26;
        int scopeWidth = 160;
        scopeButton = CyclingButtonWidget.<Scope>builder(SetEmcScreen::scopeDisplayName)
                .values(Scope.THIS_SAVE, Scope.GLOBAL)
                .initially(scope)
                .build(centerX - scopeWidth / 2, scopeY, scopeWidth, 20,
                        Text.translatable("itemalchemy-expansion.set_emc.scope_label"),
                        (button, value) -> scope = value);
        addDrawableChild(scopeButton);

        // ===== 操作按钮 =====
        int btnY = scopeY + 30;
        int btnWidth = 80;
        int btnGap = 8;
        int leftBtnX = centerX - btnWidth - btnGap / 2;
        int rightBtnX = centerX + btnGap / 2;
        addDrawableChild(ButtonWidget.builder(
                Text.translatable("itemalchemy-expansion.set_emc.confirm"),
                b -> onConfirm())
                .dimensions(leftBtnX, btnY, btnWidth, 20)
                .build());
        addDrawableChild(ButtonWidget.builder(
                Text.translatable("itemalchemy-expansion.set_emc.cancel"),
                b -> this.close())
                .dimensions(rightBtnX, btnY, btnWidth, 20)
                .build());
    }

    /** 输入框校验：允许空串、纯数字。负号不允许（EMC >= 0）。 */
    private boolean isValidEmcInput(String s) {
        if (s.isEmpty()) return true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    private static Text scopeDisplayName(Scope s) {
        return Text.translatable("itemalchemy-expansion.set_emc.scope." + s.name().toLowerCase());
    }

    private static Text precisionDisplayName(Precision p) {
        return Text.translatable("itemalchemy-expansion.set_emc.precision." + p.name().toLowerCase());
    }

    /** 确认按钮回调：解析 EMC 值，发送网络包，关闭界面。 */
    private void onConfirm() {
        String raw = emcField.getText().trim();
        if (raw.isEmpty()) {
            errorText = Text.translatable("itemalchemy-expansion.set_emc.fail.empty")
                    .formatted(Formatting.RED);
            return;
        }
        long emc;
        try {
            emc = Long.parseLong(raw);
        } catch (NumberFormatException e) {
            errorText = Text.translatable("itemalchemy-expansion.set_emc.fail.parse")
                    .formatted(Formatting.RED);
            return;
        }
        if (emc < 0) {
            errorText = Text.translatable("itemalchemy-expansion.set_emc.fail.negative")
                    .formatted(Formatting.RED);
            return;
        }

        // 发送 C2S 设置 EMC 请求：根据定价精度决定写入精确/通用存储
        boolean precise = (precision == Precision.PRECISE);
        String vkPayload = precise ? variantKey : "";
        SetEmcClientNetwork.sendSetEmc(itemId, emc,
                scope == Scope.THIS_SAVE ? SetEmcNetwork.SCOPE_THIS_SAVE : SetEmcNetwork.SCOPE_GLOBAL,
                precise, vkPayload);

        this.close();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context);

        int centerX = this.width / 2;
        int panelLeft = centerX - PANEL_WIDTH / 2;
        int panelTop = this.height / 2 - 98;
        int panelHeight = 196;
        int panelBottom = panelTop + panelHeight;

        // 面板背景（深色半透明 + 边框）
        context.fill(panelLeft, panelTop, panelLeft + PANEL_WIDTH, panelBottom, 0xC0101010);
        GuiRenderUtil.drawBorder(context, panelLeft, panelTop, PANEL_WIDTH, panelHeight, 0xFF404040);

        // 标题
        context.drawCenteredTextWithShadow(this.textRenderer, this.title,
                centerX, panelTop + PADDING, 0xFFFFFF);

        // 物品行：图标 + 名称 + ID + 当前 EMC + 变体（精确模式时）
        int itemY = panelTop + PADDING + 18;
        int itemIconX = panelLeft + PADDING + 2;
        context.drawItem(targetStack, itemIconX, itemY - 4);
        context.drawText(this.textRenderer, targetStack.getName(),
                itemIconX + 20, itemY, 0xFFFFFF, true);
        Text idText = Text.literal(itemId).formatted(Formatting.GRAY);
        context.drawText(this.textRenderer, idText,
                itemIconX + 20, itemY + 10, 0xA0A0A0, false);
        Text currentText = Text.translatable("itemalchemy-expansion.set_emc.current_emc",
                String.format("%,d", currentEmc)).formatted(Formatting.YELLOW);
        context.drawText(this.textRenderer, currentText,
                itemIconX + 20, itemY + 21, 0xFFFF00, false);
        // 精确模式 + 有 NBT：显示变体简要
        if (!nbtBrief.isEmpty()) {
            Text variantText = Text.translatable("itemalchemy-expansion.set_emc.variant_label",
                    nbtBrief).formatted(Formatting.DARK_GRAY);
            context.drawText(this.textRenderer, variantText,
                    itemIconX + 20, itemY + 32, 0x808080, false);
        }

        // EMC 输入框标签
        Text fieldLabel = Text.translatable("itemalchemy-expansion.set_emc.new_emc_label");
        context.drawText(this.textRenderer, fieldLabel,
                panelLeft + PADDING, emcField.getY() - 11, 0xC0C0C0, false);

        // 定价精度说明（按钮下方）
        Text precisionHint = Text.translatable(
                "itemalchemy-expansion.set_emc.precision." + precision.name().toLowerCase() + ".hint")
                .formatted(Formatting.DARK_GRAY);
        context.drawText(this.textRenderer, precisionHint,
                panelLeft + PADDING, precisionButton.getY() + 22, 0x808080, false);

        // 作用范围说明
        Text scopeHint = Text.translatable("itemalchemy-expansion.set_emc.scope." + scope.name().toLowerCase() + ".hint")
                .formatted(Formatting.DARK_GRAY);
        context.drawText(this.textRenderer, scopeHint,
                panelLeft + PADDING, scopeButton.getY() + 22, 0x808080, false);

        // 错误提示
        if (errorText != null) {
            context.drawCenteredTextWithShadow(this.textRenderer, errorText,
                    centerX, scopeButton.getY() + 38, 0xFF5555);
        }

        super.render(context, mouseX, mouseY, delta);
    }

    /** 作用范围枚举 */
    public enum Scope {
        /** 仅本存档 */
        THIS_SAVE,
        /** 所有存档（全局） */
        GLOBAL
    }

    /** 定价精度枚举（新增） */
    public enum Precision {
        /** 精确：按变体键（itemId + NBT 指纹）存储与查询 */
        PRECISE,
        /** 通用：按物品 ID 存储与查询（原行为） */
        GENERAL
    }
}
