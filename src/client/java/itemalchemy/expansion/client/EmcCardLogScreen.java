package itemalchemy.expansion.client;

import itemalchemy.expansion.client.util.GuiRenderUtil;
import itemalchemy.expansion.item.EmcCardItem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.text.Text;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * EMC 卡交易记录界面：显示最近 {@link EmcCardItem#MAX_TRANSACTIONS} 笔充入/拿取记录。
 */
public class EmcCardLogScreen extends Screen {

    private static final int PANEL_WIDTH = 280;
    private static final int PADDING = 14;
    private static final int LINE_HEIGHT = 14;
    private static final SimpleDateFormat DATE_FMT = new SimpleDateFormat("MM-dd HH:mm");

    public EmcCardLogScreen() {
        super(EmcCardMainScreen.getCardName());
    }

    @Override
    public boolean shouldPause() { return false; }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int panelTop = this.height / 2 - 100;
        addDrawableChild(ButtonWidget.builder(
                Text.translatable("itemalchemy-expansion.emc_card.cancel"),
                b -> MinecraftClient.getInstance().setScreen(new EmcCardMainScreen()))
                .dimensions(centerX - 60, panelTop + 170, 120, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context);
        int centerX = this.width / 2;
        int panelLeft = centerX - PANEL_WIDTH / 2;
        int panelTop = this.height / 2 - 100;
        int panelHeight = 200;

        context.fill(panelLeft, panelTop, panelLeft + PANEL_WIDTH, panelTop + panelHeight, 0xE0101420);
        GuiRenderUtil.drawBorder(context, panelLeft, panelTop, PANEL_WIDTH, panelHeight, 0xFF5060A0);

        context.drawCenteredTextWithShadow(this.textRenderer, this.title,
                centerX, panelTop + PADDING, 0xFFE0E0FF);
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.translatable("itemalchemy-expansion.emc_card.log.title"),
                centerX, panelTop + PADDING + 11, 0xFF8080A0);

        int lineY = panelTop + PADDING + 23;
        context.fill(panelLeft + PADDING, lineY, panelLeft + PANEL_WIDTH - PADDING, lineY + 1, 0xFF405080);

        ItemStack card = getCardStack();
        NbtList list = EmcCardItem.getTransactions(card);

        if (list.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.translatable("itemalchemy-expansion.emc_card.log.empty")
                            .formatted(net.minecraft.util.Formatting.GRAY),
                    centerX, lineY + 20, 0xFF808080);
        } else {
            int y = lineY + 8;
            // 倒序显示（最新在上）
            for (int i = list.size() - 1; i >= 0 && y < panelTop + panelHeight - 30; i--) {
                NbtCompound entry = list.getCompound(i);
                byte type = entry.getByte("type");
                long amount = entry.getLong("amount");
                long time = entry.getLong("time");

                String typeKey = type == EmcCardItem.TX_DEPOSIT
                        ? "itemalchemy-expansion.emc_card.log.deposit"
                        : "itemalchemy-expansion.emc_card.log.withdraw";
                int typeColor = type == EmcCardItem.TX_DEPOSIT ? 0xFF40E060 : 0xFFFF8040;

                String timeStr = DATE_FMT.format(new Date(time));
                Text line = Text.literal("§7" + timeStr + " §r")
                        .append(Text.translatable(typeKey).formatted(net.minecraft.util.Formatting.BOLD))
                        .append(" " + EmcCardItem.formatNumber(amount) + " EMC");

                context.drawText(this.textRenderer, line,
                        panelLeft + PADDING, y, typeColor, false);
                y += LINE_HEIGHT;
            }
        }

        super.render(context, mouseX, mouseY, delta);
    }

    private ItemStack getCardStack() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null) return ItemStack.EMPTY;
        ItemStack mainHand = mc.player.getMainHandStack();
        if (mainHand.isEmpty() || !(mainHand.getItem() instanceof EmcCardItem)) return ItemStack.EMPTY;
        return mainHand;
    }
}
