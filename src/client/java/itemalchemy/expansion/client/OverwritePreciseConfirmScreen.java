package itemalchemy.expansion.client;

import itemalchemy.expansion.IAExpServices;
import itemalchemy.expansion.client.util.GuiRenderUtil;
import itemalchemy.expansion.nbt.ItemVariantKey;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 「通用价覆盖精确价」确认对话框：玩家设通用价且该 ID 存在 L1 精确覆盖时弹出。
 *
 * <p>风格仿照 {@link RepriceConfirmScreen}：可滚动列表，每行一个变体，展示物品图标、名称、
 * 变体标签、旧精确 EMC。复选框勾选=覆盖（清除该变体的精确价，统一用通用价），
 * 未勾选=保留（该变体继续用精确价）。按钮：「全部覆盖」「全部保留」「确认选择」。</p>
 *
 * <p>确认后回送勾选的变体键列表给 {@link SetEmcScreen}，由后者发送 set_emc 包
 * （带 {@code preciseVkStrsToClear}）到服务端。</p>
 */
public class OverwritePreciseConfirmScreen extends Screen {

    private static final int PANEL_W = 380;
    private static final int PANEL_H = 240;
    private static final int ROW_H = 22;
    private static final int CHECKBOX = 12;
    private static final int LIST_TOP_OFFSET = 52;
    private static final int LIST_BOTTOM_OFFSET = 38;

    /** 变体条目列表 */
    private final List<OverwriteEntry> entries;
    /** 用户选择后的回调（参数=勾选覆盖的变体键列表） */
    private final java.util.function.Consumer<List<String>> confirmCallback;
    /** 取消回调（用户点取消或按 ESC） */
    private final Runnable cancelCallback;
    /** 新通用价（用于预览「→ 通用: X」） */
    private final long newGeneralEmc;
    /** 物品 ID（用于标题显示） */
    private final String itemId;

    private int scrollOffset = 0;
    private int panelX, panelY, panelW, panelH;

    public OverwritePreciseConfirmScreen(String itemId, long newGeneralEmc,
                                          Map<String, Long> variants,
                                          java.util.function.Consumer<List<String>> confirmCallback,
                                          Runnable cancelCallback) {
        super(Text.translatable("itemalchemy-expansion.overwrite_precise.title"));
        this.itemId = itemId;
        this.newGeneralEmc = newGeneralEmc;
        this.confirmCallback = confirmCallback;
        this.cancelCallback = cancelCallback;
        this.entries = new ArrayList<>();
        if (variants != null) {
            for (Map.Entry<String, Long> e : variants.entrySet()) {
                this.entries.add(new OverwriteEntry(e.getKey(), e.getValue()));
            }
        }
    }

    /** 一条精确覆盖变体 */
    private static final class OverwriteEntry {
        final String vkStr;
        final long oldEmc;
        final ItemStack stack;
        /** 勾选=覆盖（清除精确价），默认勾选 */
        boolean overwrite = true;

        OverwriteEntry(String vkStr, long oldEmc) {
            this.vkStr = vkStr;
            this.oldEmc = oldEmc;
            this.stack = resolveStack(vkStr);
        }

        String displayName() {
            try {
                if (!stack.isEmpty()) {
                    String n = stack.getName().getString();
                    if (n != null && !n.isEmpty()) return n;
                }
            } catch (Throwable ignore) {}
            return extractItemId(vkStr);
        }
    }

    // ============ 生命周期 ============

    @Override
    public boolean shouldPause() {
        return true;
    }

    @Override
    protected void init() {
        layout();
        int btnY = panelY + panelH - 26;
        int btnW = 100;
        int gap = 6;
        int totalW = btnW * 3 + gap * 2;
        int startX = panelX + (panelW - totalW) / 2;

        addDrawableChild(ButtonWidget.builder(
                Text.translatable("itemalchemy-expansion.overwrite_precise.overwrite_all"),
                b -> setAllOverwrite(true))
                .dimensions(startX, btnY, btnW, 20).build());

        addDrawableChild(ButtonWidget.builder(
                Text.translatable("itemalchemy-expansion.overwrite_precise.keep_all"),
                b -> setAllOverwrite(false))
                .dimensions(startX + btnW + gap, btnY, btnW, 20).build());

        addDrawableChild(ButtonWidget.builder(
                Text.translatable("itemalchemy-expansion.overwrite_precise.confirm"),
                b -> onConfirm())
                .dimensions(startX + (btnW + gap) * 2, btnY, btnW, 20).build());
    }

    private void layout() {
        panelW = Math.min(PANEL_W, this.width - 20);
        panelH = Math.min(PANEL_H, this.height - 20);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
    }

    // ============ 交互 ============

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && hitListRow(mouseX, mouseY) >= 0) {
            int idx = hitListRow(mouseX, mouseY);
            entries.get(idx).overwrite = !entries.get(idx).overwrite;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double amount) {
        int listTop = panelY + LIST_TOP_OFFSET;
        int listBottom = panelY + panelH - LIST_BOTTOM_OFFSET;
        int listHeight = listBottom - listTop;
        int totalHeight = entries.size() * ROW_H;
        int maxScroll = Math.max(0, totalHeight - listHeight);
        if (maxScroll <= 0) return super.mouseScrolled(mouseX, mouseY, horizontalAmount, amount);
        scrollOffset -= (int) (amount * ROW_H);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
        return true;
    }

    @Override
    public void close() {
        if (cancelCallback != null) cancelCallback.run();
    }

    private int hitListRow(double mouseX, double mouseY) {
        int listTop = panelY + LIST_TOP_OFFSET;
        int listBottom = panelY + panelH - LIST_BOTTOM_OFFSET;
        int listLeft = panelX + 10;
        int listRight = panelX + panelW - 14;
        if (mouseX < listLeft || mouseX > listRight || mouseY < listTop || mouseY > listBottom) return -1;
        int relY = (int) (mouseY - listTop) + scrollOffset;
        int idx = relY / ROW_H;
        if (idx < 0 || idx >= entries.size()) return -1;
        return idx;
    }

    private void setAllOverwrite(boolean overwrite) {
        for (OverwriteEntry e : entries) e.overwrite = overwrite;
    }

    private void onConfirm() {
        List<String> toClear = new ArrayList<>();
        for (OverwriteEntry e : entries) {
            if (e.overwrite) toClear.add(e.vkStr);
        }
        if (confirmCallback != null) confirmCallback.accept(toClear);
        this.client.setScreen(null);
    }

    // ============ 渲染 ============

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        // 基类 1.21.1 版会 applyBlur 模糊全屏；背景由 render() 自行铺暗色
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // 1.21.1 的 4 参 renderBackground 带 applyBlur 会产生模糊残影，直接铺暗色背景
        context.fill(0, 0, this.width, this.height, 0xA0000000);

        int listTop = panelY + LIST_TOP_OFFSET;
        int listBottom = panelY + panelH - LIST_BOTTOM_OFFSET;
        int listLeft = panelX + 10;
        int listRight = panelX + panelW - 14;
        int listWidth = listRight - listLeft;

        context.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xC0101010);
        GuiRenderUtil.drawBorder(context, panelX, panelY, panelW, panelH, 0xFF404040);

        context.drawCenteredTextWithShadow(this.textRenderer, this.title,
                this.width / 2, panelY + 10, 0xFFFFFF);
        // 副标题：物品 ID + 新通用价
        Text subtitle = Text.translatable("itemalchemy-expansion.overwrite_precise.subtitle",
                Text.literal(itemId), Text.literal(String.valueOf(newGeneralEmc)));
        context.drawText(this.textRenderer, subtitle, listLeft, panelY + 24, 0xA0A0A0, false);
        Text desc = Text.translatable("itemalchemy-expansion.overwrite_precise.desc");
        context.drawText(this.textRenderer, desc, listLeft, panelY + 36, 0x808080, false);

        context.fill(listLeft, listTop, listRight, listBottom, 0x60000000);
        GuiRenderUtil.drawBorder(context, listLeft, listTop, listWidth, listBottom - listTop, 0xFF303030);

        if (entries.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.translatable("itemalchemy-expansion.overwrite_precise.empty"),
                    (listLeft + listRight) / 2, (listTop + listBottom) / 2 - 4, 0xA0A0A0);
        } else {
            context.enableScissor(listLeft, listTop, listRight, listBottom);
            int hoverIdx = hitListRow(mouseX, mouseY);
            for (int i = 0; i < entries.size(); i++) {
                int rowY = listTop + i * ROW_H - scrollOffset;
                if (rowY + ROW_H <= listTop || rowY >= listBottom) continue;
                drawRow(context, entries.get(i), listLeft, listRight, rowY, i == hoverIdx);
            }
            context.disableScissor();
            drawScrollbar(context, listRight, listTop, listBottom);
        }

        context.drawText(this.textRenderer,
                Text.translatable("itemalchemy-expansion.overwrite_precise.hint"),
                listLeft, panelY + panelH - 16, 0x707070, false);

        super.render(context, mouseX, mouseY, delta);
    }

    private void drawRow(DrawContext context, OverwriteEntry e, int listLeft, int listRight, int rowY, boolean hovered) {
        int textY = rowY + (ROW_H - 8) / 2;
        if (hovered) {
            context.fill(listLeft + 1, rowY, listRight - 1, rowY + ROW_H, 0x30FFFFFF);
        }
        drawCheckbox(context, listLeft + 2, rowY + (ROW_H - CHECKBOX) / 2, e.overwrite);
        context.drawItem(e.stack, listLeft + 20, rowY + (ROW_H - 16) / 2);
        String name = e.displayName();
        if (name.length() > 16) name = name.substring(0, 15) + "...";
        context.drawText(this.textRenderer, name, listLeft + 42, textY, 0xFFFFFF, false);

        // 变体标签
        int nameW = this.textRenderer.getWidth(name);
        int tagX = listLeft + 42 + nameW + 6;
        Text preciseTag = Text.translatable("itemalchemy-expansion.reprice.layer_precise");
        context.drawText(this.textRenderer, preciseTag, tagX, textY, 0x55FFFF, false);

        // NBT 指纹摘要
        int tagW = this.textRenderer.getWidth(preciseTag);
        String nbtSummary = extractNbtSummary(e.vkStr);
        if (nbtSummary != null && !nbtSummary.isEmpty()) {
            context.drawText(this.textRenderer, nbtSummary, tagX + tagW + 4, textY, 0x888888, false);
        }

        drawEmc(context, e, listRight, textY);
    }

    private void drawCheckbox(DrawContext context, int x, int y, boolean checked) {
        int fill = checked ? 0xFF208020 : 0xFF1A1A1A;
        int border = checked ? 0xFF30C030 : 0xFF707070;
        context.fill(x, y, x + CHECKBOX, y + CHECKBOX, fill);
        GuiRenderUtil.drawBorder(context, x, y, CHECKBOX, CHECKBOX, border);
        if (checked) {
            context.fill(x + 3, y + 3, x + CHECKBOX - 3, y + CHECKBOX - 3, 0xFFFFFFFF);
        }
    }

    private void drawEmc(DrawContext context, OverwriteEntry e, int rightX, int y) {
        Text oldT = Text.translatable("itemalchemy-expansion.reprice.old_emc", String.valueOf(e.oldEmc));
        Text newT = Text.translatable("itemalchemy-expansion.overwrite_precise.new_general",
                String.valueOf(newGeneralEmc));
        int oldW = this.textRenderer.getWidth(oldT);
        int sepW = this.textRenderer.getWidth("  ");
        int newW = this.textRenderer.getWidth(newT);
        int groupW = oldW + sepW + newW;
        int startX = rightX - groupW - 4;

        int oldColor = e.overwrite ? 0xFFFFE040 : 0xFFA0A0A0;
        context.drawText(this.textRenderer, oldT, startX, y, oldColor, false);
        context.drawText(this.textRenderer, newT, startX + oldW + sepW, y, 0xFF40E060, false);
    }

    private void drawScrollbar(DrawContext context, int listRight, int listTop, int listBottom) {
        int listHeight = listBottom - listTop;
        int totalHeight = entries.size() * ROW_H;
        int maxScroll = Math.max(0, totalHeight - listHeight);
        if (maxScroll <= 0) return;
        int trackX = listRight + 2;
        int trackW = 6;
        context.fill(trackX, listTop, trackX + trackW, listBottom, 0x40404040);
        int thumbH = Math.max(20, listHeight * listHeight / totalHeight);
        int thumbY = listTop + (int) ((long) scrollOffset * (listHeight - thumbH) / maxScroll);
        context.fill(trackX, thumbY, trackX + trackW, thumbY + thumbH, 0xFF808080);
    }

    // ============ 工具 ============

    private static ItemStack resolveStack(String vkStr) {
        try {
            if (vkStr != null && !vkStr.isEmpty()) {
                ItemVariantKey vk = ItemVariantKey.fromStorageString(vkStr);
                if (vk != null) {
                    ItemStack stack = IAExpServices.rebuildStack(vk);
                    if (!stack.isEmpty()) return stack;
                }
            }
            String id = extractItemId(vkStr);
            Identifier identifier = Identifier.tryParse(id);
            if (identifier != null && Registries.ITEM.containsId(identifier)) {
                return new ItemStack(Registries.ITEM.get(identifier));
            }
        } catch (Throwable ignore) {}
        return ItemStack.EMPTY;
    }

    private static String extractItemId(String vkStr) {
        int idx = vkStr.indexOf(ItemVariantKey.SEPARATOR);
        return idx < 0 ? vkStr : vkStr.substring(0, idx);
    }

    /** 从变体键提取 NBT 指纹摘要（用于行内显示，帮助用户区分变体） */
    private static String extractNbtSummary(String vkStr) {
        int idx = vkStr.indexOf(ItemVariantKey.SEPARATOR);
        if (idx < 0 || idx >= vkStr.length() - 1) return "";
        String nbt = vkStr.substring(idx + 1);
        if (nbt.length() > 24) return nbt.substring(0, 23) + "...";
        return nbt;
    }
}