package itemalchemy.expansion.client;

import itemalchemy.expansion.client.util.GuiRenderUtil;
import itemalchemy.expansion.config.IAExpConfig;
import itemalchemy.expansion.config.IAExpConfigHolder;
import itemalchemy.expansion.nbt.ShulkerBoxSupport;
import itemalchemy.expansion.search.SearchMatcher;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import net.pitan76.itemalchemy.client.screen.AlchemyTableScreen;
import net.pitan76.itemalchemy.gui.inventory.ExtractInventory;
import net.pitan76.itemalchemy.gui.screen.AlchemyTableScreenHandler;

import java.util.List;
import java.util.Map;

/**
 * 转换桌提取槽搜索匹配覆盖渲染：
 * <ol>
 *   <li><b>右下角小标志</b>：对 SHULKER_MATCH 的槽位，在右下角画 8×8 缩小的第一个匹配物图标，
 *       提示玩家「这个潜影盒里有你搜的物品」。</li>
 *   <li><b>匹配列表 tooltip</b>：鼠标悬停 SHULKER_MATCH 槽位 + 非 Shift 时，
 *       在鼠标下方画一个小框，列出潜影盒内所有匹配物名称（去重）。</li>
 * </ol>
 *
 * <p><b>让出条件</b>：搜索为空（无搜索词）时不渲染；配置关闭时不渲染。
 * Shift 按下时不渲染匹配列表框（Shift 预览已显示内容物 + 红框标记）。</p>
 *
 * <p>由 {@code MixinSimpleInventoryScreen} 在 {@code renderOverride} RETURN 后调用，
 * 确保小标志画在槽位图标之上、且在原 tooltip 之后。</p>
 */
public final class AlchemyTableSlotMatchOverlay {

    /** 小标志尺寸（8×8，槽位 16×16 的右下角一半） */
    private static final int BADGE_SIZE = 8;
    /** 小标志 z 层级（高于槽位图标，低于 Shift 预览面板） */
    private static final int BADGE_Z = 200;

    private AlchemyTableSlotMatchOverlay() {}

    /**
     * 在 {@code SimpleInventoryScreen.renderOverride} RETURN 后触发。
     */
    public static void onAfterRender(AlchemyTableScreen screen, MatrixStack matrices, int mouseX, int mouseY) {
        try {
            IAExpConfig config = IAExpConfigHolder.get();
            if (!config.searchShulkerContents) return;
        } catch (Throwable t) {
            return;
        }

        AlchemyTableScreenHandler handler = GuiRenderUtil.getScreenHandler(screen);
        if (handler == null) return;
        ExtractInventory extractInv;
        try {
            extractInv = handler.extractInventory;
        } catch (Throwable t) {
            return;
        }
        if (extractInv == null) return;

        Map<Integer, SearchMatchCache.SlotMatch> matches = SearchMatchCache.computeSlotMatches(handler, extractInv);
        if (matches.isEmpty()) return; // 无匹配或空搜索

        int screenX = getScreenX(screen);
        int screenY = getScreenY(screen);
        if (screenX == Integer.MIN_VALUE || screenY == Integer.MIN_VALUE) return;

        for (Map.Entry<Integer, SearchMatchCache.SlotMatch> entry : matches.entrySet()) {
            int slotIndex = entry.getKey();
            SearchMatchCache.SlotMatch match = entry.getValue();
            if (match.matchType != SearchMatcher.MatchType.SHULKER_MATCH) continue;
            if (match.firstMatchedContent == null) continue; // 自身名匹配但无内容物匹配，不画小标志

            Slot slot;
            try {
                slot = handler.getSlot(slotIndex);
            } catch (Throwable t) {
                continue;
            }
            if (slot == null) continue;

            int slotScreenX = slot.x + screenX;
            int slotScreenY = slot.y + screenY;
            renderBadge(matrices, slotScreenX, slotScreenY, match.firstMatchedContent);
        }

        // Shift 时让出，由 Shift 预览显示内容物 + 红框标记
        if (!Screen.hasShiftDown()) {
            Slot hovered = GuiRenderUtil.getHoveredSlot(screen);
            if (hovered != null) {
                int hoveredIndex = hovered.id; // Slot.id 是 vanilla public 字段
                SearchMatchCache.SlotMatch hoverMatch = matches.get(hoveredIndex);
                if (hoverMatch != null && hoverMatch.matchType == SearchMatcher.MatchType.SHULKER_MATCH
                        && !hoverMatch.matchedContents.isEmpty()) {
                    renderMatchListTooltip(matrices, mouseX, mouseY, hoverMatch.matchedContents);
                }
            }
        }
    }

    /**
     * 在槽位右下角画 8×8 缩小的物品图标。
     *
     * <p>用矩阵 scale(0.5) 把 16×16 图标缩到 8×8，位置在槽位右下角 (slotX+9, slotY+9)。
     * z 提升到 BADGE_Z 确保在槽位图标之上。</p>
     */
    private static void renderBadge(MatrixStack matrices, int slotX, int slotY, ItemStack content) {
        matrices.push();
        try {
            matrices.translate(slotX + 9, slotY + 9, BADGE_Z);
            matrices.scale(0.5f, 0.5f, 1.0f);
            drawItemIcon(matrices, content, 0, 0);
        } finally {
            matrices.pop();
        }
    }

    /**
     * 在鼠标下方画一个匹配列表框，列出匹配物名称（去重）。
     *
     * <p>位置：鼠标右下方 (mouseX+12, mouseY+12)，边界检查确保在屏幕内。
     * 框内每行一个匹配物名称，最多显示 6 个，超出显示 "+N"。</p>
     */
    private static void renderMatchListTooltip(MatrixStack matrices, int mouseX, int mouseY, List<ItemStack> matchedContents) {
        MinecraftClient client = MinecraftClient.getInstance();
        int screenW = client.getWindow().getScaledWidth();
        int screenH = client.getWindow().getScaledHeight();

        // 按物品 id 去重，保留第一个名称
        java.util.LinkedHashMap<String, String> seen = new java.util.LinkedHashMap<>();
        for (ItemStack s : matchedContents) {
            if (s == null || s.isEmpty()) continue;
            String id = net.minecraft.util.registry.Registry.ITEM.getId(s.getItem()).toString();
            if (!seen.containsKey(id)) {
                seen.put(id, s.getName().getString());
            }
        }
        if (seen.isEmpty()) return;

        Text title = Text.translatable("itemalchemy-expansion.search.match_list_title");
        int titleWidth = client.textRenderer.getWidth(title);

        int maxLineWidth = titleWidth;
        java.util.List<String> names = new java.util.ArrayList<>(seen.values());
        int shownCount = Math.min(names.size(), 6);
        for (int i = 0; i < shownCount; i++) {
            int w = client.textRenderer.getWidth(names.get(i));
            if (w > maxLineWidth) maxLineWidth = w;
        }
        boolean overflow = names.size() > 6;
        if (overflow) {
            Text more = Text.translatable("itemalchemy-expansion.search.match_list_more", names.size() - 6);
            int w = client.textRenderer.getWidth(more);
            if (w > maxLineWidth) maxLineWidth = w;
        }

        int padding = 4;
        int lineHeight = client.textRenderer.fontHeight + 1;
        int boxWidth = maxLineWidth + padding * 2;
        int boxHeight = padding * 2 + lineHeight + (shownCount + (overflow ? 1 : 0)) * lineHeight;

        // 默认鼠标右下方，超出屏幕时翻到左侧 / 上侧
        int boxX = mouseX + 12;
        int boxY = mouseY + 12;
        if (boxX + boxWidth > screenW) boxX = mouseX - boxWidth - 12;
        if (boxY + boxHeight > screenH) boxY = mouseY - boxHeight - 12;
        if (boxX < 0) boxX = 0;
        if (boxY < 0) boxY = 0;

        matrices.push();
        try {
            matrices.translate(0, 0, 400); // 在普通 tooltip 之上

            net.minecraft.client.gui.DrawableHelper.fill(matrices, boxX, boxY, boxX + boxWidth, boxY + boxHeight, 0xF0101010);
            GuiRenderUtil.drawBorder(matrices, boxX, boxY, boxWidth, boxHeight, 0xFF505050);

            int textX = boxX + padding;
            int textY = boxY + padding;
            net.minecraft.client.gui.DrawableHelper.drawTextWithShadow(matrices, client.textRenderer, title, textX, textY, 0xFFFFD700);
            textY += lineHeight;

            for (int i = 0; i < shownCount; i++) {
                net.minecraft.client.gui.DrawableHelper.drawTextWithShadow(matrices, client.textRenderer, net.minecraft.text.Text.literal(names.get(i)), textX, textY, 0xFFFFFF);
                textY += lineHeight;
            }
            if (overflow) {
                Text more = Text.translatable("itemalchemy-expansion.search.match_list_more", names.size() - 6);
                net.minecraft.client.gui.DrawableHelper.drawTextWithShadow(matrices, client.textRenderer, more, textX, textY, 0xFFAAAAAA);
            }
        } finally {
            matrices.pop();
        }
    }

    /** 通过 searchBox 位置推算 screen.x（searchBox 在 x+85） */
    private static int getScreenX(AlchemyTableScreen screen) {
        try {
            if (screen.searchBox != null) return screen.searchBox.x - 85;
        } catch (Throwable ignored) {}
        return Integer.MIN_VALUE;
    }

    /** 通过 searchBox 位置推算 screen.y（searchBox 在 y+5） */
    private static int getScreenY(AlchemyTableScreen screen) {
        try {
            if (screen.searchBox != null) return screen.searchBox.y - 5;
        } catch (Throwable ignored) {}
        return Integer.MIN_VALUE;
    }
    /** 1.19.2 物品图标渲染（无 DrawContext，改走 RenderSystem 模型视图矩阵以支持 z/scale） */
    private static void drawItemIcon(MatrixStack matrices, ItemStack stack, int x, int y) {
        com.mojang.blaze3d.systems.RenderSystem.getModelViewStack().push();
        com.mojang.blaze3d.systems.RenderSystem.getModelViewStack().multiplyPositionMatrix(matrices.peek().getPositionMatrix());
        com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix();
        try {
            net.minecraft.client.MinecraftClient.getInstance().getItemRenderer().renderGuiItemIcon(stack, x, y);
        } finally {
            com.mojang.blaze3d.systems.RenderSystem.getModelViewStack().pop();
            com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix();
        }
    }
}