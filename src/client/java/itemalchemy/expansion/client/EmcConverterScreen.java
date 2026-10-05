package itemalchemy.expansion.client;

import itemalchemy.expansion.ItemAlchemyExpansion;
import itemalchemy.expansion.gui.EmcConverterScreenHandler;
import itemalchemy.expansion.item.EmcCardItem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawableHelper;
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

/**
 * EMC 分解器 GUI：4 输入槽 → 箭头 → EMC 卡槽 + 玩家物品栏，全部代码绘制（不依赖背景贴图）。
 */
public class EmcConverterScreen extends SimpleInventoryScreen<EmcConverterScreenHandler> {

    private static final int BG_W = 176;
    private static final int BG_H = 180;

    // 原版容器灰
    private static final int PANEL = 0xFFC6C6C6;
    private static final int PANEL_LINE = 0xFF555555;
    private static final int SLOT_BG = 0xFF8B8B8B;
    private static final int TEXT_MAIN = 0xFF404040;
    private static final int TEXT_DIM = 0xFF6E6E6E;
    private static final int ACCENT = 0xFF2EC4B6;
    private static final int ACCENT_DARK = 0xFF1E88A8;

    public EmcConverterScreen(EmcConverterScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
        setBackgroundWidth(BG_W);
        setBackgroundHeight(BG_H);
        EmcAutoClientNetwork.attachConverter(this);
    }

    /** 服务端推送的卡实时余额（-1 = 尚未收到） */
    private long serverBalance = -1L;
    private long lastBalanceReqTime = 0;

    /** 服务端心跳下发的实时卡余额 */
    public void onBalanceReceived(long balance) {
        this.serverBalance = balance;
    }

    /** 余额心跳：约每秒请求一次，自动化入账/他人操作后保持显示一致 */
    private void tickBalance() {
        var world = MinecraftClient.getInstance().world;
        if (world == null) return;
        long t = world.getTime();
        if (t - lastBalanceReqTime >= 20) {
            lastBalanceReqTime = t;
            EmcAutoClientNetwork.sendBalanceRequest();
        }
    }

    @Override
    public Identifier getTexture() {
        return new Identifier(ItemAlchemyExpansion.MOD_ID, "textures/gui/emc_converter");
    }

    @Override
    public void initOverride() {
        // 打开即请求一次卡余额（关联卡余额在服务端共享账户，客户端卡 NBT 无此数据）
        EmcAutoClientNetwork.sendBalanceRequest();
    }

    @Override
    public void renderOverride(RenderArgs args) {
        super.renderOverride(args);
        tickBalance();
    }

    @Override
    public void removedOverride() {
        EmcAutoClientNetwork.detachConverter(this);
        super.removedOverride();
    }

    @Override
    protected void drawForegroundOverride(DrawForegroundArgs args) {
        // 标题已在 drawBackgroundOverride 居中绘制，跳过原版 foreground（否则标题/物品栏标签被二次绘制，产生重影与重叠）
    }

    @Override
    public void drawBackgroundOverride(DrawBackgroundArgs args) {
        // 纯代码绘制，不调 super（背景贴图缺失时避免 GL 报错）
        MatrixStack matrices = args.drawObjectDM.getStack();
        int x = this.x;
        int y = this.y;

        // 面板 + 描边 + 顶部标题条
        DrawableHelper.fill(matrices, x, y, x + BG_W, y + BG_H, PANEL);
        drawBorder(matrices, x, y, BG_W, BG_H, PANEL_LINE);
        // 1.19.2 的 DrawableHelper.fillGradient 是 protected，跨包不可调用，改用顶部浅色条 + 分隔线
        DrawableHelper.fill(matrices, x + 1, y + 1, x + BG_W - 1, y + 12, 0xFFD2D2D2);
        DrawableHelper.fill(matrices, x + 1, y + 12, x + BG_W - 1, y + 13, PANEL_LINE);

        // 标题
        DrawableHelper.drawCenteredTextWithShadow(matrices, this.textRenderer, this.title.asOrderedText(),
                this.width / 2, y - 8, TEXT_MAIN);

        // 输入区标签 + 输入槽（1-4）
        this.textRenderer.draw(matrices,
                Text.translatable("itemalchemy-expansion.emc_converter.input_label"),
                x + 8, y + 8, TEXT_DIM);

        // 流向箭头：输入槽 -> 卡槽
        drawDownArrow(matrices, x + 86, y + 36, PANEL_LINE);

        // 全部槽位底框（含玩家物品栏/快捷栏，与制卡台一致；槽 0 为卡槽加青色点缀）
        for (Slot s : this.handler.slots) {
            drawSlotBox(matrices, x + s.x - 1, y + s.y - 1, s.id == 0);
        }

        // 空卡槽内画卡片轮廓示意，指明「卡放这里」
        Slot cardSlot = this.handler.slots.get(0);
        if (cardSlot.getStack().isEmpty()) {
            int cx = x + cardSlot.x + 3;
            int cy = y + cardSlot.y + 2;
            DrawableHelper.fill(matrices, cx, cy, cx + 10, cy + 12, 0xFFAEB8C2);
            DrawableHelper.fill(matrices, cx + 1, cy + 1, cx + 3, cy + 3, ACCENT_DARK);
            DrawableHelper.fill(matrices, cx + 2, cy + 8, cx + 8, cy + 9, 0xFF7B8794);
        }

        // 状态行：无卡提示 / 绑卡同步说明 / 卡内余额
        ItemStack card = this.handler.slots.get(0).getStack();
        if (card.isEmpty()) {
            DrawableHelper.drawCenteredTextWithShadow(matrices, this.textRenderer,
                    Text.translatable("itemalchemy-expansion.emc_converter.no_card").asOrderedText(),
                    this.width / 2, y + 75, TEXT_DIM);
        } else if (EmcCardItem.isBound(card)) {
            String name = EmcCardItem.getBindName(card);
            if (name == null || name.isEmpty()) name = EmcCardItem.getBindUuid(card);
            DrawableHelper.drawCenteredTextWithShadow(matrices, this.textRenderer,
                    Text.translatable("itemalchemy-expansion.emc_converter.bound_card",
                            Text.literal(name == null ? "?" : name)).asOrderedText(),
                    this.width / 2, y + 75, TEXT_DIM);
        } else {
            // 余额优先取服务端推送值：关联卡余额在服务端共享账户，客户端卡 NBT 读不到
            long shown = serverBalance >= 0 ? serverBalance : EmcCardItem.getStoredEmc(card);
            Text balance = Text.translatable("itemalchemy-expansion.emc_converter.card_balance",
                    Text.literal(EmcCardItem.formatNumber(shown)));
            DrawableHelper.drawCenteredTextWithShadow(matrices, this.textRenderer, balance.asOrderedText(),
                    this.width / 2, y + 75, ACCENT_DARK);
        }
        DrawableHelper.drawCenteredTextWithShadow(matrices, this.textRenderer,
                Text.translatable("itemalchemy-expansion.emc_converter.hint").asOrderedText(),
                this.width / 2, y + 85, TEXT_DIM);
    }

    /** 向下箭头（杆 + 逐行加宽的三角头） */
    private void drawDownArrow(MatrixStack matrices, int cx, int top, int color) {
        DrawableHelper.fill(matrices, cx, top, cx + 4, top + 8, color);
        for (int i = 0; i < 6; i++) {
            int half = i + 2;
            DrawableHelper.fill(matrices, cx + 2 - half, top + 8 + i, cx + 2 + half + 1, top + 9 + i, color);
        }
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
}
