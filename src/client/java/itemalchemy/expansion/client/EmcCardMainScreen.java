package itemalchemy.expansion.client;

import itemalchemy.expansion.client.util.GuiRenderUtil;
import itemalchemy.expansion.item.EmcCardItem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;
import net.pitan76.itemalchemy.ItemAlchemyClient;

/**
 * EMC 卡主菜单：显示卡片价值/存储/总计与玩家 EMC，提供「充入 / 拿取 / 关闭」入口。
 *
 * <p>右键卡时服务端发 S2C 信号，客户端打开本界面。
 * 卡内 EMC 从 {@code mc.player.getMainHandStack()} NBT 实时读取，
 * 玩家 EMC 从上游 {@link ItemAlchemyClient#itemAlchemyNbt} 的 {@code team.emc} 读取。</p>
 */
public class EmcCardMainScreen extends Screen {

    private static final int PANEL_WIDTH = 240;
    private static final int PADDING = 14;
    private static final int LINE_HEIGHT = 16;

    public EmcCardMainScreen() {
        super(getCardName());
    }

    /** 读取主手卡的实际显示名称（含铁砧重命名），未持卡时回退默认标题 */
    public static Text getCardName() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null)
            return Text.translatable("itemalchemy-expansion.emc_card.title");
        ItemStack mainHand = mc.player.getMainHandStack();
        if (mainHand.isEmpty() || !(mainHand.getItem() instanceof EmcCardItem))
            return Text.translatable("itemalchemy-expansion.emc_card.title");
        return mainHand.getName();
    }

    @Override
    public boolean shouldPause() {
        // 不暂停：GUI 与服务端实时通信（充入/拿取），暂停会阻塞服务端包处理
        return false;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int panelTop = this.height / 2 - 100;
        int btnY = panelTop + 100;

        int btnWidth = 96;
        int gap = 8;
        // 第一行：充入 / 拿取
        addDrawableChild(ButtonWidget.builder(
                Text.translatable("itemalchemy-expansion.emc_card.deposit"),
                b -> MinecraftClient.getInstance().setScreen(new EmcCardDepositScreen()))
                .dimensions(centerX - btnWidth - gap / 2, btnY, btnWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(
                Text.translatable("itemalchemy-expansion.emc_card.withdraw"),
                b -> MinecraftClient.getInstance().setScreen(new EmcCardWithdrawScreen()))
                .dimensions(centerX + gap / 2, btnY, btnWidth, 20).build());

        // 第二行：设置 / 记录
        addDrawableChild(ButtonWidget.builder(
                Text.translatable("itemalchemy-expansion.emc_card.config"),
                b -> MinecraftClient.getInstance().setScreen(new EmcCardConfigScreen()))
                .dimensions(centerX - btnWidth - gap / 2, btnY + 24, btnWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(
                Text.translatable("itemalchemy-expansion.emc_card.log"),
                b -> MinecraftClient.getInstance().setScreen(new EmcCardLogScreen()))
                .dimensions(centerX + gap / 2, btnY + 24, btnWidth, 20).build());

        // 第三行：关闭
        addDrawableChild(ButtonWidget.builder(
                Text.translatable("itemalchemy-expansion.emc_card.close"),
                b -> this.close())
                .dimensions(centerX - 60, btnY + 48, 120, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context);

        int centerX = this.width / 2;
        int panelLeft = centerX - PANEL_WIDTH / 2;
        int panelTop = this.height / 2 - 100;
        int panelHeight = 200;

        // 面板背景：深蓝紫色调
        context.fill(panelLeft, panelTop, panelLeft + PANEL_WIDTH, panelTop + panelHeight, 0xE0101420);
        GuiRenderUtil.drawBorder(context, panelLeft, panelTop, PANEL_WIDTH, panelHeight, 0xFF5060A0);

        // 标题
        context.drawCenteredTextWithShadow(this.textRenderer, this.title,
                centerX, panelTop + PADDING, 0xFFE0E0FF);

        // 标题下装饰线
        int lineY = panelTop + PADDING + 12;
        context.fill(panelLeft + PADDING, lineY, panelLeft + PANEL_WIDTH - PADDING, lineY + 1, 0xFF405080);

        long baseEmc = EmcCardItem.getBaseEmc();
        long cardEmc = getCardEmc();
        long playerEmc = getPlayerEmc();
        long total = baseEmc + cardEmc;

        int dataY = lineY + 10;

        // 数据行：标签左对齐，数值右对齐
        drawDataRow(context, "itemalchemy-expansion.emc_card.base_emc",
                EmcCardItem.formatNumber(baseEmc), panelLeft, dataY, 0xFFC0A040, 0xFFE0B050);
        drawDataRow(context, "itemalchemy-expansion.emc_card.card_emc",
                EmcCardItem.formatNumber(cardEmc), panelLeft, dataY + LINE_HEIGHT, 0xFF40A0FF, 0xFF60C0FF);
        drawDataRow(context, "itemalchemy-expansion.emc_card.total_emc",
                EmcCardItem.formatNumber(total), panelLeft, dataY + LINE_HEIGHT * 2, 0xFF40E060, 0xFF60FF80);

        // 分隔线
        int divY = dataY + LINE_HEIGHT * 3 + 4;
        context.fill(panelLeft + PADDING, divY, panelLeft + PANEL_WIDTH - PADDING, divY + 1, 0xFF405080);

        // 玩家 EMC
        drawDataRow(context, "itemalchemy-expansion.emc_card.player_emc",
                EmcCardItem.formatNumber(playerEmc), panelLeft, divY + 6, 0xFFC0C0C0, 0xFFFFFF55);

        super.render(context, mouseX, mouseY, delta);
    }

    /** 绘制一行数据：左侧标签（灰），右侧数值（带颜色） */
    private void drawDataRow(DrawContext context, String labelKey, String valueStr,
                             int panelLeft, int y, int labelColor, int valueColor) {
        Text label = Text.translatable(labelKey);
        Text value = Text.literal(valueStr);
        context.drawText(this.textRenderer, label,
                panelLeft + PADDING, y, labelColor, false);
        context.drawText(this.textRenderer, value,
                panelLeft + PANEL_WIDTH - PADDING - textRenderer.getWidth(value), y, valueColor, false);
    }

    /** 当前主手卡内 EMC（实时读取，反映服务端同步后的变化）。 */
    private long getCardEmc() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null) return 0;
        ItemStack mainHand = mc.player.getMainHandStack();
        if (mainHand.isEmpty() || !(mainHand.getItem() instanceof EmcCardItem)) return 0;
        return EmcCardItem.getStoredEmc(mainHand);
    }

    /** 当前玩家 Team EMC（从上游客户端缓存的 team NBT 读取）。 */
    public static long getPlayerEmc() {
        try {
            NbtCompound root = ItemAlchemyClient.itemAlchemyNbt;
            if (root == null) return 0;
            NbtCompound team = root.getCompound("team");
            if (team == null) return 0;
            return team.getLong("emc");
        } catch (Throwable t) {
            return 0;
        }
    }
}
