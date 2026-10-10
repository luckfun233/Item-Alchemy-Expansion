package itemalchemy.expansion.client;

import itemalchemy.expansion.ItemAlchemyExpansion;
import itemalchemy.expansion.client.util.GuiRenderUtil;
import itemalchemy.expansion.gui.CardForgeScreenHandler;
import itemalchemy.expansion.item.EmcCardItem;
import itemalchemy.expansion.network.CardForgeNetwork;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.pitan76.mcpitanlib.api.client.gui.screen.SimpleInventoryScreen;
import net.pitan76.mcpitanlib.api.client.render.handledscreen.DrawBackgroundArgs;
import net.pitan76.mcpitanlib.api.client.render.handledscreen.DrawForegroundArgs;
import net.pitan76.mcpitanlib.api.client.render.handledscreen.RenderArgs;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 制卡台客户端 GUI：上方悬浮标签 + MC 经典浅灰面板。
 *
 * <p>三个标签：「属性」（单槽设私有/公有）、「组合」（两槽关联/合并/解除关联）、「绑定」
 * （单槽绑定指定玩家 EMC，可设单次/总额支出限额，支持在线玩家下拉模糊搜索）。
 * 面板、标签、卡槽底框均由代码绘制。</p>
 */
public class CardForgeScreen extends SimpleInventoryScreen<CardForgeScreenHandler> {

    private static final int BG_W = 176;
    private static final int BG_H = 230;

    private static final int TAB_ATTR = 0;
    private static final int TAB_COMBINE = 1;
    private static final int TAB_BIND = 2;

    // MC 经典浅灰主题 + 青色点缀
    private static final int ACCENT = 0xFF2EC4B6;
    private static final int ACCENT_DARK = 0xFF1E88A8;
    private static final int PANEL = 0xFFC6C6C6;
    private static final int PANEL_LINE = 0xFF555555;
    private static final int SLOT_BG = 0xFF8B8B8B;
    private static final int TEXT_MAIN = 0xFF404040;
    private static final int TEXT_DIM = 0xFF707070;

    /** 绑定页玩家下拉：每行高度 */
    private static final int DROPDOWN_ROW_H = 12;
    /** 绑定页玩家下拉：最多显示行数 */
    private static final int DROPDOWN_MAX_ROWS = 4;

    /** 绑定页输入框宽度（原版 TextFieldWidget 的 width 字段为 protected，取不到） */
    private static final int NAME_FIELD_W = 116;
    private static final int LIMIT_FIELD_W = 40;
    private static final int FIELD_H = 14;

    private int currentTab = TAB_ATTR;

    private ModernButton tabAttrBtn;
    private ModernButton tabCombineBtn;
    private ModernButton tabBindBtn;
    private ModernButton btnPrivate;
    private ModernButton btnPublic;
    private ModernButton btnLink;
    private ModernButton btnMerge;
    private ModernButton btnUnlink;
    private ModernButton btnBind;
    private ModernButton btnApplyLimits;
    private TextFieldWidget nameField;
    private TextFieldWidget singleField;
    private TextFieldWidget totalField;

    public CardForgeScreen(CardForgeScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
        setBackgroundWidth(BG_W);
        setBackgroundHeight(BG_H);
    }

    @Override
    public Identifier getTexture() {
        return new Identifier(ItemAlchemyExpansion.MOD_ID, "textures/gui/card_forge");
    }

    @Override
    public void initOverride() {
        int cx = this.width / 2;
        int top = (this.height - BG_H) / 2;

        // 三个悬浮标签
        int tabW = 52, tabH = 24;
        tabAttrBtn = new ModernButton(cx - 84, top + 6, tabW, tabH,
                Text.translatable("itemalchemy-expansion.card_forge.tab.attr"), b -> switchTab(TAB_ATTR), null);
        tabCombineBtn = new ModernButton(cx - 26, top + 6, tabW, tabH,
                Text.translatable("itemalchemy-expansion.card_forge.tab.combine"), b -> switchTab(TAB_COMBINE), null);
        tabBindBtn = new ModernButton(cx + 32, top + 6, tabW, tabH,
                Text.translatable("itemalchemy-expansion.card_forge.tab.bind"), b -> switchTab(TAB_BIND), null);
        addDrawableChild_compatibility(tabAttrBtn);
        addDrawableChild_compatibility(tabCombineBtn);
        addDrawableChild_compatibility(tabBindBtn);

        // 属性页内容按钮
        int w = 70, h = 20;
        int rowY = top + 88;
        btnPrivate = new ModernButton(cx - w - 4, rowY, w, h,
                Text.translatable("itemalchemy-expansion.card_forge.private"),
                b -> CardForgeClientNetwork.sendAction(CardForgeNetwork.ACTION_SET_PRIVATE),
                Text.translatable("itemalchemy-expansion.card_forge.private.tooltip"));
        btnPublic = new ModernButton(cx + 4, rowY, w, h,
                Text.translatable("itemalchemy-expansion.card_forge.public"),
                b -> CardForgeClientNetwork.sendAction(CardForgeNetwork.ACTION_SET_PUBLIC),
                Text.translatable("itemalchemy-expansion.card_forge.public.tooltip"));

        // 组合页内容按钮（关联 / 合并 / 解除关联）
        int cw = 52;
        btnLink = new ModernButton(cx - 80, rowY, cw, h,
                Text.translatable("itemalchemy-expansion.card_forge.link"),
                b -> CardForgeClientNetwork.sendAction(CardForgeNetwork.ACTION_LINK),
                Text.translatable("itemalchemy-expansion.card_forge.link.tooltip"));
        btnMerge = new ModernButton(cx - 24, rowY, cw, h,
                Text.translatable("itemalchemy-expansion.card_forge.merge"),
                b -> CardForgeClientNetwork.sendAction(CardForgeNetwork.ACTION_MERGE),
                Text.translatable("itemalchemy-expansion.card_forge.merge.tooltip"));
        btnUnlink = new ModernButton(cx + 32, rowY, cw, h,
                Text.translatable("itemalchemy-expansion.card_forge.unlink"),
                b -> CardForgeClientNetwork.sendAction(CardForgeNetwork.ACTION_UNLINK),
                Text.translatable("itemalchemy-expansion.card_forge.unlink.tooltip"));
        addDrawableChild_compatibility(btnPrivate);
        addDrawableChild_compatibility(btnPublic);
        addDrawableChild_compatibility(btnLink);
        addDrawableChild_compatibility(btnMerge);
        addDrawableChild_compatibility(btnUnlink);

        // 绑定页内容：未绑定时显示 玩家名输入 + 绑定按钮 + 下拉候选；
        // 已绑定时显示 状态信息 + 解除绑定 + 限额输入 + 应用（renderOverride 按卡态切换）
        nameField = new TextFieldWidget(this.textRenderer, cx - 58, top + 62, NAME_FIELD_W, FIELD_H,
                Text.translatable("itemalchemy-expansion.card_forge.bind.name_field"));
        nameField.setMaxLength(16);
        singleField = new TextFieldWidget(this.textRenderer, cx - 58, top + 108, LIMIT_FIELD_W, FIELD_H,
                Text.translatable("itemalchemy-expansion.card_forge.bind.single_field"));
        singleField.setMaxLength(12);
        totalField = new TextFieldWidget(this.textRenderer, cx - 12, top + 108, LIMIT_FIELD_W, FIELD_H,
                Text.translatable("itemalchemy-expansion.card_forge.bind.total_field"));
        totalField.setMaxLength(12);
        btnBind = new ModernButton(cx - 58, top + 84, 116, 16,
                Text.translatable("itemalchemy-expansion.card_forge.bind"), b -> doBind(), null);
        btnApplyLimits = new ModernButton(cx - 30, top + 127, 60, 16,
                Text.translatable("itemalchemy-expansion.card_forge.bind.apply"), b -> doApplyLimits(),
                Text.translatable("itemalchemy-expansion.card_forge.bind.apply.tooltip"));
        addDrawableChild_compatibility(nameField);
        addDrawableChild_compatibility(singleField);
        addDrawableChild_compatibility(totalField);
        addDrawableChild_compatibility(btnBind);
        addDrawableChild_compatibility(btnApplyLimits);

        applyTab();
    }

    private void switchTab(int tab) {
        currentTab = tab;
        applyTab();
        // 切到绑定页时刷新在线玩家列表（下拉数据源）并聚焦输入框
        if (tab == TAB_BIND) {
            CardForgeClientNetwork.sendRequestPlayers();
            if (!isCardBound()) {
                // 1.19.2 需同时设 Screen.focused（键盘事件路由）与控件焦点（下拉展开判定）
                this.setFocused(nameField);
                nameField.setTextFieldFocused(true);
            }
        }
    }

    /** 按当前标签切换内容按钮/字段显隐与卡槽数量（卡态相关的细化在 renderOverride） */
    private void applyTab() {
        boolean attr = currentTab == TAB_ATTR;
        boolean combine = currentTab == TAB_COMBINE;
        boolean bind = currentTab == TAB_BIND;

        tabAttrBtn.setTabActive(attr);
        tabCombineBtn.setTabActive(combine);
        tabBindBtn.setTabActive(bind);

        btnPrivate.visible = attr;
        btnPublic.visible = attr;
        btnLink.visible = combine;
        btnMerge.visible = combine;
        btnUnlink.visible = combine;

        nameField.setVisible(bind);
        singleField.setVisible(bind);
        totalField.setVisible(bind);
        btnBind.visible = bind;
        btnApplyLimits.visible = bind;
    }

    /** 当前是否有卡 */
    private boolean hasCard() {
        Slot s0 = this.handler.slots.get(0);
        return s0 != null && !s0.getStack().isEmpty();
    }

    /** 当前槽 0 的卡是否已绑定 */
    private boolean isCardBound() {
        Slot s0 = this.handler.slots.get(0);
        if (s0 == null || s0.getStack().isEmpty()) return false;
        return EmcCardItem.isBound(s0.getStack());
    }

    /** 当前槽 0 的卡是否已关联 */
    private boolean isCardLinked() {
        Slot s0 = this.handler.slots.get(0);
        if (s0 == null || s0.getStack().isEmpty()) return false;
        return EmcCardItem.getLinkGroup(s0.getStack()) != null;
    }

    private void doBind() {
        if (isCardBound()) {
            CardForgeClientNetwork.sendUnbind();
        } else {
            CardForgeClientNetwork.sendBind(nameField.getText().trim());
        }
    }

    private void doApplyLimits() {
        CardForgeClientNetwork.sendSetLimits(parseLong(singleField.getText()), parseLong(totalField.getText()));
    }

    private long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable t) {
            return 0L;
        }
    }

    @Override
    public void drawBackgroundOverride(DrawBackgroundArgs args) {
        // 纯代码绘制，不调 super（背景贴图缺失时避免 GL 报错）
        MatrixStack matrices = args.drawObjectDM.getStack();
        int x = this.x;
        int y = this.y;

        // 浅灰面板 + 描边
        DrawableHelper.fill(matrices, x, y, x + BG_W, y + BG_H, PANEL);
        drawBorder(matrices, x, y, BG_W, BG_H, PANEL_LINE);
        // 顶部标题条：与 1.20.1 的 fillGradient 同款竖向渐变
        // （1.19.2 的 DrawableHelper.fillGradient 是 protected，跨包不可调用，见 GuiRenderUtil）
        GuiRenderUtil.fillVerticalGradient(matrices, x + 1, y + 1, x + BG_W - 1, y + 12, 0xFFD2D2D2, PANEL);

        // 标题
        DrawableHelper.drawCenteredTextWithShadow(matrices, this.textRenderer, this.title.asOrderedText(),
                this.width / 2, y - 8, TEXT_MAIN);

        // 卡槽底框（含玩家物品栏；属性/绑定页盖住第二槽）。
        // 原版槽位绘制惯例：18x18 底框以槽位坐标为左上角向左上偏移 1px，使 16x16 物品居中
        boolean single = currentTab != TAB_COMBINE;
        for (Slot s : this.handler.slots) {
            if (single && s.id == 1) continue;
            drawSlotBox(matrices, x + s.x - 1, y + s.y - 1, s.id < 2);
        }

        // 空卡槽画卡片轮廓示意（与分解器一致），指明「卡放这里」
        for (Slot s : this.handler.slots) {
            if (s.id >= 2) break;
            if (s.getStack().isEmpty()) {
                int cx = x + s.x + 3;
                int cy = y + s.y + 2;
                DrawableHelper.fill(matrices, cx, cy, cx + 10, cy + 12, 0xFFAEB8C2);
                DrawableHelper.fill(matrices, cx + 1, cy + 1, cx + 3, cy + 3, ACCENT_DARK);
                DrawableHelper.fill(matrices, cx + 2, cy + 8, cx + 8, cy + 9, 0xFF7B8794);
            }
        }

        // 各页附加信息（画在背景层，避开槽位与控件区域；静态说明已移至按钮 tooltip，此处只留状态反馈）
        switch (currentTab) {
            case TAB_ATTR -> {
                if (!hasCard()) {
                    DrawableHelper.drawCenteredTextWithShadow(matrices, this.textRenderer,
                            Text.translatable("itemalchemy-expansion.card_forge.no_card").asOrderedText(),
                            this.width / 2, y + 112, TEXT_DIM);
                }
            }
            case TAB_COMBINE -> {
                if (isCardLinked()) {
                    DrawableHelper.drawCenteredTextWithShadow(matrices, this.textRenderer,
                            Text.translatable("itemalchemy-expansion.card_forge.link.current").asOrderedText(),
                            this.width / 2, y + 112, ACCENT_DARK);
                } else if (this.handler.slots.get(0).getStack().isEmpty()
                        && this.handler.slots.get(1).getStack().isEmpty()) {
                    DrawableHelper.drawCenteredTextWithShadow(matrices, this.textRenderer,
                            Text.translatable("itemalchemy-expansion.card_forge.no_card").asOrderedText(),
                            this.width / 2, y + 112, TEXT_DIM);
                } else if (this.handler.slots.get(0).getStack().isEmpty()
                        || this.handler.slots.get(1).getStack().isEmpty()) {
                    DrawableHelper.drawCenteredTextWithShadow(matrices, this.textRenderer,
                            Text.translatable("itemalchemy-expansion.card_forge.link.need_two").asOrderedText(),
                            this.width / 2, y + 112, TEXT_DIM);
                }
            }
            case TAB_BIND -> drawBindInfo(matrices, y);
        }
    }

    /** 绑定页：当前绑定状态提示（已绑定时绘制，未绑定时由输入框 placeholder 提示） */
    private void drawBindInfo(MatrixStack matrices, int y) {
        if (!hasCard() || !isCardBound()) return;
        ItemStack card = this.handler.slots.get(0).getStack();
        long single = EmcCardItem.getBindSingleLimit(card);
        long total = EmcCardItem.getBindTotalLimit(card);
        String name = resolveBoundName(card);
        DrawableHelper.drawCenteredTextWithShadow(matrices, this.textRenderer,
                Text.translatable("itemalchemy-expansion.card_forge.bind.bound_success", Text.literal(name)).asOrderedText(),
                this.width / 2, y + 64, ACCENT_DARK);
        DrawableHelper.drawCenteredTextWithShadow(matrices, this.textRenderer,
                Text.translatable("itemalchemy-expansion.card_forge.bind.limits_display",
                        Text.literal(EmcCardItem.formatNumber(single)),
                        Text.literal(EmcCardItem.formatNumber(total))).asOrderedText(),
                this.width / 2, y + 76, TEXT_DIM);
    }

    /** 解析绑卡显示名：卡上存的绑定名（离线可读）> 在线玩家档案 > UUID 截断 */
    private String resolveBoundName(ItemStack card) {
        String stored = EmcCardItem.getBindName(card);
        if (stored != null && !stored.isEmpty()) return stored;
        String uuid = EmcCardItem.getBindUuid(card);
        if (uuid == null) return "";
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc != null && mc.getNetworkHandler() != null) {
                var entry = mc.getNetworkHandler().getPlayerListEntry(java.util.UUID.fromString(uuid));
                if (entry != null && entry.getProfile() != null) {
                    return entry.getProfile().getName();
                }
            }
        } catch (Throwable t) {
            // fallthrough
        }
        return uuid.length() > 8 ? uuid.substring(0, 8) : uuid;
    }

    @Override
    protected void drawForegroundOverride(DrawForegroundArgs args) {
        // 标题已在 drawBackgroundOverride 居中绘制，跳过原版 foreground（否则标题/物品栏标签被二次绘制，产生重影与重叠）

        // 属性/绑定页：盖住组合专用第二槽。原版已按 (x, y) 平移矩阵，此处用槽位相对坐标；
        // 必须画在物品层之后、tooltip 之前（放在 renderOverride 里会盖住原版物品提示框）
        if (currentTab != TAB_COMBINE) {
            Slot s1 = this.handler.slots.get(1);
            if (s1 != null) {
                DrawableHelper.fill(args.drawObjectDM.getStack(), s1.x - 1, s1.y - 1, s1.x + 17, s1.y + 17, PANEL);
            }
        }
    }

    @Override
    public void renderOverride(RenderArgs args) {
        // 按卡态细化绑定页控件显隐与文案
        boolean bound = isCardBound();
        if (currentTab == TAB_BIND) {
            btnBind.setMessage(Text.translatable(bound
                    ? "itemalchemy-expansion.card_forge.bind.unbind"
                    : "itemalchemy-expansion.card_forge.bind"));
            btnBind.setTooltipText(Text.translatable(bound
                    ? "itemalchemy-expansion.card_forge.bind.unbind.tooltip"
                    : "itemalchemy-expansion.card_forge.bind.tooltip"));
            // 下拉展开时把绑定按钮移到下拉下方，避免被候选列表遮挡无法点击
            int bindY = bound ? 88 : (dropdownOpen() ? 130 : 84);
            btnBind.y = (this.height - BG_H) / 2 + bindY;
            nameField.setVisible(!bound);
            singleField.setVisible(bound);
            totalField.setVisible(bound);
            btnApplyLimits.visible = bound;
        }
        // 解除关联按钮仅当左槽卡已关联时可见
        btnUnlink.visible = currentTab == TAB_COMBINE && isCardLinked();

        super.renderOverride(args);

        MatrixStack matrices = args.drawObjectDM.getStack();
        // 绑定页：输入框占位提示（1.19.2 无 TextFieldWidget.setPlaceholder，手绘）
        if (currentTab == TAB_BIND) {
            drawPlaceholder(matrices, nameField, "itemalchemy-expansion.card_forge.bind.name_placeholder");
            drawPlaceholder(matrices, singleField, "itemalchemy-expansion.card_forge.bind.single_placeholder");
            drawPlaceholder(matrices, totalField, "itemalchemy-expansion.card_forge.bind.total_placeholder");
        }

        // 绑定页：玩家下拉候选列表（最上层绘制）
        drawPlayerDropdown(matrices, args.mouseX, args.mouseY);

        // 输入框悬停提示（1.19.2 的 TextFieldWidget 无 setTooltip，手绘；最后绘制保证在最上层）
        if (currentTab == TAB_BIND) {
            drawFieldTooltip(matrices, nameField, NAME_FIELD_W,
                    "itemalchemy-expansion.card_forge.bind.name.tooltip", args.mouseX, args.mouseY);
            drawFieldTooltip(matrices, singleField, LIMIT_FIELD_W,
                    "itemalchemy-expansion.card_forge.bind.single.tooltip", args.mouseX, args.mouseY);
            drawFieldTooltip(matrices, totalField, LIMIT_FIELD_W,
                    "itemalchemy-expansion.card_forge.bind.total.tooltip", args.mouseX, args.mouseY);
        }

        // 按钮悬停提示必须最后绘制：在 renderButton 里画会被同层后渲染的相邻控件盖住
        drawButtonTooltips(matrices, args.mouseX, args.mouseY);
    }

    /** 悬停按钮的提示文案：1.19.2 无 1.20 的 Tooltip 延迟绘制机制，改由界面在渲染末尾统一触发 */
    private void drawButtonTooltips(MatrixStack matrices, int mouseX, int mouseY) {
        ModernButton[] buttons = {btnPrivate, btnPublic, btnLink, btnMerge, btnUnlink, btnBind, btnApplyLimits};
        for (ModernButton b : buttons) {
            if (b.tooltipText == null || !b.isHovering(mouseX, mouseY)) continue;
            b.renderTooltipNow(matrices, mouseX, mouseY);
            return;
        }
    }

    /** 空输入框的灰色占位提示（对应 1.20.1 的 setPlaceholder） */
    private void drawPlaceholder(MatrixStack matrices, TextFieldWidget field, String key) {
        if (!field.isVisible() || !field.getText().isEmpty()) return;
        this.textRenderer.draw(matrices, Text.translatable(key),
                field.x + 4, field.y + (field.getHeight() - 8) / 2, 0xFF808080);
    }

    /** 输入框悬停提示（对应 1.20.1 的 TextFieldWidget.setTooltip） */
    private void drawFieldTooltip(MatrixStack matrices, TextFieldWidget field, int fieldWidth, String key,
                                  int mouseX, int mouseY) {
        if (!field.isVisible()) return;
        if (mouseX < field.x || mouseX >= field.x + fieldWidth) return;
        if (mouseY < field.y || mouseY >= field.y + field.getHeight()) return;
        this.renderTooltip(matrices, Text.translatable(key), mouseX, mouseY);
    }

    /** 下拉是否展开（聚焦或已有输入时显示，避免玩家不知道要先点输入框） */
    private boolean dropdownOpen() {
        return currentTab == TAB_BIND && !isCardBound()
                && nameField.isVisible()
                && (nameField.isFocused() || !nameField.getText().trim().isEmpty())
                && !playerCandidates().isEmpty();
    }

    /** 按输入内容模糊匹配在线玩家（前缀优先，最多 DROPDOWN_MAX_ROWS 个） */
    private List<String> playerCandidates() {
        List<String> all = CardForgeClientNetwork.onlinePlayers;
        String q = nameField.getText().trim().toLowerCase(Locale.ROOT);
        // 未输入时直接取前几项，避免每帧遍历 + 建临时列表
        if (q.isEmpty()) {
            return all.size() > DROPDOWN_MAX_ROWS ? new ArrayList<>(all.subList(0, DROPDOWN_MAX_ROWS)) : all;
        }
        List<String> starts = new ArrayList<>();
        List<String> contains = new ArrayList<>();
        for (String n : all) {
            String ln = n.toLowerCase(Locale.ROOT);
            if (ln.startsWith(q)) {
                starts.add(n);
            } else if (ln.contains(q)) {
                contains.add(n);
            }
        }
        starts.addAll(contains);
        return starts.size() > DROPDOWN_MAX_ROWS ? new ArrayList<>(starts.subList(0, DROPDOWN_MAX_ROWS)) : starts;
    }

    private void drawPlayerDropdown(MatrixStack matrices, int mouseX, int mouseY) {
        if (!dropdownOpen()) return;
        List<String> cands = playerCandidates();
        if (cands.isEmpty()) return;
        int dx = nameField.x;
        int dy = nameField.y + nameField.getHeight();
        int dw = NAME_FIELD_W;
        int dh = cands.size() * DROPDOWN_ROW_H + 2;
        DrawableHelper.fill(matrices, dx, dy, dx + dw, dy + dh, 0xFFEDEDED);
        drawBorder(matrices, dx, dy, dw, dh, PANEL_LINE);
        for (int i = 0; i < cands.size(); i++) {
            int ry = dy + 1 + i * DROPDOWN_ROW_H;
            boolean hover = mouseX >= dx && mouseX < dx + dw
                    && mouseY >= ry && mouseY < ry + DROPDOWN_ROW_H;
            if (hover) {
                DrawableHelper.fill(matrices, dx + 1, ry, dx + dw - 1, ry + DROPDOWN_ROW_H, 0xFFB0D8D4);
            }
            this.textRenderer.draw(matrices, Text.literal(cands.get(i)), dx + 5, ry + 2, 0xFF303030);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 1) 玩家下拉候选点击优先
        if (button == 0 && dropdownOpen()) {
            List<String> cands = playerCandidates();
            int dx = nameField.x;
            int dy = nameField.y + nameField.getHeight();
            if (mouseX >= dx && mouseX < dx + NAME_FIELD_W
                    && mouseY >= dy && mouseY < dy + cands.size() * DROPDOWN_ROW_H + 2) {
                int idx = (int) ((mouseY - dy - 1) / DROPDOWN_ROW_H);
                if (idx >= 0 && idx < cands.size()) {
                    nameField.setText(cands.get(idx));
                    // 选中后失焦收起下拉，露出下方绑定按钮
                    nameField.setTextFieldFocused(false);
                    return true;
                }
            }
        }
        // 2) 属性/绑定页第二槽被面板遮挡，吞掉点击避免误操作隐藏槽位
        if (currentTab != TAB_COMBINE) {
            Slot s1 = this.handler.slots.get(1);
            if (s1 != null) {
                int sx = this.x + s1.x;
                int sy = this.y + s1.y;
                if (mouseX >= sx - 1 && mouseX < sx + 17 && mouseY >= sy - 1 && mouseY < sy + 17) {
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void drawSlotBox(MatrixStack matrices, int sx, int sy, boolean card) {
        DrawableHelper.fill(matrices, sx, sy, sx + 18, sy + 18, SLOT_BG);
        drawBorder(matrices, sx, sy, 18, 18, card ? ACCENT_DARK : PANEL_LINE);
        if (card) {
            DrawableHelper.fill(matrices, sx + 1, sy + 1, sx + 3, sy + 3, ACCENT);
        }
    }

    private void drawBorder(MatrixStack matrices, int x, int y, int w, int h, int color) {
        DrawableHelper.fill(matrices, x, y, x + w, y + 1, color);
        DrawableHelper.fill(matrices, x, y + h - 1, x + w, y + h, color);
        DrawableHelper.fill(matrices, x, y, x + 1, y + h, color);
        DrawableHelper.fill(matrices, x + w - 1, y, x + w, y + h, color);
    }

    /**
     * 现代风格按钮（浅色系）：激活标签浅底 + 青色下划线高亮，普通按钮走 MC 灰。
     * 注意：tabActive 与 {@link ButtonWidget#active}（可用性）是两个独立状态。
     *
     * <p>1.19.2 无 {@code Tooltip} 类，提示文案改由 {@link #setTooltipText} 维护、
     * 经 {@code TooltipSupplier} 回调交回外层界面绘制。</p>
     */
    private class ModernButton extends ButtonWidget {
        private boolean tabActive;
        private Text tooltipText;

        public ModernButton(int x, int y, int width, int height, Text message, PressAction onPress, Text tooltip) {
            super(x, y, width, height, message, onPress, (button, matrices, mouseX, mouseY) -> {
                ModernButton b = (ModernButton) button;
                // 必须限定 CardForgeScreen.this：ModernButton 继承的同名 renderTooltip 会遮蔽外层方法
                if (b.tooltipText != null) CardForgeScreen.this.renderTooltip(matrices, b.tooltipText, mouseX, mouseY);
            });
            this.tooltipText = tooltip;
        }

        public void setTabActive(boolean tabActive) {
            this.tabActive = tabActive;
        }

        public void setTooltipText(Text tooltipText) {
            this.tooltipText = tooltipText;
        }

        @Override
        public void renderButton(MatrixStack matrices, int mouseX, int mouseY, float delta) {
            int bg;
            if (tabActive) {
                bg = 0xFFE4E4E4;
            } else if (this.isHovered()) {
                bg = 0xFFBDBDBD;
            } else {
                bg = 0xFF9E9E9E;
            }
            DrawableHelper.fill(matrices, this.x, this.y, this.x + this.width, this.y + this.height, bg);
            DrawableHelper.fill(matrices, this.x, this.y, this.x + this.width, this.y + 1, PANEL_LINE);
            DrawableHelper.fill(matrices, this.x, this.y + this.height - 1,
                    this.x + this.width, this.y + this.height, tabActive ? ACCENT : PANEL_LINE);
            DrawableHelper.fill(matrices, this.x, this.y, this.x + 1, this.y + this.height, PANEL_LINE);
            DrawableHelper.fill(matrices, this.x + this.width - 1, this.y, this.x + this.width, this.y + this.height, PANEL_LINE);
            int tc = this.active ? 0xFFFFFFFF : 0xFFA0A0A0;
            // 1.19.2 的 ButtonWidget 无 drawMessage，按原版居中方式自绘；
            // 提示框不在此处画（会被后渲染的控件盖住），改由外层 drawButtonTooltips 统一触发
            DrawableHelper.drawCenteredTextWithShadow(matrices, MinecraftClient.getInstance().textRenderer,
                    this.getMessage().asOrderedText(),
                    this.x + this.width / 2, this.y + (this.height - 8) / 2, tc);
        }

        /** 鼠标是否落在本按钮内（width/height 是父类 protected 字段，只能由子类内部判断） */
        public boolean isHovering(int mouseX, int mouseY) {
            return this.visible && mouseX >= this.x && mouseY >= this.y
                    && mouseX < this.x + this.width && mouseY < this.y + this.height;
        }

        /** 触发构造器传入的 TooltipSupplier（1.19.2 由 renderButton 调用，此处改由外层在渲染末尾调用） */
        public void renderTooltipNow(MatrixStack matrices, int mouseX, int mouseY) {
            this.renderTooltip(matrices, mouseX, mouseY);
        }
    }
}
