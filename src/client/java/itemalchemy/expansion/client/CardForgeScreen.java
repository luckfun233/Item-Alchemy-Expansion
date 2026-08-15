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

/**
 * 制卡台客户端 GUI：上方悬浮标签 + 现代科技风面板。
 *
 * <p>三个标签：「属性」（单槽设私有/公有）、「组合」（两槽关联/合并）、「绑定」
 * （单槽绑定指定玩家 EMC，可设单次/总额支出限额）。面板、标签、卡槽底框均由代码绘制。</p>
 */
public class CardForgeScreen extends SimpleInventoryScreen<CardForgeScreenHandler> {

    private static final int BG_W = 176;
    private static final int BG_H = 230;

    private static final int TAB_ATTR = 0;
    private static final int TAB_COMBINE = 1;
    private static final int TAB_BIND = 2;

    // 主题色
    private static final int ACCENT = 0xFF2EC4B6;
    private static final int ACCENT_DARK = 0xFF1E88A8;
    private static final int PANEL = 0xFF0D1117;
    private static final int PANEL_LINE = 0xFF2A333D;
    private static final int SLOT_BG = 0xFF161C24;
    private static final int TEXT_MAIN = 0xFFE6F1FF;
    private static final int TEXT_DIM = 0xFF7A8AA0;

    private int currentTab = TAB_ATTR;

    private ModernButton tabAttrBtn;
    private ModernButton tabCombineBtn;
    private ModernButton tabBindBtn;
    private ModernButton btnPrivate;
    private ModernButton btnPublic;
    private ModernButton btnLink;
    private ModernButton btnMerge;
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

        // 属性 / 组合 内容按钮
        int w = 70, h = 20;
        int rowY = top + 88;
        btnPrivate = new ModernButton(cx - w - 4, rowY, w, h,
                Text.translatable("itemalchemy-expansion.card_forge.private"),
                b -> CardForgeClientNetwork.sendAction(CardForgeNetwork.ACTION_SET_PRIVATE));
        btnPublic = new ModernButton(cx + 4, rowY, w, h,
                Text.translatable("itemalchemy-expansion.card_forge.public"),
                b -> CardForgeClientNetwork.sendAction(CardForgeNetwork.ACTION_SET_PUBLIC));
        btnLink = new ModernButton(cx - w - 4, rowY, w, h,
                Text.translatable("itemalchemy-expansion.card_forge.link"),
                b -> CardForgeClientNetwork.sendAction(CardForgeNetwork.ACTION_LINK));
        btnMerge = new ModernButton(cx + 4, rowY, w, h,
                Text.translatable("itemalchemy-expansion.card_forge.merge"),
                b -> CardForgeClientNetwork.sendAction(CardForgeNetwork.ACTION_MERGE));
        addDrawableChild_compatibility(btnPrivate);
        addDrawableChild_compatibility(btnPublic);
        addDrawableChild_compatibility(btnLink);
        addDrawableChild_compatibility(btnMerge);

        // 绑定标签内容
        nameField = new TextFieldWidget(this.textRenderer, cx - 58, top + 66, 116, 16,
                Text.translatable("itemalchemy-expansion.card_forge.bind.name_field"));
        nameField.setMaxLength(16);
        nameField.setPlaceholder(Text.translatable("itemalchemy-expansion.card_forge.bind.name_placeholder"));
        singleField = new TextFieldWidget(this.textRenderer, cx - 58, top + 110, 40, 16,
                Text.translatable("itemalchemy-expansion.card_forge.bind.single_field"));
        singleField.setMaxLength(12);
        singleField.setPlaceholder(Text.translatable("itemalchemy-expansion.card_forge.bind.single_placeholder"));
        totalField = new TextFieldWidget(this.textRenderer, cx - 12, top + 110, 40, 16,
                Text.translatable("itemalchemy-expansion.card_forge.bind.total_field"));
        totalField.setMaxLength(12);
        totalField.setPlaceholder(Text.translatable("itemalchemy-expansion.card_forge.bind.total_placeholder"));
        btnBind = new ModernButton(cx - 58, top + 86, 116, 18,
                Text.translatable("itemalchemy-expansion.card_forge.bind"),
                b -> doBind());
        btnApplyLimits = new ModernButton(cx + 34, top + 108, 44, 18,
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
    }

    /** 按当前标签切换内容按钮/字段显隐与卡槽数量 */
    private void applyTab() {
        boolean attr = currentTab == TAB_ATTR;
        boolean combine = currentTab == TAB_COMBINE;
        boolean bind = currentTab == TAB_BIND;

        tabAttrBtn.active = attr;
        tabCombineBtn.active = combine;
        tabBindBtn.active = bind;

        btnPrivate.visible = attr;
        btnPublic.visible = attr;
        btnLink.visible = combine;
        btnMerge.visible = combine;

        nameField.visible = bind;
        singleField.visible = bind;
        totalField.visible = bind;
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

        // 深色面板 + 科技描边
        ctx.fill(x, y, x + BG_W, y + BG_H, PANEL);
        drawBorder(ctx, x, y, BG_W, BG_H, PANEL_LINE);
        // 顶部标题栏高光条
        ctx.fillGradient(x, y + 1, x + BG_W, y + 13, 0xFF1C2A3A, 0xFF0D1117);

        // 标题
        ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, y - 8, 0xFFA8C8E8);

        // 卡槽底框（含玩家物品栏；属性/绑定页盖住第二槽）
        boolean single = currentTab != TAB_COMBINE;
        for (Slot s : this.handler.slots) {
            if (single && s.id == 1) continue;
            drawSlotBox(ctx, x + s.x, y + s.y, s.id < 2);
        }

        // 绑定页附加信息
        if (currentTab == TAB_BIND) {
            drawBindInfo(ctx, x, y);
        }
    }

    /** 绑定页：当前绑定状态提示 */
    private void drawBindInfo(DrawContext ctx, int x, int y) {
        if (!hasCard()) {
            ctx.drawCenteredTextWithShadow(this.textRenderer,
                    Text.translatable("itemalchemy-expansion.card_forge.bind.no_card"),
                    this.width / 2, y + 130, TEXT_DIM);
            return;
        }
        ItemStack card = this.handler.slots.get(0).getStack();
        if (isCardBound()) {
            String uuid = EmcCardItem.getBindUuid(card);
            long single = EmcCardItem.getBindSingleLimit(card);
            long total = EmcCardItem.getBindTotalLimit(card);
            // 尝试解析玩家名（尽力而为，失败显示 UUID 前 8 位）
            String name = resolvePlayerName(uuid);
            ctx.drawCenteredTextWithShadow(this.textRenderer,
                    Text.translatable("itemalchemy-expansion.card_forge.bind.bound_success", Text.literal(name)),
                    this.width / 2, y + 130, 0xFF40A0FF);
            ctx.drawCenteredTextWithShadow(this.textRenderer,
                    Text.translatable("itemalchemy-expansion.card_forge.bind.limits_display",
                            Text.literal(EmcCardItem.formatNumber(single)),
                            Text.literal(EmcCardItem.formatNumber(total))),
                    this.width / 2, y + 142, TEXT_DIM);
        } else {
            ctx.drawCenteredTextWithShadow(this.textRenderer,
                    Text.translatable("itemalchemy-expansion.card_forge.bind.hint"),
                    this.width / 2, y + 130, TEXT_DIM);
        }
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
        // 绑定页：更新按钮文案（绑定/解除绑定）
        if (currentTab == TAB_BIND) {
            btnBind.setMessage(Text.translatable(isCardBound()
                    ? "itemalchemy-expansion.card_forge.bind.unbind"
                    : "itemalchemy-expansion.card_forge.bind"));
        }
        super.renderOverride(args);
        // 属性/绑定页：用面板色盖住组合专用第二槽，只显示一个卡槽
        if (currentTab != TAB_COMBINE) {
            DrawContext ctx = args.drawObjectDM.getContext();
            ctx.fill(this.x + 98, this.y + 44, this.x + 98 + 18, this.y + 44 + 18, PANEL);
        }
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

    /** 现代风格按钮：方形圆滑化 + 主题色，激活标签用高亮。 */
    private static class ModernButton extends ButtonWidget {
        private boolean active;

        public ModernButton(int x, int y, int width, int height, Text message, PressAction onPress) {
            super(x, y, width, height, message, onPress, DEFAULT_NARRATION_SUPPLIER);
        }

        @Override
        protected void renderButton(DrawContext context, int mouseX, int mouseY, float delta) {
            int bg, line;
            if (active) {
                bg = 0xFF1C2A3A;
                line = ACCENT;
            } else if (this.isHovered()) {
                bg = 0xFF1A2230;
                line = 0xFF4A5A6E;
            } else {
                bg = 0xFF141A22;
                line = PANEL_LINE;
            }
            context.fill(this.getX(), this.getY(), this.getX() + this.width, this.getY() + this.height, bg);
            context.fill(this.getX(), this.getY(), this.getX() + this.width, this.getY() + 1, line);
            if (active) {
                context.fill(this.getX(), this.getY() + this.height - 1,
                        this.getX() + this.width, this.getY() + this.height, ACCENT);
            }
            int tc = active ? TEXT_MAIN : (this.active ? TEXT_DIM : 0xFF556070);
            this.drawMessage(context, MinecraftClient.getInstance().textRenderer, tc);
        }
    }
}