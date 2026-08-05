package itemalchemy.expansion.client;

import itemalchemy.expansion.network.SetEmcNetwork;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
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
 *   │                                       │
 *   │   新 EMC 值: [________________]       │  输入框
 *   │                                       │
 *   │   作用范围: [仅本存档 ⇄]               │  循环按钮
 *   │                                       │
 *   │      [确认]        [取消]              │  操作按钮
 *   └──────────────────────────────────────┘
 * </pre></p>
 *
 * <p><b>暂停世界</b>：覆写 {@link #shouldPause()} 返回 true，行为类似 ESC 菜单，
 * 单人世界中打开此界面时世界 tick 暂停。</p>
 *
 * <p><b>确认流程</b>：解析输入框为 long（非法时提示并阻止），通过
 * {@link SetEmcNetwork#sendSetEmc} 发送 C2S 包（itemId + emc + scope），然后关闭界面。
 * 服务端处理持久化与同步（见 {@link itemalchemy.expansion.network.SetEmcNetwork}）。</p>
 *
 * <p><b>作用范围</b>：
 * <ul>
 *   <li>{@link Scope#THIS_SAVE}：仅本存档，写入 {@code <world>/itemalchemy_expansion_overrides.json}</li>
 *   <li>{@link Scope#GLOBAL}：所有存档，写入前置模组的 {@code config/itemalchemy/emc_config.json}</li>
 * </ul>
 * </p>
 */
public class SetEmcScreen extends Screen {

    /** 面板宽度（内容区） */
    private static final int PANEL_WIDTH = 240;
    /** 面板内边距 */
    private static final int PADDING = 12;

    /** 目标物品（手持物品的副本，仅用于展示） */
    private final ItemStack targetStack;
    /** 目标物品 ID（如 "minecraft:stone"） */
    private final String itemId;
    /** 当前 EMC 值（用于输入框默认值与展示） */
    private final long currentEmc;

    /** EMC 值输入框 */
    private TextFieldWidget emcField;
    /** 作用范围切换按钮 */
    private CyclingButtonWidget<Scope> scopeButton;
    /** 当前选择的作用范围 */
    private Scope scope = Scope.THIS_SAVE;

    /** 校验错误提示（null 表示无错误）；点击确认后若非法则设置，渲染时显示红色 */
    private Text errorText;

    public SetEmcScreen(ItemStack targetStack) {
        super(Text.translatable("itemalchemy-expansion.set_emc.title"));
        this.targetStack = targetStack.copy();
        this.itemId = resolveItemId(targetStack);
        this.currentEmc = resolveCurrentEmc(this.itemId);
    }

    private static String resolveItemId(ItemStack stack) {
        Identifier id = Registries.ITEM.getId(stack.getItem());
        return id == null ? "minecraft:air" : id.toString();
    }

    private static long resolveCurrentEmc(String itemId) {
        try {
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
        int fieldWidth = 140;
        int fieldX = centerX - fieldWidth / 2;
        int fieldY = this.height / 2 - 6;
        emcField = new TextFieldWidget(this.textRenderer, fieldX, fieldY, fieldWidth, 16,
                Text.translatable("itemalchemy-expansion.set_emc.emc_field"));
        emcField.setMaxLength(18);
        emcField.setText(String.valueOf(currentEmc));
        emcField.setFocused(true);
        // 仅允许纯数字（EMC >= 0）
        emcField.setTextPredicate(this::isValidEmcInput);
        addDrawableChild(emcField);

        // ===== 作用范围循环按钮 =====
        int scopeY = fieldY + 26;
        int scopeWidth = 140;
        scopeButton = CyclingButtonWidget.<Scope>builder(SetEmcScreen::scopeDisplayName)
                .values(Scope.THIS_SAVE, Scope.GLOBAL)
                .initially(scope)
                .build(centerX - scopeWidth / 2, scopeY, scopeWidth, 20,
                        Text.translatable("itemalchemy-expansion.set_emc.scope_label"),
                        (button, value) -> scope = value);
        addDrawableChild(scopeButton);

        // ===== 操作按钮 =====
        int btnY = scopeY + 30;
        int btnWidth = 70;
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

        // 发送 C2S 设置 EMC 请求（客户端发送方在 SetEmcClientNetwork，因 ClientPlayNetworking 是客户端专属 API）
        SetEmcClientNetwork.sendSetEmc(itemId, emc,
                scope == Scope.THIS_SAVE ? SetEmcNetwork.SCOPE_THIS_SAVE : SetEmcNetwork.SCOPE_GLOBAL);

        this.close();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // 渲染背景（半透明深色蒙层，与原版 pause 菜单风格一致）
        renderBackground(context);

        int centerX = this.width / 2;
        int panelLeft = centerX - PANEL_WIDTH / 2;
        int panelTop = this.height / 2 - 78;
        int panelHeight = 156;
        int panelBottom = panelTop + panelHeight;

        // 面板背景（深色半透明 + 边框）
        context.fill(panelLeft, panelTop, panelLeft + PANEL_WIDTH, panelBottom, 0xC0101010);
        drawBorder(context, panelLeft, panelTop, PANEL_WIDTH, panelHeight, 0xFF404040);

        // 标题（居中，带阴影）
        context.drawCenteredTextWithShadow(this.textRenderer, this.title,
                centerX, panelTop + PADDING, 0xFFFFFF);

        // 物品行：图标 + 名称 + ID + 当前 EMC
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

        // EMC 输入框标签（输入框上方）
        Text fieldLabel = Text.translatable("itemalchemy-expansion.set_emc.new_emc_label");
        context.drawText(this.textRenderer, fieldLabel,
                panelLeft + PADDING, emcField.getY() - 11, 0xC0C0C0, false);

        // 作用范围说明（按钮下方，暗灰色小字）
        Text scopeHint = Text.translatable("itemalchemy-expansion.set_emc.scope." + scope.name().toLowerCase() + ".hint")
                .formatted(Formatting.DARK_GRAY);
        context.drawText(this.textRenderer, scopeHint,
                panelLeft + PADDING, scopeButton.getY() + 22, 0x808080, false);

        // 错误提示（操作按钮上方，红色，居中）
        if (errorText != null) {
            context.drawCenteredTextWithShadow(this.textRenderer, errorText,
                    centerX, scopeButton.getY() + 38, 0xFF5555);
        }

        // 子控件（输入框、按钮）渲染
        super.render(context, mouseX, mouseY, delta);
    }

    /** 绘制边框（4 条线） */
    private static void drawBorder(DrawContext context, int x, int y, int width, int height, int color) {
        context.fill(x, y, x + width, y + 1, color);                  // top
        context.fill(x, y + height - 1, x + width, y + height, color); // bottom
        context.fill(x, y, x + 1, y + height, color);                 // left
        context.fill(x + width - 1, y, x + width, y + height, color);  // right
    }

    /** 作用范围枚举 */
    public enum Scope {
        /** 仅本存档 */
        THIS_SAVE,
        /** 所有存档（全局） */
        GLOBAL
    }
}
