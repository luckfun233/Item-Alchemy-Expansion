package itemalchemy.expansion.client;

import itemalchemy.expansion.client.util.GuiRenderUtil;
import itemalchemy.expansion.item.EmcCardItem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * EMC 卡充入界面：输入充入数量，从玩家 Team EMC 转入卡内。
 *
 * <p>显示玩家当前 EMC 与卡内 EMC，输入数量后确认。服务端再次校验余额，
 * 不足时通过 actionbar 反馈。充入后回到主菜单（卡内 EMC 已刷新）。</p>
 */
public class EmcCardDepositScreen extends Screen {

    private static final int PANEL_WIDTH = 260;
    private static final int PADDING = 14;
    private static final int LINE_HEIGHT = 16;

    private TextFieldWidget amountField;
    private Text errorText;

    public EmcCardDepositScreen() {
        super(EmcCardMainScreen.getCardName());
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    protected void init() {
        errorText = null;
        int centerX = this.width / 2;
        int fieldY = this.height / 2 + 2;

        int fieldWidth = 180;
        amountField = new TextFieldWidget(this.textRenderer,
                centerX - fieldWidth / 2, fieldY, fieldWidth, 16,
                Text.translatable("itemalchemy-expansion.emc_card.amount_field"));
        amountField.setMaxLength(18);
        amountField.setTextPredicate(this::isNumeric);
        amountField.setText("");
        addDrawableChild(amountField);
        this.setFocused(amountField);

        int btnY = fieldY + 28;
        int btnWidth = 84;
        int gap = 8;
        addDrawableChild(ButtonWidget.builder(
                Text.translatable("itemalchemy-expansion.emc_card.confirm"),
                b -> onConfirm())
                .dimensions(centerX - btnWidth - gap / 2, btnY, btnWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(
                Text.translatable("itemalchemy-expansion.emc_card.cancel"),
                b -> MinecraftClient.getInstance().setScreen(new EmcCardMainScreen()))
                .dimensions(centerX + gap / 2, btnY, btnWidth, 20).build());
    }

    private boolean isNumeric(String s) {
        if (s.isEmpty()) return true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    private void onConfirm() {
        String raw = amountField.getText().trim();
        if (raw.isEmpty()) {
            errorText = Text.translatable("itemalchemy-expansion.emc_card.fail.empty")
                    .formatted(Formatting.RED);
            return;
        }
        long amount;
        try {
            amount = Long.parseLong(raw);
        } catch (NumberFormatException e) {
            errorText = Text.translatable("itemalchemy-expansion.emc_card.fail.parse")
                    .formatted(Formatting.RED);
            return;
        }
        if (amount <= 0) {
            errorText = Text.translatable("itemalchemy-expansion.emc_card.fail.nonpositive")
                    .formatted(Formatting.RED);
            return;
        }
        long playerEmc = EmcCardMainScreen.getPlayerEmc();
        if (amount > playerEmc) {
            errorText = Text.translatable("itemalchemy-expansion.emc_card.deposit.fail.insufficient",
                    Text.literal(EmcCardItem.formatNumber(playerEmc))).formatted(Formatting.RED);
            return;
        }
        EmcCardClientNetwork.sendDeposit(amount);
        MinecraftClient.getInstance().setScreen(new EmcCardMainScreen());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context);

        int centerX = this.width / 2;
        int panelLeft = centerX - PANEL_WIDTH / 2;
        int panelTop = this.height / 2 - 90;
        int panelHeight = 180;

        context.fill(panelLeft, panelTop, panelLeft + PANEL_WIDTH, panelTop + panelHeight, 0xE0101420);
        GuiRenderUtil.drawBorder(context, panelLeft, panelTop, PANEL_WIDTH, panelHeight, 0xFF5060A0);

        context.drawCenteredTextWithShadow(this.textRenderer, this.title,
                centerX, panelTop + PADDING, 0xFFE0E0FF);
        // 副标题：操作类型
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.translatable("itemalchemy-expansion.emc_card.deposit.title"),
                centerX, panelTop + PADDING + 11, 0xFF8080A0);

        int lineY = panelTop + PADDING + 23;
        context.fill(panelLeft + PADDING, lineY, panelLeft + PANEL_WIDTH - PADDING, lineY + 1, 0xFF405080);

        long playerEmc = EmcCardMainScreen.getPlayerEmc();
        long cardEmc = getCardEmc();

        int dataY = lineY + 10;
        drawDataRow(context, "itemalchemy-expansion.emc_card.player_emc",
                EmcCardItem.formatNumber(playerEmc), panelLeft, dataY, 0xFFC0C0C0, 0xFFFFFF55);
        drawDataRow(context, "itemalchemy-expansion.emc_card.card_emc",
                EmcCardItem.formatNumber(cardEmc), panelLeft, dataY + LINE_HEIGHT, 0xFF40A0FF, 0xFF60C0FF);

        Text fieldLabel = Text.translatable("itemalchemy-expansion.emc_card.deposit.field_label")
                .formatted(Formatting.GRAY);
        context.drawText(this.textRenderer, fieldLabel,
                panelLeft + PADDING, amountField.getY() - 12, 0xFFA0A0C0, false);

        if (errorText != null) {
            context.drawCenteredTextWithShadow(this.textRenderer, errorText,
                    centerX, amountField.getY() + 52, 0xFF5555);
        }

        super.render(context, mouseX, mouseY, delta);
    }

    private void drawDataRow(DrawContext context, String labelKey, String valueStr,
                             int panelLeft, int y, int labelColor, int valueColor) {
        Text label = Text.translatable(labelKey);
        Text value = Text.literal(valueStr);
        context.drawText(this.textRenderer, label,
                panelLeft + PADDING, y, labelColor, false);
        context.drawText(this.textRenderer, value,
                panelLeft + PANEL_WIDTH - PADDING - textRenderer.getWidth(value), y, valueColor, false);
    }

    private long getCardEmc() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null) return 0;
        ItemStack mainHand = mc.player.getMainHandStack();
        if (mainHand.isEmpty() || !(mainHand.getItem() instanceof EmcCardItem)) return 0;
        return EmcCardItem.getStoredEmc(mainHand);
    }
}
