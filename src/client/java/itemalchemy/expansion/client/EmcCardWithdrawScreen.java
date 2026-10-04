package itemalchemy.expansion.client;

import itemalchemy.expansion.client.util.GuiRenderUtil;
import itemalchemy.expansion.item.EmcCardItem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * EMC 卡拿取界面：输入拿取数量，从卡内转入玩家 Team EMC。
 *
 * <p>提供「全拿 / 拿一半」快捷按钮。服务端再次校验卡内余额，不足时通过 actionbar 反馈。
 * 拿取后回到主菜单。</p>
 */
public class EmcCardWithdrawScreen extends Screen {

    private static final int PANEL_WIDTH = 260;
    private static final int PADDING = 14;
    private static final int LINE_HEIGHT = 16;

    private TextFieldWidget amountField;
    private Text errorText;

    public EmcCardWithdrawScreen() {
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
        int fieldY = this.height / 2 - 6;

        int fieldWidth = 180;
        amountField = new TextFieldWidget(this.textRenderer,
                centerX - fieldWidth / 2, fieldY, fieldWidth, 16,
                Text.translatable("itemalchemy-expansion.emc_card.amount_field"));
        amountField.setMaxLength(18);
        amountField.setTextPredicate(this::isNumeric);
        amountField.setText("");
        addDrawableChild(amountField);
        this.setFocused(amountField);

        // 快捷按钮：全拿 / 拿一半
        int quickY = fieldY + 22;
        int quickWidth = 88;
        int gap = 4;
        addDrawableChild(new ButtonWidget(
                centerX - quickWidth - gap / 2, quickY, quickWidth, 16,
                Text.translatable("itemalchemy-expansion.emc_card.withdraw.all"),
                b -> amountField.setText(String.valueOf(getCardEmc()))));
        addDrawableChild(new ButtonWidget(
                centerX + gap / 2, quickY, quickWidth, 16,
                Text.translatable("itemalchemy-expansion.emc_card.withdraw.half"),
                b -> amountField.setText(String.valueOf(getCardEmc() / 2))));

        int btnY = quickY + 24;
        int btnWidth = 84;
        int gap2 = 8;
        addDrawableChild(new ButtonWidget(
                centerX - btnWidth - gap2 / 2, btnY, btnWidth, 20,
                Text.translatable("itemalchemy-expansion.emc_card.confirm"),
                b -> onConfirm()));
        addDrawableChild(new ButtonWidget(
                centerX + gap2 / 2, btnY, btnWidth, 20,
                Text.translatable("itemalchemy-expansion.emc_card.cancel"),
                b -> MinecraftClient.getInstance().setScreen(new EmcCardMainScreen())));
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
        long cardEmc = getCardEmc();
        if (amount > cardEmc) {
            errorText = Text.translatable("itemalchemy-expansion.emc_card.withdraw.fail.insufficient",
                    Text.literal(EmcCardItem.formatNumber(cardEmc))).formatted(Formatting.RED);
            return;
        }
        EmcCardClientNetwork.sendWithdraw(amount);
        MinecraftClient.getInstance().setScreen(new EmcCardMainScreen());
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        renderBackground(matrices);

        int centerX = this.width / 2;
        int panelLeft = centerX - PANEL_WIDTH / 2;
        int panelTop = this.height / 2 - 98;
        int panelHeight = 196;

        DrawableHelper.fill(matrices, panelLeft, panelTop, panelLeft + PANEL_WIDTH, panelTop + panelHeight, 0xE0101420);
        GuiRenderUtil.drawBorder(matrices, panelLeft, panelTop, PANEL_WIDTH, panelHeight, 0xFF5060A0);

        DrawableHelper.drawCenteredTextWithShadow(matrices, this.textRenderer, this.title.asOrderedText(),
                centerX, panelTop + PADDING, 0xFFE0E0FF);
        // 副标题：操作类型
        DrawableHelper.drawCenteredTextWithShadow(matrices, this.textRenderer,
                Text.translatable("itemalchemy-expansion.emc_card.withdraw.title").asOrderedText(),
                centerX, panelTop + PADDING + 11, 0xFF8080A0);

        int lineY = panelTop + PADDING + 23;
        DrawableHelper.fill(matrices, panelLeft + PADDING, lineY, panelLeft + PANEL_WIDTH - PADDING, lineY + 1, 0xFF405080);

        long cardEmc = getCardEmc();
        long playerEmc = EmcCardMainScreen.getPlayerEmc();

        int dataY = lineY + 10;
        drawDataRow(matrices, "itemalchemy-expansion.emc_card.card_emc",
                EmcCardItem.formatNumber(cardEmc), panelLeft, dataY, 0xFF40A0FF, 0xFF60C0FF);
        drawDataRow(matrices, "itemalchemy-expansion.emc_card.player_emc",
                EmcCardItem.formatNumber(playerEmc), panelLeft, dataY + LINE_HEIGHT, 0xFFC0C0C0, 0xFFFFFF55);

        Text fieldLabel = Text.translatable("itemalchemy-expansion.emc_card.withdraw.field_label")
                .formatted(Formatting.GRAY);
        this.textRenderer.draw(matrices, fieldLabel,
                panelLeft + PADDING, amountField.y - 12, 0xFFA0A0C0);

        if (errorText != null) {
            DrawableHelper.drawCenteredTextWithShadow(matrices, this.textRenderer, errorText.asOrderedText(),
                    centerX, amountField.y + 82, 0xFFFF5555);
        }

        super.render(matrices, mouseX, mouseY, delta);
    }

    private void drawDataRow(MatrixStack matrices, String labelKey, String valueStr,
                             int panelLeft, int y, int labelColor, int valueColor) {
        Text label = Text.translatable(labelKey);
        Text value = Text.literal(valueStr);
        this.textRenderer.draw(matrices, label,
                panelLeft + PADDING, y, labelColor);
        this.textRenderer.draw(matrices, value,
                panelLeft + PANEL_WIDTH - PADDING - textRenderer.getWidth(value), y, valueColor);
    }

    private long getCardEmc() {
        return EmcCardClientNetwork.getCardBalance();
    }
}
