package itemalchemy.expansion.client;

import itemalchemy.expansion.client.util.GuiRenderUtil;
import itemalchemy.expansion.item.EmcCardItem;
import itemalchemy.expansion.network.EmcCardNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import itemalchemy.expansion.compat.port.FilteredEditBox;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;

/**
 * EMC 卡设置界面：快捷充能开关 + 金额配置。
 */
public class EmcCardConfigScreen extends Screen {

    private static final int PANEL_WIDTH = 260;
    private static final int PADDING = 14;

    private FilteredEditBox amountField;

    public EmcCardConfigScreen() {
        super(EmcCardMainScreen.getCardName());
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int panelTop = this.height / 2 - 90;
        ItemStack card = getCardStack();
        boolean enabled = EmcCardItem.isQuickChargeEnabled(card);

        // 快捷充能开关按钮
        addRenderableWidget(Button.builder(
                Component.translatable("itemalchemy-expansion.emc_card.config.quickcharge")
                        .append(": ")
                        .append(Component.translatable(enabled
                                ? "itemalchemy-expansion.emc_card.config.quickcharge.enabled"
                                : "itemalchemy-expansion.emc_card.config.quickcharge.disabled")),
                b -> EmcCardClientNetwork.sendConfig(EmcCardNetwork.CFG_TOGGLE_QUICK, 0))
                .bounds(centerX - 120, panelTop + 50, 240, 20).build());

        // 金额输入框
        int fieldY = panelTop + 80;
        int fieldWidth = 160;
        amountField = new FilteredEditBox(this.font,
                centerX - fieldWidth / 2, fieldY, fieldWidth, 16,
                Component.literal(String.valueOf(EmcCardItem.getQuickChargeAmount(card))));
        amountField.setMaxLength(18);
        amountField.setTextPredicate(this::isNumeric);
        amountField.setValue(String.valueOf(EmcCardItem.getQuickChargeAmount(card)));
        addRenderableWidget(amountField);
        // 与存入/取出界面一致：进入即让输入框获焦，26.2 的文本输入（含输入法）只在文本框获焦时开启
        this.setFocused(amountField);

        // 设置金额按钮
        addRenderableWidget(Button.builder(
                Component.translatable("itemalchemy-expansion.emc_card.config.quickcharge.amount"),
                b -> {
                    String raw = amountField.getValue().trim();
                    if (!raw.isEmpty()) {
                        try {
                            long val = Long.parseLong(raw);
                            if (val > 0)
                                EmcCardClientNetwork.sendConfig(EmcCardNetwork.CFG_SET_QUICK_AMOUNT, val);
                        } catch (NumberFormatException ignored) {}
                    }
                })
                .bounds(centerX - 60, fieldY + 22, 120, 20).build());

        // 返回按钮
        addRenderableWidget(Button.builder(
                Component.translatable("itemalchemy-expansion.emc_card.cancel"),
                b -> Minecraft.getInstance().setScreenAndShow(new EmcCardMainScreen()))
                .bounds(centerX - 60, fieldY + 50, 120, 20).build());
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
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        // The 26.2 GUI manager already extracts the screen background.
        int centerX = this.width / 2;
        int panelLeft = centerX - PANEL_WIDTH / 2;
        int panelTop = this.height / 2 - 90;
        int panelHeight = 180;

        context.fill(panelLeft, panelTop, panelLeft + PANEL_WIDTH, panelTop + panelHeight, 0xE0101420);
        GuiRenderUtil.drawBorder(context, panelLeft, panelTop, PANEL_WIDTH, panelHeight, 0xFF5060A0);

        context.centeredText(this.font, this.title,
                centerX, panelTop + PADDING, 0xFFE0E0FF);
        context.centeredText(this.font,
                Component.translatable("itemalchemy-expansion.emc_card.config.title"),
                centerX, panelTop + PADDING + 11, 0xFF8080A0);

        int lineY = panelTop + PADDING + 23;
        context.fill(panelLeft + PADDING, lineY, panelLeft + PANEL_WIDTH - PADDING, lineY + 1, 0xFF405080);

        super.extractRenderState(context, mouseX, mouseY, delta);
    }

    private ItemStack getCardStack() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) return ItemStack.EMPTY;
        ItemStack mainHand = mc.player.getMainHandItem();
        if (mainHand.isEmpty() || !(mainHand.getItem() instanceof EmcCardItem)) return ItemStack.EMPTY;
        return mainHand;
    }
}
