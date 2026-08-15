package itemalchemy.expansion.client;

import itemalchemy.expansion.ItemAlchemyExpansion;
import itemalchemy.expansion.gui.CardForgeScreenHandler;
import itemalchemy.expansion.item.EmcCardItem;
import itemalchemy.expansion.network.CardForgeNetwork;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.pitan76.mcpitanlib.api.client.gui.screen.SimpleInventoryScreen;
import net.pitan76.mcpitanlib.api.client.render.handledscreen.DrawBackgroundArgs;
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
                Text.translatable("itemalchemy-expansion.card_forge.tab.attr"), b -> switchTab(TAB_ATTR));
        tabCombineBtn = new ModernButton(cx - 26, top + 6, tabW, tabH,
                Text.translatable("itemalchemy-expansion.card_forge.tab.combine"), b -> switchTab(TAB_COMBINE));
        tabBindBtn = new ModernButton(cx + 32, top + 6, tabW, tabH,
                Text.translatable("itemalchemy-expansion.card_forge.tab.bind"), b -> switchTab(TAB_BIND));
        addDrawableChild_compatibility(tabAttrBtn);
        addDrawableChild_compatibility(tabCombineBtn);
        addDrawableChild_compatibility(tabBindBtn);

        // 属性页内容按钮
        int w = 70, h = 20;
        int rowY = top + 88;
        btnPrivate = new ModernButton(cx - w - 4, rowY, w, h,
                Text.translatable("itemalchemy-expansion.card_forge.private"),
                b -> CardForgeClientNetwork.sendAction(CardForgeNetwork.ACTION_SET_PRIVATE));
        btnPublic = new ModernButton(cx + 4, rowY, w, h,
                Text.translatable("itemalchemy-expansion.card_forge.public"),
                b -> CardForgeClientNetwork.sendAction(CardForgeNetwork.ACTION_SET_PUBLIC));

        // 组合页内容按钮（关联 / 合并 / 解除关联）
        int cw = 52;
        btnLink = new ModernButton(cx - 80, rowY, cw, h,
                Text.translatable("itemalchemy-expansion.card_forge.link"),
                b -> CardForgeClientNetwork.sendAction(CardForgeNetwork.ACTION_LINK));
        btnMerge = new ModernButton(cx - 24, rowY, cw, h,
                Text.translatable("itemalchemy-expansion.card_forge.merge"),
                b -> CardForgeClientNetwork.sendAction(CardForgeNetwork.ACTION_MERGE));
        btnUnlink = new ModernButton(cx + 32, rowY, cw, h,
                Text.translatable("itemalchemy-expansion.card_forge.unlink"),
                b -> CardForgeClientNetwork.sendAction(CardForgeNetwork.ACTION_UNLINK));
        addDrawableChild_compatibility(btnPrivate);
        addDrawableChild_compatibility(btnPublic);
        addDrawableChild_compatibility(btnLink);
        addDrawableChild_compatibility(btnMerge);
        addDrawableChild_compatibility(btnUnlink);

        // 绑定页内容：未绑定时显示 玩家名输入 + 绑定按钮 + 下拉候选；
        // 已绑定时显示 状态信息 + 解除绑定 + 限额输入 + 应用（renderOverride 按卡态切换）
        nameField = new TextFieldWidget(this.textRenderer, cx - 58, top + 62, 116, 14,
                Text.translatable("itemalchemy-expansion.card_forge.bind.name_field"));
        nameField.setMaxLength(16);
        nameField.setPlaceholder(Text.translatable("itemalchemy-expansion.card_forge.bind.name_placeholder"));
        singleField = new TextFieldWidget(this.textRenderer, cx - 58, top + 108, 40, 14,
                Text.translatable("itemalchemy-expansion.card_forge.bind.single_field"));
        singleField.setMaxLength(12);
        singleField.setPlaceholder(Text.translatable("itemalchemy-expansion.card_forge.bind.single_placeholder"));
        totalField = new TextFieldWidget(this.textRenderer, cx - 12, top + 108, 40, 14,
                Text.translatable("itemalchemy-expansion.card_forge.bind.total_field"));
        totalField.setMaxLength(12);
        totalField.setPlaceholder(Text.translatable("itemalchemy-expansion.card_forge.bind.total_placeholder"));
        btnBind = new ModernButton(cx - 58, top + 84, 116, 16,
                Text.translatable("itemalchemy-expansion.card_forge.bind"),
                b -> doBind());
        btnApplyLimits = new ModernButton(cx - 30, top + 127, 60, 16,
                Text.translatable("itemalchemy-expansion.card_forge.bind.apply"),
                b -> doApplyLimits());
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
        // 切到绑定页时刷新在线玩家列表（下拉数据源）
        if (tab == TAB_BIND) {
            CardForgeClientNetwork.sendRequestPlayers();
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
        super.drawBackgroundOverride(args);

        DrawContext ctx = args.drawObjectDM.getContext();
        int x = this.x;
        int y = this.y;

        // 浅灰面板 + 描边
        ctx.fill(x, y, x + BG_W, y + BG_H, PANEL);
        drawBorder(ctx, x, y, BG_W, BG_H, PANEL_LINE);
        // 顶部标题栏高光条
        ctx.fillGradient(x, y + 1, x + BG_W, y + 13, 0xFFD8D8D8, 0xFFC6C6C6);

        // 标题
        ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, y - 8, TEXT_MAIN);

        // 卡槽底框（含玩家物品栏；属性/绑定页盖住第二槽）。
        // 原版槽位绘制惯例：18x18 底框以槽位坐标为左上角向左上偏移 1px，使 16x16 物品居中
        boolean single = currentTab != TAB_COMBINE;
        for (Slot s : this.handler.slots) {
            if (single && s.id == 1) continue;
            drawSlotBox(ctx, x + s.x - 1, y + s.y - 1, s.id < 2);
        }

        // 各页附加信息（画在背景层，避开槽位与控件区域）
        switch (currentTab) {
            case TAB_ATTR -> {
                if (hasCard()) {
                    ctx.drawCenteredTextWithShadow(this.textRenderer,
                            Text.translatable("itemalchemy-expansion.card_forge.hint.attr"),
                            this.width / 2, y + 112, TEXT_DIM);
                }
            }
            case TAB_COMBINE -> {
                if (isCardLinked()) {
                    ctx.drawCenteredTextWithShadow(this.textRenderer,
                            Text.translatable("itemalchemy-expansion.card_forge.link.current"),
                            this.width / 2, y + 112, ACCENT_DARK);
                } else {
                    ctx.drawCenteredTextWithShadow(this.textRenderer,
                            Text.translatable("itemalchemy-expansion.card_forge.hint.combine"),
                            this.width / 2, y + 112, TEXT_DIM);
                }
            }
            case TAB_BIND -> drawBindInfo(ctx, y);
        }
    }

    /** 绑定页：当前绑定状态提示（已绑定时绘制，未绑定时由输入框 placeholder 提示） */
    private void drawBindInfo(DrawContext ctx, int y) {
        if (!hasCard() || !isCardBound()) return;
        ItemStack card = this.handler.slots.get(0).getStack();
        String uuid = EmcCardItem.getBindUuid(card);
        long single = EmcCardItem.getBindSingleLimit(card);
        long total = EmcCardItem.getBindTotalLimit(card);
        String name = resolvePlayerName(uuid);
        ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.translatable("itemalchemy-expansion.card_forge.bind.bound_success", Text.literal(name)),
                this.width / 2, y + 64, ACCENT_DARK);
        ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.translatable("itemalchemy-expansion.card_forge.bind.limits_display",
                        Text.literal(EmcCardItem.formatNumber(single)),
                        Text.literal(EmcCardItem.formatNumber(total))),
                this.width / 2, y + 76, TEXT_DIM);
    }

    private String resolvePlayerName(String uuid) {
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
    public void renderOverride(RenderArgs args) {
        // 按卡态细化绑定页控件显隐与文案
        boolean bound = isCardBound();
        if (currentTab == TAB_BIND) {
            btnBind.setMessage(Text.translatable(bound
                    ? "itemalchemy-expansion.card_forge.bind.unbind"
                    : "itemalchemy-expansion.card_forge.bind"));
            btnBind.setY((this.height - BG_H) / 2 + (bound ? 88 : 84));
            nameField.setVisible(!bound);
            singleField.setVisible(bound);
            totalField.setVisible(bound);
            btnApplyLimits.visible = bound;
        }
        // 解除关联按钮仅当左槽卡已关联时可见
        btnUnlink.visible = currentTab == TAB_COMBINE && isCardLinked();

        super.renderOverride(args);

        DrawContext ctx = args.drawObjectDM.getContext();
        // 属性/绑定页：用面板色盖住组合专用第二槽（坐标从槽位读取，避免硬编码错位）
        if (currentTab != TAB_COMBINE) {
            Slot s1 = this.handler.slots.get(1);
            if (s1 != null) {
                ctx.fill(this.x + s1.x - 1, this.y + s1.y - 1,
                        this.x + s1.x + 17, this.y + s1.y + 17, PANEL);
            }
        }

        // 绑定页：玩家下拉候选列表（最上层绘制）
        drawPlayerDropdown(ctx, args.getMouseX(), args.getMouseY());
    }

    /** 下拉是否展开 */
    private boolean dropdownOpen() {
        return currentTab == TAB_BIND && !isCardBound()
                && nameField.isVisible() && nameField.isFocused()
                && !playerCandidates().isEmpty();
    }

    /** 按输入内容模糊匹配在线玩家（前缀优先，最多 DROPDOWN_MAX_ROWS 个） */
    private List<String> playerCandidates() {
        List<String> all = CardForgeClientNetwork.onlinePlayers;
        String q = nameField.getText().trim().toLowerCase(Locale.ROOT);
        List<String> starts = new ArrayList<>();
        List<String> contains = new ArrayList<>();
        for (String n : all) {
            String ln = n.toLowerCase(Locale.ROOT);
            if (q.isEmpty() || ln.startsWith(q)) {
                starts.add(n);
            } else if (ln.contains(q)) {
                contains.add(n);
            }
        }
        starts.addAll(contains);
        return starts.size() > DROPDOWN_MAX_ROWS ? new ArrayList<>(starts.subList(0, DROPDOWN_MAX_ROWS)) : starts;
    }

    private void drawPlayerDropdown(DrawContext ctx, int mouseX, int mouseY) {
        if (!dropdownOpen()) return;
        List<String> cands = playerCandidates();
        if (cands.isEmpty()) return;
        int dx = nameField.getX();
        int dy = nameField.getY() + nameField.getHeight();
        int dw = 116;
        int dh = cands.size() * DROPDOWN_ROW_H + 2;
        ctx.fill(dx, dy, dx + dw, dy + dh, 0xFFEDEDED);
        drawBorder(ctx, dx, dy, dw, dh, PANEL_LINE);
        for (int i = 0; i < cands.size(); i++) {
            int ry = dy + 1 + i * DROPDOWN_ROW_H;
            boolean hover = mouseX >= dx && mouseX < dx + dw
                    && mouseY >= ry && mouseY < ry + DROPDOWN_ROW_H;
            if (hover) {
                ctx.fill(dx + 1, ry, dx + dw - 1, ry + DROPDOWN_ROW_H, 0xFFB0D8D4);
            }
            ctx.drawText(this.textRenderer, cands.get(i), dx + 5, ry + 2, 0xFF303030, false);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 1) 玩家下拉候选点击优先
        if (button == 0 && dropdownOpen()) {
            List<String> cands = playerCandidates();
            int dx = nameField.getX();
            int dy = nameField.getY() + nameField.getHeight();
            if (mouseX >= dx && mouseX < dx + 116 && mouseY >= dy && mouseY < dy + cands.size() * DROPDOWN_ROW_H + 2) {
                int idx = (int) ((mouseY - dy - 1) / DROPDOWN_ROW_H);
                if (idx >= 0 && idx < cands.size()) {
                    nameField.setText(cands.get(idx));
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

    private void drawSlotBox(DrawContext ctx, int sx, int sy, boolean card) {
        ctx.fill(sx, sy, sx + 18, sy + 18, SLOT_BG);
        drawBorder(ctx, sx, sy, 18, 18, card ? ACCENT_DARK : PANEL_LINE);
        if (card) {
            ctx.fill(sx + 1, sy + 1, sx + 3, sy + 3, ACCENT);
        }
    }

    private void drawBorder(DrawContext ctx, int x, int y, int w, int h, int color) {
        ctx.fill(x, y, x + w, y + 1, color);
        ctx.fill(x, y + h - 1, x + w, y + h, color);
        ctx.fill(x, y, x + 1, y + h, color);
        ctx.fill(x + w - 1, y, x + w, y + h, color);
    }

    /**
     * 现代风格按钮（浅色系）：激活标签浅底 + 青色下划线高亮，普通按钮走 MC 灰。
     * 注意：tabActive 与 {@link ButtonWidget#active}（可用性）是两个独立状态。
     */
    private static class ModernButton extends ButtonWidget {
        private boolean tabActive;

        public ModernButton(int x, int y, int width, int height, Text message, PressAction onPress) {
            super(x, y, width, height, message, onPress, DEFAULT_NARRATION_SUPPLIER);
        }

        public void setTabActive(boolean tabActive) {
            this.tabActive = tabActive;
        }

        @Override
        protected void renderButton(DrawContext context, int mouseX, int mouseY, float delta) {
            int bg;
            if (tabActive) {
                bg = 0xFFE4E4E4;
            } else if (this.isHovered()) {
                bg = 0xFFBDBDBD;
            } else {
                bg = 0xFF9E9E9E;
            }
            context.fill(this.getX(), this.getY(), this.getX() + this.width, this.getY() + this.height, bg);
            context.fill(this.getX(), this.getY(), this.getX() + this.width, this.getY() + 1, PANEL_LINE);
            context.fill(this.getX(), this.getY() + this.height - 1,
                    this.getX() + this.width, this.getY() + this.height, tabActive ? ACCENT : PANEL_LINE);
            context.fill(this.getX(), this.getY(), this.getX() + 1, this.getY() + this.height, PANEL_LINE);
            context.fill(this.getX() + this.width - 1, this.getY(), this.getX() + this.width, this.getY() + this.height, PANEL_LINE);
            int tc = this.active ? 0xFFFFFFFF : 0xFFA0A0A0;
            this.drawMessage(context, MinecraftClient.getInstance().textRenderer, tc);
        }
    }
}
