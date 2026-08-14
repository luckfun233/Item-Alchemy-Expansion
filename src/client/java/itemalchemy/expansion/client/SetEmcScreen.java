package itemalchemy.expansion.client;

import itemalchemy.expansion.IAExpServices;
import itemalchemy.expansion.client.util.GuiRenderUtil;
import itemalchemy.expansion.config.IAExpConfigHolder;
import itemalchemy.expansion.nbt.ItemVariantKey;
import itemalchemy.expansion.network.AutoEmcStore;
import itemalchemy.expansion.network.PreciseEmcStore;
import itemalchemy.expansion.network.SetEmcNetwork;
import itemalchemy.expansion.util.EmcQueryUtil;
import net.minecraft.client.MinecraftClient;
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
import net.pitan76.mcpitanlib.api.util.CustomDataUtil;

/**
 * 「设置物品 EMC」GUI：手持物品按快捷键打开。简约居中面板，显示目标物品信息
 * （图标 / 名称 / ID / 当前 EMC / 变体简要）+ 新 EMC 输入框 + 定价精度循环按钮
 * + 作用范围循环按钮 + 确认 / 取消按钮。
 *
 * <p><b>定价精度</b>：
 * <ul>
 *   <li>{@link Precision#PRECISE}：写入 {@link PreciseEmcStore}（按变体键）</li>
 *   <li>{@link Precision#GENERAL}：写入原有 {@code PerSaveEmcStore}/{@code GlobalEmcStore}（按 id）</li>
 * </ul>
 * 无 NBT 物品两者等价。</p>
 *
 * <p>覆写 {@link #shouldPause()} 返回 true，单人世界中打开此界面时暂停世界 tick。</p>
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
        // 算变体键，依赖 IAExpServices 已 init
        ItemVariantKey vk = IAExpServices.variantKeyOf(targetStack);
        this.variantKey = vk.toStorageString();
        this.nbtBrief = extractNbtBrief(targetStack);
        // 有 NBT 的物品默认 PRECISE（精确配置的意义所在），无 NBT 默认 GENERAL（两者等价）
        NbtCompound nbt = CustomDataUtil.getNbt(targetStack);
        this.precision = (nbt != null && !nbt.isEmpty()) ? Precision.PRECISE : Precision.GENERAL;
        this.currentEmc = resolveCurrentEmc(this.itemId, this.variantKey, this.precision);
    }

    /** 提取 NBT 简要串（截断到 30 字符）用于显示。无 NBT 返回空串 */
    private static String extractNbtBrief(ItemStack stack) {
        NbtCompound nbt = CustomDataUtil.getNbt(stack);
        if (nbt == null || nbt.isEmpty()) return "";
        String s = nbt.toString();
        return s.length() > 30 ? s.substring(0, 30) + "..." : s;
    }

    /** 按定价精度查询当前 EMC：精确 → 精确 map → 通用 map；通用 → 通用 map → 自动通用 map */
    private static long resolveCurrentEmc(String itemId, String variantKey, Precision precision) {
        try {
            if (precision == Precision.PRECISE) {
                Long v = PreciseEmcStore.get(variantKey);
                if (v != null) return v;
                // 精确 map 无值时回退到通用
                Long g = EMCManager.getMap().get(itemId);
                return g == null ? 0L : g;
            }
            // 通用模式：先查 L2 通用层，再回退查 L4 自动通用层
            // 不回退查 L3 自动精确层（变体键级别），否则会显示「自动精确值」而非「通用值」
            Long v = EMCManager.getMap().get(itemId);
            if (v != null) return v;
            Long auto = AutoEmcStore.getGeneral(itemId);
            return auto == null ? 0L : auto;
        } catch (Throwable t) {
            return 0L;
        }
    }

    @Override
    public boolean shouldPause() {
        return true;
    }

    @Override
    protected void init() {
        errorText = null;
        int centerX = this.width / 2;

        int fieldWidth = 160;
        int fieldX = centerX - fieldWidth / 2;
        int fieldY = this.height / 2 - 18;
        emcField = new TextFieldWidget(this.textRenderer, fieldX, fieldY, fieldWidth, 16,
                Text.translatable("itemalchemy-expansion.set_emc.emc_field"));
        emcField.setMaxLength(18);
        emcField.setText(String.valueOf(currentEmc));
        emcField.setTextPredicate(this::isValidEmcInput);
        addDrawableChild(emcField);
        // 关键：必须让 Screen.focused 指向输入框，否则 keyPressed/charTyped
        // 会因为 this.focused==null 而直接返回 false，键盘事件全部丢失
        // （退格删除、数字键、IME 字符都进不来，表现为「卡输入法」）
        this.setFocused(emcField);

        int precisionY = fieldY + 26;
        int precisionWidth = 160;
        precisionButton = CyclingButtonWidget.<Precision>builder(SetEmcScreen::precisionDisplayName)
                .values(Precision.PRECISE, Precision.GENERAL)
                .initially(precision)
                .build(centerX - precisionWidth / 2, precisionY, precisionWidth, 20,
                        Text.translatable("itemalchemy-expansion.set_emc.precision_label"),
                        (button, value) -> {
                            precision = value;
                            // 切换精度时刷新输入框默认值
                            long refreshed = resolveCurrentEmc(itemId, variantKey, precision);
                            emcField.setText(String.valueOf(refreshed));
                            // Screen.mouseClicked 在 widget.mouseClicked 返回 true 后会
                            // 把焦点设到按钮上，同步 setFocused 会被覆盖。延迟到下一帧
                            // 把焦点还给输入框，让用户切换精度后能直接继续输入新值。
                            MinecraftClient.getInstance().execute(() -> this.setFocused(emcField));
                        });
        addDrawableChild(precisionButton);

        int scopeY = precisionY + 26;
        int scopeWidth = 160;
        scopeButton = CyclingButtonWidget.<Scope>builder(SetEmcScreen::scopeDisplayName)
                .values(Scope.THIS_SAVE, Scope.GLOBAL)
                .initially(scope)
                .build(centerX - scopeWidth / 2, scopeY, scopeWidth, 20,
                        Text.translatable("itemalchemy-expansion.set_emc.scope_label"),
                        (button, value) -> {
                            scope = value;
                            MinecraftClient.getInstance().execute(() -> this.setFocused(emcField));
                        });
        addDrawableChild(scopeButton);

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

        // 按定价精度决定写入精确/通用存储
        boolean precise = (precision == Precision.PRECISE);
        int scopeCode = scope == Scope.THIS_SAVE ? SetEmcNetwork.SCOPE_THIS_SAVE : SetEmcNetwork.SCOPE_GLOBAL;

        if (precise) {
            // 精确模式：直接发送，无需查询 L1 候选
            SetEmcClientNetwork.sendSetEmc(itemId, emc, scopeCode, true, variantKey, null);
            this.close();
            return;
        }

        // 通用模式：先查询该 ID 是否有 L1 精确覆盖，有则弹覆盖确认框
        final long finalEmc = emc;
        final int finalScopeCode = scopeCode;
        SetEmcClientNetwork.setPendingPreciseQueryCallback(variants -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (variants == null || variants.isEmpty()) {
                // 无 L1 覆盖：直接设通用价，不清除
                SetEmcClientNetwork.sendSetEmc(itemId, finalEmc, finalScopeCode, false, "", null);
                if (mc != null) mc.setScreen(null);
                return;
            }
            // 有 L1 覆盖：弹覆盖确认框（复选框逐个选择 + 物品图标/名称/旧EMC）
            mc.setScreen(new OverwritePreciseConfirmScreen(
                    itemId, finalEmc, variants,
                    toClear -> {
                        // 确认：发送 set_emc 包，带要清除的变体键列表
                        SetEmcClientNetwork.sendSetEmc(itemId, finalEmc, finalScopeCode, false, "", toClear);
                    },
                    () -> {
                        // 取消：回到 SetEmcScreen
                        if (mc != null) mc.setScreen(this);
                    }
            ));
        });
        SetEmcClientNetwork.sendQueryPreciseByItem(itemId);
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        // 基类 1.21.1 版会 applyBlur 模糊全屏；背景由 render() 自行铺暗色
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // 1.21.1 的 4 参 renderBackground 带 applyBlur 会产生模糊残影，直接铺暗色背景
        context.fill(0, 0, this.width, this.height, 0xA0000000);

        int centerX = this.width / 2;
        int panelLeft = centerX - PANEL_WIDTH / 2;
        int panelTop = this.height / 2 - 98;
        int panelHeight = 196;
        int panelBottom = panelTop + panelHeight;

        // 面板背景：深色半透明 + 边框
        context.fill(panelLeft, panelTop, panelLeft + PANEL_WIDTH, panelBottom, 0xC0101010);
        GuiRenderUtil.drawBorder(context, panelLeft, panelTop, PANEL_WIDTH, panelHeight, 0xFF404040);

        context.drawCenteredTextWithShadow(this.textRenderer, this.title,
                centerX, panelTop + PADDING, 0xFFFFFF);

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
        // 精确模式且有 NBT 时显示变体简要
        if (!nbtBrief.isEmpty()) {
            Text variantText = Text.translatable("itemalchemy-expansion.set_emc.variant_label",
                    nbtBrief).formatted(Formatting.DARK_GRAY);
            context.drawText(this.textRenderer, variantText,
                    itemIconX + 20, itemY + 32, 0x808080, false);
        }

        Text fieldLabel = Text.translatable("itemalchemy-expansion.set_emc.new_emc_label");
        context.drawText(this.textRenderer, fieldLabel,
                panelLeft + PADDING, emcField.getY() - 11, 0xC0C0C0, false);

        Text precisionHint = Text.translatable(
                "itemalchemy-expansion.set_emc.precision." + precision.name().toLowerCase() + ".hint")
                .formatted(Formatting.DARK_GRAY);
        context.drawText(this.textRenderer, precisionHint,
                panelLeft + PADDING, precisionButton.getY() + 22, 0x808080, false);

        Text scopeHint = Text.translatable("itemalchemy-expansion.set_emc.scope." + scope.name().toLowerCase() + ".hint")
                .formatted(Formatting.DARK_GRAY);
        context.drawText(this.textRenderer, scopeHint,
                panelLeft + PADDING, scopeButton.getY() + 22, 0x808080, false);

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

    /** 定价精度枚举 */
    public enum Precision {
        /** 精确：按变体键（itemId + NBT 指纹）存储与查询 */
        PRECISE,
        /** 通用：按物品 ID 存储与查询（原行为） */
        GENERAL
    }
}
