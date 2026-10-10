package itemalchemy.expansion.client;

import itemalchemy.expansion.IAExpServices;
import itemalchemy.expansion.ItemAlchemyExpansion;
import itemalchemy.expansion.block.EmcEmitterBlockEntity;
import itemalchemy.expansion.client.util.GuiRenderUtil;
import itemalchemy.expansion.gui.EmcEmitterScreenHandler;
import itemalchemy.expansion.item.EmcCardItem;
import itemalchemy.expansion.nbt.ItemVariantKey;
import itemalchemy.expansion.nbt.ShulkerBoxSupport;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.registry.Registry;
import net.pitan76.mcpitanlib.api.client.gui.screen.SimpleInventoryScreen;
import net.pitan76.mcpitanlib.api.client.render.handledscreen.DrawBackgroundArgs;
import net.pitan76.mcpitanlib.api.client.render.handledscreen.DrawForegroundArgs;
import net.pitan76.mcpitanlib.api.client.render.handledscreen.RenderArgs;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * EMC 构物器容器 GUI：左侧为物品选择列表（搜索 + 滚动 + 点击选择），右侧为 EMC 卡槽 +
 * 玩家物品栏。卡槽是真实容器槽，可直接拖动放入/取出 EMC 卡；E 键/Esc 关闭。
 *
 * <p>所选物品存于方块 NBT（共享），列表基于打开者本人，由服务端经 {@code EmcAutoNetwork}
 * 下发；卡槽内容变化时重新请求以刷新余额。</p>
 */
public class EmcEmitterScreen extends SimpleInventoryScreen<EmcEmitterScreenHandler> {

    private static final int BG_W = 366;
    private static final int BG_H = 200;

    // 左列：搜索框 + 物品列表
    private static final int LIST_X = 8;
    private static final int LIST_W = 168;
    private static final int SEARCH_Y = 18;
    private static final int LIST_TOP = 36;
    private static final int LIST_BOTTOM = 184;
    private static final int ROW_H = 20;

    // 右列底部：当前选择展示面板
    private static final int SELECT_PANEL_X = 184;
    private static final int SELECT_PANEL_Y = 154;
    private static final int SELECT_PANEL_W = 174;
    private static final int SELECT_PANEL_H = 42;

    // 原版容器灰
    private static final int PANEL = 0xFFC6C6C6;
    private static final int PANEL_LINE = 0xFF555555;
    private static final int SLOT_BG = 0xFF8B8B8B;
    private static final int TEXT_MAIN = 0xFF404040;
    private static final int TEXT_DIM = 0xFF6E6E6E;
    private static final int ACCENT = 0xFF2EC4B6;
    private static final int ACCENT_DARK = 0xFF1E88A8;

    private final List<String> keys = new ArrayList<>();
    private final List<ItemStack> stacks = new ArrayList<>();
    /** 过滤后的下标（指向 keys/stacks），搜索为空时与全量一一对应 */
    private final List<Integer> filtered = new ArrayList<>();
    private String selected = "";
    private long balance = 0;
    private String facing = "";
    private boolean loaded = false;
    private int scroll = 0;
    private TextFieldWidget searchField;
    /** 上一次看到的卡槽栈，用于检测卡槽变化后刷新余额（无可重写的槽变化钩子） */
    private ItemStack lastCardSlot = ItemStack.EMPTY;

    public EmcEmitterScreen(EmcEmitterScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
        setBackgroundWidth(BG_W);
        setBackgroundHeight(BG_H);
        EmcAutoClientNetwork.attach(this);
    }

    @Override
    public Identifier getTexture() {
        // 背景为纯代码绘制，返回占位符即可（不会被使用）
        return new Identifier(ItemAlchemyExpansion.MOD_ID, "textures/gui/emc_emitter");
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void initOverride() {
        int x = this.x;
        int y = this.y;

        // 搜索框（模糊过滤转换桌列表：名称 / id / 变体键）
        String oldText = searchField == null ? "" : searchField.getText();
        searchField = new TextFieldWidget(this.textRenderer, x + LIST_X, y + SEARCH_Y,
                LIST_W, 14, Text.translatable("itemalchemy-expansion.emc_emitter.search"));
        searchField.setMaxLength(64);
        searchField.setText(oldText);
        searchField.setChangedListener(t -> applyFilter());
        addDrawableChild_compatibility(searchField);

        // 清除选择按钮（右下）
        addDrawableChild_compatibility(new ButtonWidget(
                x + 184, y + 132, 70, 20,
                Text.translatable("itemalchemy-expansion.emc_emitter.clear"),
                b -> EmcAutoClientNetwork.sendSet(null)));

        applyFilter();
        // 菜单已打开，请求服务端下发列表（含余额/卡栈）
        EmcAutoClientNetwork.sendRequest();
    }

    /** 按搜索词重建过滤视图并复位滚动 */
    private void applyFilter() {
        filtered.clear();
        String q = searchField == null ? "" : searchField.getText().trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < keys.size(); i++) {
            if (q.isEmpty() || matches(stacks.get(i), keys.get(i), q)) {
                filtered.add(i);
            }
        }
        scroll = 0;
    }

    private boolean matches(ItemStack stack, String key, String q) {
        if (key != null && key.toLowerCase(Locale.ROOT).contains(q)) {
            return true;
        }
        if (!stack.isEmpty()) {
            String name = stack.getName().getString().toLowerCase(Locale.ROOT);
            if (name.contains(q)) {
                return true;
            }
            try {
                String id = Registry.ITEM.getId(stack.getItem()).toString();
                if (id.toLowerCase(Locale.ROOT).contains(q)) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /** 服务端下发列表后调用 */
    public void onListReceived(List<String> newKeys, List<ItemStack> newStacks, String newSelected, long newBalance, String newFacing, ItemStack newCard) {
        this.keys.clear();
        this.keys.addAll(newKeys);
        this.stacks.clear();
        this.stacks.addAll(newStacks);
        this.selected = (newSelected == null || newSelected.isEmpty()) ? "" : newSelected;
        this.balance = newBalance;
        this.facing = newFacing == null ? "" : newFacing;
        this.loaded = true;
        applyFilter();
    }

    /** 服务端下发更新后的所选物品 */
    public void onSelectedUpdated(String newSelected) {
        this.selected = (newSelected == null || newSelected.isEmpty()) ? "" : newSelected;
    }

    /** 服务端心跳下发的实时卡余额（自动化扣减/他人操作后保持显示一致） */
    public void onBalanceReceived(long newBalance) {
        this.balance = newBalance;
    }

    /** 余额心跳：约每秒请求一次，避免自动化扣减后余额显示陈旧 */
    private long lastBalanceReqTime = 0;

    private void tickBalance() {
        var world = MinecraftClient.getInstance().world;
        if (world == null) return;
        long t = world.getTime();
        if (t - lastBalanceReqTime >= 20) {
            lastBalanceReqTime = t;
            EmcAutoClientNetwork.sendBalanceRequest();
        }
    }

    /** 卡槽内容变化（放入/取出 EMC 卡）时重新请求，刷新余额显示。
     *  仅比较物品身份（空/非空、物品种类），避免普通卡 NBT 每次扣减触发整列表重发。 */
    private void refreshOnCardChange() {
        ItemStack slotCard = this.handler.getSlot(EmcEmitterBlockEntity.CARD_SLOT).getStack();
        boolean changed = slotCard.isEmpty() != lastCardSlot.isEmpty()
                || (!slotCard.isEmpty() && slotCard.getItem() != lastCardSlot.getItem());
        if (changed) {
            lastCardSlot = slotCard.copy();
            EmcAutoClientNetwork.sendRequest();
        }
    }

    private boolean inListArea(double mouseX, double mouseY) {
        return mouseX >= this.x + LIST_X && mouseX < this.x + LIST_X + LIST_W
                && mouseY >= this.y + LIST_TOP && mouseY < this.y + LIST_BOTTOM;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && canPick() && inListArea(mouseX, mouseY)) {
            int row = scroll + (int) ((mouseY - (this.y + LIST_TOP)) / ROW_H);
            if (row >= 0 && row < filtered.size()) {
                EmcAutoClientNetwork.sendSet(keys.get(filtered.get(row)));
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (canPick() && inListArea(mouseX, mouseY)) {
            int rows = visibleRows();
            int maxScroll = Math.max(0, filtered.size() - rows);
            scroll = (int) Math.max(0, Math.min(maxScroll, scroll - amount));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    private boolean canPick() {
        return loaded && !filtered.isEmpty();
    }

    private int visibleRows() {
        return (LIST_BOTTOM - LIST_TOP) / ROW_H;
    }

    @Override
    public void drawBackgroundOverride(DrawBackgroundArgs args) {
        MatrixStack matrices = args.drawObjectDM.getStack();
        int x = this.x;
        int y = this.y;

        // 面板 + 描边 + 顶部标题条
        DrawableHelper.fill(matrices, x, y, x + BG_W, y + BG_H, PANEL);
        drawBorder(matrices, x, y, BG_W, BG_H, PANEL_LINE);
        // 顶部标题条：与 1.20.1 的 fillGradient 同款竖向渐变
        // （1.19.2 的 DrawableHelper.fillGradient 是 protected，跨包不可调用，见 GuiRenderUtil）
        GuiRenderUtil.fillVerticalGradient(matrices, x + 1, y + 1, x + BG_W - 1, y + 12, 0xFFD2D2D2, PANEL);

        // 标题
        DrawableHelper.drawCenteredTextWithShadow(matrices, this.textRenderer, this.title.asOrderedText(),
                this.width / 2, y - 8, TEXT_MAIN);

        // 左列列表区底框
        DrawableHelper.fill(matrices, x + LIST_X, y + LIST_TOP, x + LIST_X + LIST_W, y + LIST_BOTTOM, 0xFF9A9A9A);
        drawBorder(matrices, x + LIST_X, y + LIST_TOP, LIST_W, LIST_BOTTOM - LIST_TOP, PANEL_LINE);

        // 右列槽位底框（卡槽 + 玩家物品栏 + 快捷栏，坐标取自 ScreenHandler）
        for (Slot s : this.handler.slots) {
            drawSlotBox(matrices, x + s.x - 1, y + s.y - 1, s.id == EmcEmitterBlockEntity.CARD_SLOT);
        }
        // 空卡槽画卡片轮廓示意
        Slot cardSlot = this.handler.getSlot(EmcEmitterBlockEntity.CARD_SLOT);
        if (cardSlot.getStack().isEmpty()) {
            int cx = x + cardSlot.x + 3;
            int cy = y + cardSlot.y + 2;
            DrawableHelper.fill(matrices, cx, cy, cx + 10, cy + 12, 0xFFAEB8C2);
            DrawableHelper.fill(matrices, cx + 1, cy + 1, cx + 3, cy + 3, ACCENT_DARK);
            DrawableHelper.fill(matrices, cx + 2, cy + 8, cx + 8, cy + 9, 0xFF7B8794);
        }
    }

    @Override
    protected void drawForegroundOverride(DrawForegroundArgs args) {
        // 标题与文字均由 drawBackgroundOverride/renderOverride 绘制，跳过原版 foreground
        // （否则会在列表区叠加「物品栏」标签、标题二次绘制）
    }

    @Override
    public void renderOverride(RenderArgs args) {
        super.renderOverride(args);

        MatrixStack matrices = args.drawObjectDM.getStack();
        int x = this.x;
        int y = this.y;

        // 卡槽内容变化时刷新余额（真实容器槽由原版同步，这里仅在变化时请求一次）
        refreshOnCardChange();
        // 余额心跳：自动化扣减/他人操作后保持显示与服务端一致
        tickBalance();

        // 卡余额（卡槽右侧）+ 输出方向（左列底部）。绑卡显示「已绑定 <名> · 余额」。
        ItemStack card = this.handler.getSlot(EmcEmitterBlockEntity.CARD_SLOT).getStack();
        Text balanceText;
        if (!card.isEmpty() && card.getItem() instanceof EmcCardItem && EmcCardItem.isBound(card)) {
            String name = EmcCardItem.getBindName(card);
            if (name == null || name.isEmpty()) name = EmcCardItem.getBindUuid(card);
            balanceText = Text.translatable("itemalchemy-expansion.emc_emitter.balance_bound",
                    Text.literal(name == null ? "?" : name),
                    Text.literal(EmcCardItem.formatNumber(balance)));
        } else {
            balanceText = Text.translatable("itemalchemy-expansion.emc_emitter.balance",
                    Text.literal(EmcCardItem.formatNumber(balance)));
        }
        this.textRenderer.draw(matrices, balanceText,
                x + EmcEmitterScreenHandler.CARD_SLOT_X + 26, y + 22, TEXT_MAIN);
        if (!facing.isEmpty()) {
            this.textRenderer.draw(matrices,
                    Text.translatable("itemalchemy-expansion.emc_emitter.facing",
                            Text.translatable("itemalchemy-expansion.direction." + facing)),
                    x + LIST_X, y + LIST_BOTTOM + 6, TEXT_DIM);
        }

        // 左列物品列表
        int listTop = y + LIST_TOP;
        int listBottom = y + LIST_BOTTOM;
        if (!loaded) {
            DrawableHelper.drawCenteredTextWithShadow(matrices, this.textRenderer,
                    Text.translatable("itemalchemy-expansion.emc_emitter.loading").asOrderedText(),
                    x + LIST_X + LIST_W / 2, (listTop + listBottom) / 2 - 4, 0xFF707070);
        } else if (filtered.isEmpty()) {
            DrawableHelper.drawCenteredTextWithShadow(matrices, this.textRenderer,
                    Text.translatable(keys.isEmpty()
                            ? "itemalchemy-expansion.emc_emitter.empty"
                            : "itemalchemy-expansion.emc_emitter.no_match").asOrderedText(),
                    x + LIST_X + LIST_W / 2, (listTop + listBottom) / 2 - 4, 0xFF707070);
        } else {
            int rows = visibleRows();
            int maxScroll = Math.max(0, filtered.size() - rows);
            if (scroll > maxScroll) scroll = maxScroll;
            int start = scroll;
            int end = Math.min(filtered.size(), start + rows);
            for (int r = start; r < end; r++) {
                int i = filtered.get(r);
                int ry = listTop + (r - start) * ROW_H;
                ItemStack st = stacks.get(i);
                boolean isSel = keys.get(i).equals(selected);
                if (isSel) {
                    // 选中行：亮青色底，更醒目
                    DrawableHelper.fill(matrices, x + LIST_X + 1, ry, x + LIST_X + LIST_W - 1, ry + ROW_H, 0xFF8FD9D3);
                    DrawableHelper.fill(matrices, x + LIST_X + 1, ry, x + LIST_X + 2, ry + ROW_H, ACCENT);
                }
                if (!st.isEmpty()) {
                    drawItemIcon(matrices, st, x + LIST_X + 3, ry + 1);
                }
                String name = st.isEmpty() ? keys.get(i) : st.getName().getString();
                this.textRenderer.draw(matrices, Text.literal(name), x + LIST_X + 23, ry + 6,
                        isSel ? 0xFF143A3A : 0xFF606060);
                if (isSel) {
                    this.textRenderer.draw(matrices, Text.literal(">"), x + LIST_X + 1, ry + 6, ACCENT_DARK);
                }
            }
        }

        // 右列底部：当前选择展示面板 + 悬停信息框 + 潜影盒 Shift 预览
        renderSelectionPanel(matrices, x, y, args.mouseX, args.mouseY);
    }

    /** 当前所选物品（由 selected 变体键重建） */
    private ItemStack selectedStack() {
        if (selected.isEmpty()) return ItemStack.EMPTY;
        ItemVariantKey vk = ItemVariantKey.fromStorageString(selected);
        if (vk == null) return ItemStack.EMPTY;
        return IAExpServices.rebuildStack(vk);
    }

    private boolean inSelectionPanel(double mouseX, double mouseY) {
        return mouseX >= this.x + SELECT_PANEL_X && mouseX < this.x + SELECT_PANEL_X + SELECT_PANEL_W
                && mouseY >= this.y + SELECT_PANEL_Y && mouseY < this.y + SELECT_PANEL_Y + SELECT_PANEL_H;
    }

    /** 绘制「当前选择」展示面板，并处理悬停信息框 / 潜影盒 Shift 预览 */
    private void renderSelectionPanel(MatrixStack matrices, int x, int y, int mouseX, int mouseY) {
        int px = x + SELECT_PANEL_X;
        int py = y + SELECT_PANEL_Y;
        DrawableHelper.fill(matrices, px, py, px + SELECT_PANEL_W, py + SELECT_PANEL_H, 0xFF9A9A9A);
        drawBorder(matrices, px, py, SELECT_PANEL_W, SELECT_PANEL_H, PANEL_LINE);
        this.textRenderer.draw(matrices,
                Text.translatable("itemalchemy-expansion.emc_emitter.current"), px + 4, py + 2, 0xFF5A5A5A);

        ItemStack sel = selectedStack();
        if (sel.isEmpty()) {
            this.textRenderer.draw(matrices,
                    Text.translatable("itemalchemy-expansion.emc_emitter.none"), px + 4, py + 16, 0xFF888888);
            return;
        }
        drawItemIcon(matrices, sel, px + 4, py + 14);
        long cost = emcOf(sel);
        Text name = sel.getName();
        String line = cost > 0
                ? name.getString() + "  (" + EmcCardItem.formatNumber(cost) + " EMC)"
                : name.getString();
        this.textRenderer.draw(matrices, Text.literal(line), px + 24, py + 18, TEXT_MAIN);

        boolean hover = inSelectionPanel(mouseX, mouseY);
        if (!hover) return;
        // 悬停显示原版物品信息框（1.19.2 的 getTooltipFromItem 只收 ItemStack）
        this.renderTooltip(matrices, this.getTooltipFromItem(sel), mouseX, mouseY);
        // 潜影盒支持 Shift 预览内容物
        if (Screen.hasShiftDown() && ShulkerBoxSupport.isShulkerBox(sel)) {
            try {
                renderShulkerPreview(matrices, mouseX, mouseY, sel);
            } catch (Throwable t) {
                ItemAlchemyExpansion.LOGGER.warn("[IAExp] failed to render emitter shulker preview", t);
            }
        }
    }

    /** 简洁 9×3 潜影盒内容预览（Shift 悬停选择展示时） */
    private void renderShulkerPreview(MatrixStack matrices, int mouseX, int mouseY, ItemStack shulkerBox) {
        ShulkerBoxSupport.ContentsAndEmc cae = ShulkerBoxSupport.getContentsAndSumEmc(shulkerBox);
        ItemStack[] contents = cae.contents;
        final int cols = 9, rows = 3, slot = 18, pad = 4, title = 12;
        int w = cols * slot + pad * 2;
        int h = pad + title + 2 + rows * slot + pad;
        int px = mouseX - w - 16;
        int py = mouseY - h - 16;
        if (px < 0) px = mouseX + 16;
        if (py < 0) py = mouseY + 16;
        if (px + w > this.width) px = this.width - w;
        if (py + h > this.height) py = this.height - h;

        matrices.push();
        matrices.translate(0, 0, 500);
        try {
            DrawableHelper.fill(matrices, px, py, px + w, py + h, 0xF0101010);
            drawBorder(matrices, px, py, w, h, 0xFF505050);
            DrawableHelper.drawTextWithShadow(matrices, this.textRenderer,
                    Text.translatable("itemalchemy-expansion.shulker_box.preview_title",
                            shulkerBox.getName(), String.format("%,d", cae.sumEmc)),
                    px + pad, py + pad, 0xFFFFFF);
            int gridY = py + pad + title + 2;
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    int idx = r * cols + c;
                    int sx = px + pad + c * slot;
                    int sy = gridY + r * slot;
                    DrawableHelper.fill(matrices, sx, sy, sx + slot, sy + slot, 0x80202020);
                    ItemStack item = contents[idx];
                    if (!item.isEmpty()) drawItemIcon(matrices, item, sx + 1, sy + 1);
                }
            }
        } finally {
            matrices.pop();
        }
    }

    private long emcOf(ItemStack stack) {
        try {
            return net.pitan76.itemalchemy.EMCManager.get(stack);
        } catch (Throwable t) {
            return 0;
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

    /** 1.19.2 物品图标渲染（无 DrawContext，改走 RenderSystem 模型视图矩阵） */
    private static void drawItemIcon(MatrixStack matrices, ItemStack stack, int x, int y) {
        com.mojang.blaze3d.systems.RenderSystem.getModelViewStack().push();
        com.mojang.blaze3d.systems.RenderSystem.getModelViewStack().multiplyPositionMatrix(matrices.peek().getPositionMatrix());
        com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix();
        try {
            MinecraftClient.getInstance().getItemRenderer().renderGuiItemIcon(stack, x, y);
        } finally {
            com.mojang.blaze3d.systems.RenderSystem.getModelViewStack().pop();
            com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix();
        }
    }

    @Override
    public void removedOverride() {
        EmcAutoClientNetwork.detach(this);
        super.removedOverride();
    }
}
