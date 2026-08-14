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
import net.minecraft.util.Formatting;
import net.pitan76.itemalchemy.ItemAlchemyClient;

/**
 * EMC 卡主菜单：显示卡内 EMC 与玩家 EMC，提供「充入 / 拿取 / 关闭」入口。
 *
 * <p>右键卡时服务端发 S2C 信号，客户端打开本界面。数据来源：
 * <ul>
 *   <li>卡内 EMC：从 {@code mc.player.getMainHandStack()} 的 NBT 实时读取</li>
 *   <li>玩家 EMC：从上游 {@link ItemAlchemyClient#itemAlchemyNbt} 的 {@code team.emc} 读取</li>
 * </ul>
 * 充入/拿取操作后服务端会同步刷新这两个值。</p>
 */
public class EmcCardMainScreen extends Screen {

    private static final int PANEL_WIDTH = 220;
    private static final int PADDING = 12;

    public EmcCardMainScreen() {
        super(Text.translatable("itemalchemy-expansion.emc_card.title"));
    }

    @Override
    public boolean shouldPause() {
        return true;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int panelTop = this.height / 2 - 76;
        int btnY = panelTop + 76;

        int btnWidth = 92;
        int gap = 8;
        addDrawableChild(ButtonWidget.builder(
                Text.translatable("itemalchemy-expansion.emc_card.deposit"),
                b -> MinecraftClient.getInstance().setScreen(new EmcCardDepositScreen()))
                .dimensions(centerX - btnWidth - gap / 2, btnY, btnWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(
                Text.translatable("itemalchemy-expansion.emc_card.withdraw"),
                b -> MinecraftClient.getInstance().setScreen(new EmcCardWithdrawScreen()))
                .dimensions(centerX + gap / 2, btnY, btnWidth, 20).build());

        addDrawableChild(ButtonWidget.builder(
                Text.translatable("itemalchemy-expansion.emc_card.close"),
                b -> this.close())
                .dimensions(centerX - 60, btnY + 26, 120, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context);

        int centerX = this.width / 2;
        int panelLeft = centerX - PANEL_WIDTH / 2;
        int panelTop = this.height / 2 - 76;
        int panelHeight = 152;

        context.fill(panelLeft, panelTop, panelLeft + PANEL_WIDTH, panelTop + panelHeight, 0xC0101010);
        GuiRenderUtil.drawBorder(context, panelLeft, panelTop, PANEL_WIDTH, panelHeight, 0xFF404040);

        context.drawCenteredTextWithShadow(this.textRenderer, this.title,
                centerX, panelTop + PADDING, 0xFFFFFF);

        long cardEmc = getCardEmc();
        long playerEmc = getPlayerEmc();

        Text cardLabel = Text.translatable("itemalchemy-expansion.emc_card.card_emc")
                .formatted(Formatting.GRAY);
        context.drawText(this.textRenderer, cardLabel,
                panelLeft + PADDING, panelTop + 34, 0xA0A0A0, false);
        Text cardValue = Text.literal(EmcCardItem.formatNumber(cardEmc))
                .formatted(Formatting.AQUA);
        context.drawText(this.textRenderer, cardValue,
                panelLeft + PANEL_WIDTH - PADDING - textRenderer.getWidth(cardValue),
                panelTop + 34, 0x55FFFF, false);

        Text playerLabel = Text.translatable("itemalchemy-expansion.emc_card.player_emc")
                .formatted(Formatting.GRAY);
        context.drawText(this.textRenderer, playerLabel,
                panelLeft + PADDING, panelTop + 50, 0xA0A0A0, false);
        Text playerValue = Text.literal(EmcCardItem.formatNumber(playerEmc))
                .formatted(Formatting.YELLOW);
        context.drawText(this.textRenderer, playerValue,
                panelLeft + PANEL_WIDTH - PADDING - textRenderer.getWidth(playerValue),
                panelTop + 50, 0xFFFF55, false);

        // 分隔线
        context.fill(panelLeft + PADDING, panelTop + 66,
                panelLeft + PANEL_WIDTH - PADDING, panelTop + 67, 0xFF404040);

        super.render(context, mouseX, mouseY, delta);
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
