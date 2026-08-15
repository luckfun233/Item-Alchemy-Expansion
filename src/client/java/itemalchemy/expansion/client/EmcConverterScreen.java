package itemalchemy.expansion.client;

import itemalchemy.expansion.ItemAlchemyExpansion;
import itemalchemy.expansion.gui.EmcConverterScreenHandler;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.pitan76.mcpitanlib.api.client.gui.screen.SimpleInventoryScreen;
import net.pitan76.mcpitanlib.api.client.render.handledscreen.DrawBackgroundArgs;

/**
 * EMC 转能器 GUI：1 卡槽 + 4 输入槽，科技风面板。
 */
public class EmcConverterScreen extends SimpleInventoryScreen<EmcConverterScreenHandler> {

    private static final int BG_W = 176;
    private static final int BG_H = 166;

    // MC 经典浅灰 + 青色点缀
    private static final int PANEL = 0xFFC6C6C6;
    private static final int PANEL_LINE = 0xFF555555;
    private static final int SLOT_BG = 0xFF8B8B8B;
    private static final int ACCENT = 0xFF2EC4B6;
    private static final int ACCENT_DARK = 0xFF1E88A8;

    public EmcConverterScreen(EmcConverterScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
        setBackgroundWidth(BG_W);
        setBackgroundHeight(BG_H);
    }

    @Override
    public Identifier getTexture() {
        return new Identifier(ItemAlchemyExpansion.MOD_ID, "textures/gui/emc_converter");
    }

    @Override
    public void initOverride() {
        // nothing extra
    }

    @Override
    public void drawBackgroundOverride(DrawBackgroundArgs args) {
        super.drawBackgroundOverride(args);

        DrawContext ctx = args.drawObjectDM.getContext();
        int x = this.x;
        int y = this.y;

        ctx.fill(x, y, x + BG_W, y + BG_H, PANEL);
        drawBorder(ctx, x, y, BG_W, BG_H, PANEL_LINE);
        ctx.fillGradient(x, y + 1, x + BG_W, y + 13, 0xFFD8D8D8, 0xFFC6C6C6);

        // 标题
        ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, y - 8, 0xFF404040);

        // 卡槽底框
        drawSlotBox(ctx, x + 80, y + 40, true);
        // 输入槽底框
        for (int i = 0; i < 4; i++) {
            drawSlotBox(ctx, x + 62 + i * 18, y + 17, false);
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
}