package itemalchemy.expansion.client;

import itemalchemy.expansion.IAExpServices;
import itemalchemy.expansion.client.util.GuiRenderUtil;
import itemalchemy.expansion.nbt.ItemVariantKey;
import itemalchemy.expansion.network.AutoEmcStore;
import itemalchemy.expansion.network.PreciseEmcStore;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「重新定价」逐个选择对话框：玩家首次开启「配方自动定价」并存在手动定价时弹出一次。
 *
 * <p>由 {@code SetEmcClientNetwork} 收到 {@code reprice_candidates} S2C 包（通用层 + 精确层候选，
 * 含旧 EMC）后打开。界面为可滚动列表，每行一个候选，玩家可逐个勾选「重算 / 保留」。
 * 勾选条目会从对应存储移除并触发服务端重算自动定价；未勾选保留原手动值（手动优先级 &gt; 自动）。</p>
 *
 * <p>无论选什么，服务端都会置 {@code autoPricingRepricePromptShown=true} 写盘，对话框只弹一次。
 * 玩家想再次触发可用命令 {@code /itemalchemy-expansion reprice}。</p>
 *
 * <p>「新」EMC 来自客户端同步的 {@link AutoEmcStore}（仅用于预览），实际重算值由服务端
 * {@code RecipeAutoPricer} 决定；无自动值时只显示「旧」。</p>
 *
 * <p>实现说明：用自包含的滚动列表（{@code enableScissor} + {@code mouseScrolled}）而非
 * {@code EntryListWidget}，避免对 Minecraft 原版类字段/方法签名的依赖（本项目无 refmap）。
 * 覆写 {@link #shouldPause()} 返回 true，与 {@link SetEmcScreen} 一致。</p>
 */
public class RepriceConfirmScreen extends Screen {

    /** 面板宽度（按屏幕宽度夹紧，见 {@link #layout()}） */
    private static final int PANEL_W = 360;
    /** 面板高度（按屏幕高度夹紧） */
    private static final int PANEL_H = 240;
    /** 列表行高 */
    private static final int ROW_H = 22;
    /** 复选框边长 */
    private static final int CHECKBOX = 12;
    /** 标题/说明区高度（列表顶距面板顶） */
    private static final int LIST_TOP_OFFSET = 44;
    /** 按钮区高度（列表底距面板底） */
    private static final int LIST_BOTTOM_OFFSET = 38;

    /** 候选条目列表 */
    private final List<RepriceEntry> entries;
    /** 当前滚动偏移（像素，向下为正） */
    private int scrollOffset = 0;

    // 面板几何（在 init 中按当前窗口尺寸计算）
    private int panelX, panelY, panelW, panelH;

    public RepriceConfirmScreen(List<RepriceEntry> entries) {
        super(Text.translatable("itemalchemy-expansion.reprice.title"));
        this.entries = groupByItemId(entries);
    }

    /**
     * 对精确层候选按 itemId 分组：同 ID 的多个变体合并为一行，显示一个代表并标注变体数量。
     * 通用层候选（vkStr=null）每个 itemId 本身只有一条，保持不变。
     *
     * <p>合并后 {@link RepriceEntry#vkStr} 为代表的变体键，{@link RepriceEntry#variantCount}
     * 为该 ID 下变体总数（>=1）。勾选时整组一起重算（{@link #onConfirm} 按 itemId 发送所有变体）。</p>
     */
    private static List<RepriceEntry> groupByItemId(List<RepriceEntry> raw) {
        if (raw == null || raw.isEmpty()) return new ArrayList<>();
        List<RepriceEntry> result = new ArrayList<>();
        // 通用层直接保留，精确层按 itemId 分组
        Map<String, List<RepriceEntry>> preciseGroups = new LinkedHashMap<>();
        for (RepriceEntry e : raw) {
            if (e.isPrecise()) {
                preciseGroups.computeIfAbsent(e.itemId, k -> new ArrayList<>()).add(e);
            } else {
                result.add(e);
            }
        }
        for (Map.Entry<String, List<RepriceEntry>> grp : preciseGroups.entrySet()) {
            List<RepriceEntry> group = grp.getValue();
            if (group.size() == 1) {
                // 单个变体：直接加入，不标注数量
                result.add(group.get(0));
            } else {
                // 多个变体：取第一个作为代表，记录变体数量
                RepriceEntry representative = group.get(0);
                representative.variantCount = group.size();
                // 收集同组所有 vkStr 供整组重算
                List<String> allVks = new ArrayList<>(group.size());
                for (RepriceEntry e : group) allVks.add(e.vkStr);
                representative.allVkStrs = allVks;
                result.add(representative);
            }
        }
        return result;
    }

    /**
     * 一条重新定价候选。
     *
     * <p>通用层（{@code vkStr=null}，按 itemId 存）或精确层（{@code vkStr=变体键}，按变体键存）。
     * {@code recompute} 为玩家选择：true=重算（从存储移除并重定价），false=保留手动值。</p>
     */
    public static final class RepriceEntry {
        /** 物品 ID（查图标/名称/通用层 EMC） */
        public final String itemId;
        /** 精确层变体键；通用层为 null */
        public final String vkStr;
        /** 当前手动 EMC（旧值） */
        public final long oldEmc;
        /** 显示用图标（解析失败为空堆） */
        public final ItemStack stack;
        /** 玩家选择：true=重算，false=保留。默认勾选（重算） */
        public boolean recompute = true;
        /** 该 itemId 下变体总数（精确层分组后 >1 表示合并显示，=1 表示单个变体） */
        public int variantCount = 1;
        /** 同组所有变体键（仅精确层分组后 >1 时非 null，用于整组重算） */
        public List<String> allVkStrs = null;

        private RepriceEntry(String itemId, String vkStr, long oldEmc) {
            this.itemId = itemId;
            this.vkStr = vkStr;
            this.oldEmc = oldEmc;
            this.stack = resolveStack(itemId, vkStr);
        }

        /** 通用层候选 */
        public static RepriceEntry general(String itemId, long oldEmc) {
            return new RepriceEntry(itemId, null, oldEmc);
        }

        /** 精确层候选（从变体键提取 itemId） */
        public static RepriceEntry precise(String vkStr, long oldEmc) {
            return new RepriceEntry(extractItemId(vkStr), vkStr, oldEmc);
        }

        /** 是否为精确层候选 */
        public boolean isPrecise() {
            return vkStr != null;
        }

        /** 显示名：优先物品名，回退 itemId */
        public String displayName() {
            try {
                if (!stack.isEmpty()) {
                    String n = stack.getName().getString();
                    if (n != null && !n.isEmpty()) return n;
                }
            } catch (Throwable ignore) {}
            return itemId;
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
        int btnW = 90;
        int gap = 6;
        int totalW = btnW * 3 + gap * 2;
        int startX = panelX + (panelW - totalW) / 2;

        addDrawableChild(ButtonWidget.builder(
                Text.translatable("itemalchemy-expansion.reprice.recompute_all"),
                b -> setAllRecompute(true))
                .dimensions(startX, btnY, btnW, 20).build());

        addDrawableChild(ButtonWidget.builder(
                Text.translatable("itemalchemy-expansion.reprice.keep_all"),
                b -> setAllRecompute(false))
                .dimensions(startX + btnW + gap, btnY, btnW, 20).build());

        addDrawableChild(ButtonWidget.builder(
                Text.translatable("itemalchemy-expansion.reprice.confirm_selection"),
                b -> onConfirm())
                .dimensions(startX + (btnW + gap) * 2, btnY, btnW, 20).build());
    }

    /** 按当前窗口尺寸计算面板与列表几何（夹紧到屏幕内） */
    private void layout() {
        panelW = Math.min(PANEL_W, this.width - 20);
        panelH = Math.min(PANEL_H, this.height - 20);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
    }

    // ============ 交互 ============

    /** 点击列表行切换该行勾选状态 */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && hitListRow(mouseX, mouseY) >= 0) {
            int idx = hitListRow(mouseX, mouseY);
            entries.get(idx).recompute = !entries.get(idx).recompute;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** 滚轮滚动列表 */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        int listTop = panelY + LIST_TOP_OFFSET;
        int listBottom = panelY + panelH - LIST_BOTTOM_OFFSET;
        int listHeight = listBottom - listTop;
        int totalHeight = entries.size() * ROW_H;
        int maxScroll = Math.max(0, totalHeight - listHeight);
        if (maxScroll <= 0) return super.mouseScrolled(mouseX, mouseY, amount);
        scrollOffset -= (int) (amount * ROW_H);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
        return true;
    }

    /** 返回点击命中的行索引（-1 表示未命中列表区域） */
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

    /** 设置全部条目的勾选状态 */
    private void setAllRecompute(boolean recompute) {
        for (RepriceEntry e : entries) e.recompute = recompute;
    }

    /** 发送勾选「重算」的条目并关闭 */
    private void onConfirm() {
        List<String> generalIds = new ArrayList<>();
        List<String> preciseVkStrs = new ArrayList<>();
        for (RepriceEntry e : entries) {
            if (!e.recompute) continue;
            if (e.isPrecise()) {
                // 分组后的精确层条目：如果有 allVkStrs，整组一起发送；否则只发送代表
                if (e.allVkStrs != null) {
                    preciseVkStrs.addAll(e.allVkStrs);
                } else {
                    preciseVkStrs.add(e.vkStr);
                }
            } else {
                generalIds.add(e.itemId);
            }
        }
        SetEmcClientNetwork.sendRepriceSelective(generalIds, preciseVkStrs);
        this.close();
    }

    // ============ 渲染 ============

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context);

        int listTop = panelY + LIST_TOP_OFFSET;
        int listBottom = panelY + panelH - LIST_BOTTOM_OFFSET;
        int listLeft = panelX + 10;
        int listRight = panelX + panelW - 14;
        int listWidth = listRight - listLeft;

        context.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xC0101010);
        GuiRenderUtil.drawBorder(context, panelX, panelY, panelW, panelH, 0xFF404040);

        context.drawCenteredTextWithShadow(this.textRenderer, this.title,
                this.width / 2, panelY + 10, 0xFFFFFF);
        context.drawText(this.textRenderer,
                Text.translatable("itemalchemy-expansion.reprice.selective_desc"),
                listLeft, panelY + 26, 0xA0A0A0, false);

        context.fill(listLeft, listTop, listRight, listBottom, 0x60000000);
        GuiRenderUtil.drawBorder(context, listLeft, listTop, listWidth, listBottom - listTop, 0xFF303030);

        if (entries.isEmpty()) {
            // 防御：服务端无候选时不弹窗，此处兜底显示
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.translatable("itemalchemy-expansion.reprice.empty"),
                    (listLeft + listRight) / 2, (listTop + listBottom) / 2 - 4, 0xA0A0A0);
        } else {
            // 裁剪到列表区域
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
                Text.translatable("itemalchemy-expansion.reprice.hint2"),
                listLeft, panelY + panelH - 16, 0x707070, false);

        super.render(context, mouseX, mouseY, delta);
    }

    private void drawRow(DrawContext context, RepriceEntry e, int listLeft, int listRight, int rowY, boolean hovered) {
        int textY = rowY + (ROW_H - 8) / 2;
        if (hovered) {
            context.fill(listLeft + 1, rowY, listRight - 1, rowY + ROW_H, 0x30FFFFFF);
        }
        drawCheckbox(context, listLeft + 2, rowY + (ROW_H - CHECKBOX) / 2, e.recompute);
        context.drawItem(e.stack, listLeft + 20, rowY + (ROW_H - 16) / 2);
        // 名称截断到 16 字符
        String name = e.displayName();
        if (name.length() > 16) name = name.substring(0, 15) + "...";
        context.drawText(this.textRenderer, name, listLeft + 42, textY, 0xFFFFFF, false);
        if (e.isPrecise()) {
            int nameW = this.textRenderer.getWidth(name);
            int tagX = listLeft + 42 + nameW + 6;
            Text precise = Text.translatable("itemalchemy-expansion.reprice.layer_precise");
            context.drawText(this.textRenderer, precise, tagX, textY, 0x55FFFF, false);
            // 同 ID 多变体时标注数量
            if (e.variantCount > 1) {
                int preciseW = this.textRenderer.getWidth(precise);
                Text count = Text.translatable("itemalchemy-expansion.reprice.variant_count",
                        e.variantCount);
                context.drawText(this.textRenderer, count, tagX + preciseW + 4, textY, 0xFFAA00, false);
            }
        }
        drawEmc(context, e, listRight, textY);
    }

    /** 渲染复选框：勾选=绿底白心，未勾选=暗底空心 */
    private static void drawCheckbox(DrawContext context, int x, int y, boolean checked) {
        int fill = checked ? 0xFF208020 : 0xFF1A1A1A;
        int border = checked ? 0xFF30C030 : 0xFF707070;
        context.fill(x, y, x + CHECKBOX, y + CHECKBOX, fill);
        GuiRenderUtil.drawBorder(context, x, y, CHECKBOX, CHECKBOX, border);
        if (checked) {
            // 用白色实心方块作勾选标记，避免依赖字体字形
            context.fill(x + 3, y + 3, x + CHECKBOX - 3, y + CHECKBOX - 3, 0xFFFFFFFF);
        }
    }

    /** 右对齐渲染「旧: X」与可选「→ 新: Y」；新值来自客户端同步的 {@link AutoEmcStore} */
    private void drawEmc(DrawContext context, RepriceEntry e, int rightX, int y) {
        Text oldT = Text.translatable("itemalchemy-expansion.reprice.old_emc", String.valueOf(e.oldEmc));
        Long newEmc = e.isPrecise() ? AutoEmcStore.getPrecise(e.vkStr) : AutoEmcStore.getGeneral(e.itemId);
        Text newT = newEmc != null
                ? Text.translatable("itemalchemy-expansion.reprice.new_emc", String.valueOf(newEmc))
                : null;

        int oldW = this.textRenderer.getWidth(oldT);
        int sepW = this.textRenderer.getWidth("  ");
        int newW = newT == null ? 0 : this.textRenderer.getWidth(newT);
        int groupW = oldW + (newT == null ? 0 : sepW + newW);
        int startX = rightX - groupW - 4;

        // 旧值颜色：勾选=黄色（将被重算），未勾选=灰色（保留）
        int oldColor = e.recompute ? 0xFFFFE040 : 0xFFA0A0A0;
        context.drawText(this.textRenderer, oldT, startX, y, oldColor, false);
        if (newT != null) {
            context.drawText(this.textRenderer, newT, startX + oldW + sepW, y, 0xFF40E060, false);
        }
    }

    /** 渲染滚动条（仅在内容超出列表高度时） */
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

    /**
     * 根据 itemId / 变体键解析 ItemStack（用于显示图标，带 NBT 还原）。
     *
     * <p>通用层候选（vkStr=null）会尝试从客户端同步的精确层镜像中查找该 itemId 下的
     * 任意变体键，用变体键重建带 NBT 的 ItemStack，避免药水/附魔书等依赖 NBT 渲染图标的
     * 物品显示为紫黑色缺失贴图。找不到才回退到不带 NBT 的 ItemStack。</p>
     */
    private static ItemStack resolveStack(String itemId, String vkStr) {
        try {
            if (vkStr != null && !vkStr.isEmpty()) {
                ItemVariantKey vk = ItemVariantKey.fromStorageString(vkStr);
                if (vk != null) {
                    ItemStack stack = IAExpServices.rebuildStack(vk);
                    if (!stack.isEmpty()) return stack;
                }
            }
            // 通用层候选：从精确层镜像中找该 itemId 下的任意变体键，还原带 NBT 的图标
            String sampleVk = findSampleVariantKey(itemId);
            if (sampleVk != null) {
                ItemVariantKey vk = ItemVariantKey.fromStorageString(sampleVk);
                if (vk != null) {
                    ItemStack stack = IAExpServices.rebuildStack(vk);
                    if (!stack.isEmpty()) return stack;
                }
            }
            // 回退：创建不带 NBT 的 ItemStack
            Identifier id = Identifier.tryParse(itemId);
            if (id != null && Registries.ITEM.containsId(id)) {
                return new ItemStack(Registries.ITEM.get(id));
            }
        } catch (Throwable ignore) {}
        return ItemStack.EMPTY;
    }

    /**
     * 从客户端同步的精确层镜像中查找该 itemId 下的任意变体键。
     *
     * <p>优先查 {@link PreciseEmcStore}（玩家手动精确），再查 {@link AutoEmcStore}（自动精确）。
     * 都没有时返回 null（调用方回退到不带 NBT 的 ItemStack）。</p>
     */
    private static String findSampleVariantKey(String itemId) {
        if (itemId == null || itemId.isEmpty()) return null;
        String prefix = itemId + ItemVariantKey.SEPARATOR;
        // 先查玩家手动精确层
        for (String key : PreciseEmcStore.snapshot().keySet()) {
            if (key.startsWith(prefix) || key.equals(itemId)) return key;
        }
        // 再查自动精确层
        for (String key : AutoEmcStore.snapshotPrecise().keySet()) {
            if (key.startsWith(prefix) || key.equals(itemId)) return key;
        }
        return null;
    }

    /** 从变体键存储串提取 itemId（兼容纯 ID 与 {@code itemId\u0001nbt} 格式） */
    private static String extractItemId(String vkStr) {
        int idx = vkStr.indexOf(ItemVariantKey.SEPARATOR);
        return idx < 0 ? vkStr : vkStr.substring(0, idx);
    }
}
