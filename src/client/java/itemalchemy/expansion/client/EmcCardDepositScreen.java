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

    private static final int PANEL_WIDTH = 240;
    private static final int PADDING = 12;

    private TextFieldWidget amountField;
    private Text errorText;

    public EmcCardDepositScreen() {
        super(Text.translatable("itemalchemy-expansion.emc_card.deposit.title"));
    }

    @Override
    public boolean shouldPause() {
        return true;
    }

    @Override
    protected void init() {
        errorText = null;
        int centerX = this.width / 2;
        int fieldY = this.height / 2 - 4;

        int fieldWidth = 160;
        amountField = new TextFieldWidget(this.textRenderer,
                centerX - fieldWidth / 2, fieldY, fieldWidth, 16,
                Text.translatable("itemalchemy-expansion.emc_card.amount_field"));
        amountField.setMaxLength(18);
        amountField.setTextPredicate(this::isNumeric);
        amountField.setText("");
        addDrawableChild(amountField);
        this.setFocused(amountField);

        int btnY = fieldY + 28;
        int btnWidth = 80;
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
        // 回主菜单：服务端同步后卡内 EMC 刷新
        MinecraftClient.getInstance().setScreen(new EmcCardMainScreen());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context);

        int centerX = this.width / 2;
        int panelLeft = centerX - PANEL_WIDTH / 2;
        int panelTop = this.height / 2 - 80;
        int panelHeight = 160;

        context.fill(panelLeft, panelTop, panelLeft + PANEL_WIDTH, panelTop + panelHeight, 0xC0101010);
        GuiRenderUtil.drawBorder(context, panelLeft, panelTop, PANEL_WIDTH, panelHeight, 0xFF404040);

        context.drawCenteredTextWithShadow(this.textRenderer, this.title,
                centerX, panelTop + PADDING, 0xFFFFFF);

        long playerEmc = EmcCardMainScreen.getPlayerEmc();
        long cardEmc = getCardEmc();

        Text playerLine = Text.translatable("itemalchemy-expansion.emc_card.player_emc",
                Text.literal(EmcCardItem.formatNumber(playerEmc)).formatted(Formatting.YELLOW));
        context.drawText(this.textRenderer, playerLine,
                panelLeft + PADDING, panelTop + 34, 0xFFFFFF, false);

        Text cardLine = Text.translatable("itemalchemy-expansion.emc_card.card_emc",
                Text.literal(EmcCardItem.formatNumber(cardEmc)).formatted(Formatting.AQUA));
        context.drawText(this.textRenderer, cardLine,
                panelLeft + PADDING, panelTop + 48, 0xFFFFFF, false);

        Text fieldLabel = Text.translatable("itemalchemy-expansion.emc_card.deposit.field_label")
                .formatted(Formatting.GRAY);
        context.drawText(this.textRenderer, fieldLabel,
                panelLeft + PADDING, amountField.getY() - 12, 0xC0C0C0, false);

        if (errorText != null) {
            context.drawCenteredTextWithShadow(this.textRenderer, errorText,
                    centerX, amountField.getY() + 52, 0xFF5555);
        }

        super.render(context, mouseX, mouseY, delta);
    }

    private long getCardEmc() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null) return 0;
        ItemStack mainHand = mc.player.getMainHandStack();
        if (mainHand.isEmpty() || !(mainHand.getItem() instanceof EmcCardItem)) return 0;
        return EmcCardItem.getStoredEmc(mainHand);
    }
}
