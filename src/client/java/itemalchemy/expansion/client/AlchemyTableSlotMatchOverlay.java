package itemalchemy.expansion.client;

import itemalchemy.expansion.ItemAlchemyExpansion;
import itemalchemy.expansion.config.IAExpConfig;
import itemalchemy.expansion.config.IAExpConfigHolder;
import itemalchemy.expansion.nbt.ShulkerBoxSupport;
import itemalchemy.expansion.search.SearchMatcher;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import net.pitan76.itemalchemy.client.screen.AlchemyTableScreen;
import net.pitan76.itemalchemy.gui.inventory.ExtractInventory;
import net.pitan76.itemalchemy.gui.screen.AlchemyTableScreenHandler;

import java.lang.reflect.Field;
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

    /** HandledScreen.focusedSlot 的 intermediary 字段名（与 AlchemyTableScreenShulkerPreview 一致） */
    private static final String HOVERED_SLOT_INTERMEDIARY = "field_2787";
    private static Field hoveredSlotField;

    private AlchemyTableSlotMatchOverlay() {}

    /**
     * 在 {@code SimpleInventoryScreen.renderOverride} RETURN 后触发。
     *
     * @param screen 当前 AlchemyTableScreen
     * @param context DrawContext
     * @param mouseX 鼠标 X
     * @param mouseY 鼠标 Y
     */
    public static void onAfterRender(AlchemyTableScreen screen, DrawContext context, int mouseX, int mouseY) {
        // 1. 配置开关
        try {
            IAExpConfig config = IAExpConfigHolder.get();
            if (!config.searchShulkerContents) return;
        } catch (Throwable t) {
            return;
        }

        // 2. 获取 handler + extractInventory
        AlchemyTableScreenHandler handler;
        ExtractInventory extractInv;
        try {
            handler = (AlchemyTableScreenHandler) AlchemyTableScreen.class
                    .getMethod("getScreenHandlerOverride")
                    .invoke(screen);
            extractInv = handler.extractInventory;
        } catch (Throwable t) {
            return;
        }
        if (extractInv == null) return;

        // 3. 计算匹配
        Map<Integer, SearchMatchCache.SlotMatch> matches = SearchMatchCache.computeSlotMatches(handler, extractInv);
        if (matches.isEmpty()) return; // 无匹配或空搜索

        // 4. 渲染小标志
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
            renderBadge(context, slotScreenX, slotScreenY, match.firstMatchedContent);
        }

        // 5. 匹配列表 tooltip（鼠标悬停 + 非 Shift）
        if (!Screen.hasShiftDown()) {
            Slot hovered = getHoveredSlot(screen);
            if (hovered != null) {
                int hoveredIndex = hovered.id; // Slot.id 是 vanilla public 字段
                SearchMatchCache.SlotMatch hoverMatch = matches.get(hoveredIndex);
                if (hoverMatch != null && hoverMatch.matchType == SearchMatcher.MatchType.SHULKER_MATCH
                        && !hoverMatch.matchedContents.isEmpty()) {
                    renderMatchListTooltip(context, mouseX, mouseY, hoverMatch.matchedContents);
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
    private static void renderBadge(DrawContext context, int slotX, int slotY, ItemStack content) {
        context.getMatrices().push();
        try {
            context.getMatrices().translate(slotX + 9, slotY + 9, BADGE_Z);
            context.getMatrices().scale(0.5f, 0.5f, 1.0f);
            context.drawItem(content, 0, 0);
        } finally {
            context.getMatrices().pop();
        }
    }

    /**
     * 在鼠标下方画一个匹配列表框，列出匹配物名称（去重）。
     *
     * <p>位置：鼠标右下方 (mouseX+12, mouseY+12)，边界检查确保在屏幕内。
     * 框内每行一个匹配物名称，最多显示 6 个，超出显示 "+N"。</p>
     */
    private static void renderMatchListTooltip(DrawContext context, int mouseX, int mouseY, List<ItemStack> matchedContents) {
        MinecraftClient client = MinecraftClient.getInstance();
        int screenW = client.getWindow().getScaledWidth();
        int screenH = client.getWindow().getScaledHeight();

        // 去重名称（按物品 id 去重，保留第一个名称）
        java.util.LinkedHashMap<String, String> seen = new java.util.LinkedHashMap<>();
        for (ItemStack s : matchedContents) {
            if (s == null || s.isEmpty()) continue;
            String id = net.minecraft.registry.Registries.ITEM.getId(s.getItem()).toString();
            if (!seen.containsKey(id)) {
                seen.put(id, s.getName().getString());
            }
        }
        if (seen.isEmpty()) return;

        // 标题
        Text title = Text.translatable("itemalchemy-expansion.search.match_list_title");
        int titleWidth = client.textRenderer.getWidth(title);

        // 计算每行宽度
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

        // 位置：鼠标右下方
        int boxX = mouseX + 12;
        int boxY = mouseY + 12;
        // 边界检查
        if (boxX + boxWidth > screenW) boxX = mouseX - boxWidth - 12;
        if (boxY + boxHeight > screenH) boxY = mouseY - boxHeight - 12;
        if (boxX < 0) boxX = 0;
        if (boxY < 0) boxY = 0;

        context.getMatrices().push();
        try {
            context.getMatrices().translate(0, 0, 400); // 在普通 tooltip 之上

            // 背景
            context.fill(boxX, boxY, boxX + boxWidth, boxY + boxHeight, 0xF0101010);
            // 边框
            drawBorder(context, boxX, boxY, boxWidth, boxHeight, 0xFF505050);

            // 标题
            int textX = boxX + padding;
            int textY = boxY + padding;
            context.drawTextWithShadow(client.textRenderer, title, textX, textY, 0xFFFFD700);
            textY += lineHeight;

            // 匹配物名称
            for (int i = 0; i < shownCount; i++) {
                context.drawTextWithShadow(client.textRenderer, names.get(i), textX, textY, 0xFFFFFF);
                textY += lineHeight;
            }
            // 溢出提示
            if (overflow) {
                Text more = Text.translatable("itemalchemy-expansion.search.match_list_more", names.size() - 6);
                context.drawTextWithShadow(client.textRenderer, more, textX, textY, 0xFFAAAAAA);
            }
        } finally {
            context.getMatrices().pop();
        }
    }

    private static void drawBorder(DrawContext context, int x, int y, int w, int h, int color) {
        context.fill(x, y, x + w, y + 1, color);
        context.fill(x, y + h - 1, x + w, y + h, color);
        context.fill(x, y, x + 1, y + h, color);
        context.fill(x + w - 1, y, x + w, y + h, color);
    }

    /** 通过 searchBox 位置推算 screen.x（searchBox 在 x+85） */
    private static int getScreenX(AlchemyTableScreen screen) {
        try {
            if (screen.searchBox != null) return screen.searchBox.getX() - 85;
        } catch (Throwable ignored) {}
        return Integer.MIN_VALUE;
    }

    /** 通过 searchBox 位置推算 screen.y（searchBox 在 y+5） */
    private static int getScreenY(AlchemyTableScreen screen) {
        try {
            if (screen.searchBox != null) return screen.searchBox.getY() - 5;
        } catch (Throwable ignored) {}
        return Integer.MIN_VALUE;
    }

    /** 反射读取 HandledScreen.focusedSlot（与 AlchemyTableScreenShulkerPreview 相同逻辑） */
    private static Slot getHoveredSlot(AlchemyTableScreen screen) {
        Field f = resolveHoveredSlotField();
        if (f == null) return null;
        try {
            return (Slot) f.get(screen);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Field resolveHoveredSlotField() {
        if (hoveredSlotField != null) return hoveredSlotField;
        try {
            Field f = net.minecraft.client.gui.screen.ingame.HandledScreen.class
                    .getDeclaredField(HOVERED_SLOT_INTERMEDIARY);
            f.setAccessible(true);
            hoveredSlotField = f;
            return f;
        } catch (Throwable t) {
            ItemAlchemyExpansion.LOGGER.warn("[IAExp] Could not resolve HandledScreen.{} (focusedSlot); slot match overlay disabled",
                    HOVERED_SLOT_INTERMEDIARY, t);
            hoveredSlotField = null;
            return null;
        }
    }
}
