package itemalchemy.expansion.client;

import itemalchemy.expansion.client.util.GuiRenderUtil;
import itemalchemy.expansion.item.EmcCardItem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
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
        int btnY = panelTop + 118;

        int btnWidth = 96;
        int gap = 8;
        // 第一行：充入 / 拿取
        addDrawableChild(new ButtonWidget(
                centerX - btnWidth - gap / 2, btnY, btnWidth, 20,
                Text.translatable("itemalchemy-expansion.emc_card.deposit"),
                b -> MinecraftClient.getInstance().setScreen(new EmcCardDepositScreen())));
        addDrawableChild(new ButtonWidget(
                centerX + gap / 2, btnY, btnWidth, 20,
                Text.translatable("itemalchemy-expansion.emc_card.withdraw"),
                b -> MinecraftClient.getInstance().setScreen(new EmcCardWithdrawScreen())));

        // 第二行：设置 / 记录
        addDrawableChild(new ButtonWidget(
                centerX - btnWidth - gap / 2, btnY + 24, btnWidth, 20,
                Text.translatable("itemalchemy-expansion.emc_card.config"),
                b -> MinecraftClient.getInstance().setScreen(new EmcCardConfigScreen())));
        addDrawableChild(new ButtonWidget(
                centerX + gap / 2, btnY + 24, btnWidth, 20,
                Text.translatable("itemalchemy-expansion.emc_card.log"),
                b -> MinecraftClient.getInstance().setScreen(new EmcCardLogScreen())));

        // 第三行：关闭
        addDrawableChild(new ButtonWidget(
                centerX - 60, btnY + 48, 120, 20,
                Text.translatable("itemalchemy-expansion.emc_card.close"),
                b -> this.close()));
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        renderBackground(matrices);

        int centerX = this.width / 2;
        int panelLeft = centerX - PANEL_WIDTH / 2;
        int panelTop = this.height / 2 - 100;
        int panelHeight = 200;

        // 面板背景：深蓝紫色调
        DrawableHelper.fill(matrices, panelLeft, panelTop, panelLeft + PANEL_WIDTH, panelTop + panelHeight, 0xE0101420);
        GuiRenderUtil.drawBorder(matrices, panelLeft, panelTop, PANEL_WIDTH, panelHeight, 0xFF5060A0);

        // 标题
        DrawableHelper.drawCenteredTextWithShadow(matrices, this.textRenderer, this.title.asOrderedText(),
                centerX, panelTop + PADDING, 0xFFE0E0FF);

        // 标题下装饰线
        int lineY = panelTop + PADDING + 12;
        DrawableHelper.fill(matrices, panelLeft + PADDING, lineY, panelLeft + PANEL_WIDTH - PADDING, lineY + 1, 0xFF405080);

        long cardEmc = getCardEmc();
        long playerEmc = getPlayerEmc();

        int dataY = lineY + 12;

        // 绑卡：额外显示「已绑定：玩家名」并把卡内余额行改名为绑定余额
        MinecraftClient mc = MinecraftClient.getInstance();
        ItemStack mainHand = (mc != null && mc.player != null)
                ? mc.player.getMainHandStack() : ItemStack.EMPTY;
        boolean bound = !mainHand.isEmpty()
                && mainHand.getItem() instanceof EmcCardItem && EmcCardItem.isBound(mainHand);

        if (bound) {
            String bindName = resolveBindName(mainHand);
            // 已绑定：<名>。%s 在翻译键内，须传参渲染，否则会显示字面 %s
            Text bindLabel = Text.translatable("itemalchemy-expansion.emc_card.bind.label", bindName);
            this.textRenderer.draw(matrices, bindLabel, panelLeft + PADDING, dataY, 0xFF40FF80);
            // 绑定余额：同步绑定玩家的队 EMC（服务端下发的权威值）
            drawDataRow(matrices, "itemalchemy-expansion.emc_card.bind_emc",
                    EmcCardItem.formatNumber(cardEmc), panelLeft, dataY + LINE_HEIGHT + 8, 0xFF40A0FF, 0xFF60C0FF);
        } else {
            // 数据行：标签左对齐，数值右对齐（卡片本身价值不在此展示）
            drawDataRow(matrices, "itemalchemy-expansion.emc_card.card_emc",
                    EmcCardItem.formatNumber(cardEmc), panelLeft, dataY, 0xFF40A0FF, 0xFF60C0FF);
            drawDataRow(matrices, "itemalchemy-expansion.emc_card.player_emc",
                    EmcCardItem.formatNumber(playerEmc), panelLeft, dataY + LINE_HEIGHT + 8, 0xFFC0C0C0, 0xFFFFFF55);
        }

        // 分隔线
        int divY = dataY + LINE_HEIGHT * 2 + 16;
        DrawableHelper.fill(matrices, panelLeft + PADDING, divY, panelLeft + PANEL_WIDTH - PADDING, divY + 1, 0xFF405080);

        // 合并提示（灰色小字）
        DrawableHelper.drawCenteredTextWithShadow(matrices, this.textRenderer,
                Text.translatable("itemalchemy-expansion.emc_card.merge_hint").asOrderedText(),
                centerX, divY + 8, 0xFF8080A0);

        super.render(matrices, mouseX, mouseY, delta);
    }

    /** 绘制一行数据：左侧标签（灰），右侧数值（带颜色） */
    private void drawDataRow(MatrixStack matrices, String labelKey, String valueStr,
                             int panelLeft, int y, int labelColor, int valueColor) {
        Text label = Text.translatable(labelKey);
        Text value = Text.literal(valueStr);
        this.textRenderer.draw(matrices, label,
                panelLeft + PADDING, y, labelColor);
        this.textRenderer.draw(matrices, value,
                panelLeft + PANEL_WIDTH - PADDING - textRenderer.getWidth(value), y, valueColor);
    }

    /** 解析绑卡玩家名：卡上存的绑定名（离线可读）> 在线玩家档案 > UUID 截断 */
    private static String resolveBindName(ItemStack card) {
        String stored = EmcCardItem.getBindName(card);
        if (stored != null && !stored.isEmpty()) return stored;
        String uuid = EmcCardItem.getBindUuid(card);
        if (uuid == null) return "";
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc != null && mc.getNetworkHandler() != null) {
                var entry = mc.getNetworkHandler().getPlayerListEntry(java.util.UUID.fromString(uuid));
                if (entry != null && entry.getProfile() != null) {
                    return entry.getProfile().getName();
                }
            }
        } catch (Throwable t) {
            // fallthrough
        }
        return uuid.length() > 8 ? uuid.substring(0, 8) : uuid;
    }

    /** 当前主手卡内 EMC（优先用服务端同步余额，反映关联账户值）。 */
    private long getCardEmc() {
        return EmcCardClientNetwork.getCardBalance();
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
