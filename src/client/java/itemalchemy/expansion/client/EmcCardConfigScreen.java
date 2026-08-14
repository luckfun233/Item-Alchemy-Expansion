package itemalchemy.expansion.client;

import itemalchemy.expansion.client.util.GuiRenderUtil;
import itemalchemy.expansion.item.EmcCardItem;
import itemalchemy.expansion.network.EmcCardNetwork;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

/**
 * EMC 卡设置界面：快捷充能开关 + 金额配置。
 */
public class EmcCardConfigScreen extends Screen {

    private static final int PANEL_WIDTH = 260;
    private static final int PADDING = 14;

    private TextFieldWidget amountField;

    public EmcCardConfigScreen() {
        super(EmcCardMainScreen.getCardName());
    }

    @Override
    public boolean shouldPause() { return false; }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int panelTop = this.height / 2 - 90;
        ItemStack card = getCardStack();
        boolean enabled = EmcCardItem.isQuickChargeEnabled(card);

        // 快捷充能开关按钮
        addDrawableChild(ButtonWidget.builder(
                Text.translatable("itemalchemy-expansion.emc_card.config.quickcharge")
                        .append(": ")
                        .append(Text.translatable(enabled
                                ? "itemalchemy-expansion.emc_card.config.quickcharge.enabled"
                                : "itemalchemy-expansion.emc_card.config.quickcharge.disabled")),
                b -> EmcCardClientNetwork.sendConfig(EmcCardNetwork.CFG_TOGGLE_QUICK, 0))
                .dimensions(centerX - 120, panelTop + 50, 240, 20).build());

        // 金额输入框
        int fieldY = panelTop + 80;
        int fieldWidth = 160;
        amountField = new TextFieldWidget(this.textRenderer,
                centerX - fieldWidth / 2, fieldY, fieldWidth, 16,
                Text.literal(String.valueOf(EmcCardItem.getQuickChargeAmount(card))));
        amountField.setMaxLength(18);
        amountField.setTextPredicate(this::isNumeric);
        amountField.setText(String.valueOf(EmcCardItem.getQuickChargeAmount(card)));
        addDrawableChild(amountField);

        // 设置金额按钮
        addDrawableChild(ButtonWidget.builder(
                Text.translatable("itemalchemy-expansion.emc_card.config.quickcharge.amount"),
                b -> {
                    String raw = amountField.getText().trim();
                    if (!raw.isEmpty()) {
                        try {
                            long val = Long.parseLong(raw);
                            if (val > 0)
                                EmcCardClientNetwork.sendConfig(EmcCardNetwork.CFG_SET_QUICK_AMOUNT, val);
                        } catch (NumberFormatException ignored) {}
                    }
                })
                .dimensions(centerX - 60, fieldY + 22, 120, 20).build());

        // 返回按钮
        addDrawableChild(ButtonWidget.builder(
                Text.translatable("itemalchemy-expansion.emc_card.cancel"),
                b -> MinecraftClient.getInstance().setScreen(new EmcCardMainScreen()))
                .dimensions(centerX - 60, fieldY + 50, 120, 20).build());
    }

    private boolean isNumeric(String s) {
        if (s.isEmpty()) return true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
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
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.translatable("itemalchemy-expansion.emc_card.config.title"),
                centerX, panelTop + PADDING + 11, 0xFF8080A0);

        int lineY = panelTop + PADDING + 23;
        context.fill(panelLeft + PADDING, lineY, panelLeft + PANEL_WIDTH - PADDING, lineY + 1, 0xFF405080);

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
