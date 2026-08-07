package itemalchemy.expansion.client.util;

import itemalchemy.expansion.ItemAlchemyExpansion;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.slot.Slot;

import java.lang.reflect.Field;

/**
 * 客户端 GUI 渲染共享工具：边框绘制与悬停槽位反射读取。
 *
 * <p>提取自 {@code AlchemyTableScreenShulkerPreview}、{@code AlchemyTableSlotMatchOverlay}、
 * {@code SetEmcScreen}、{@code RepriceConfirmScreen} 中重复的 {@code drawBorder} 与
 * {@code getHoveredSlot} 实现，统一维护反射字段名与缓存。</p>
 */
public final class GuiRenderUtil {

    /**
     * {@link HandledScreen#focusedSlot} 的 intermediary 字段名。
     * <p>yarn 1.20.1+build.10 中 {@code focusedSlot}（鼠标当前悬停的槽位）→ {@code field_2787}。
     * 反射用此名读取，兼容无 refmap 的生产环境。</p>
     */
    public static final String HOVERED_SLOT_INTERMEDIARY = "field_2787";

    /** 缓存的反射 Field，首次使用时查找；查找失败置 null 表示不可用 */
    private static Field hoveredSlotField;
    private static boolean hoveredSlotFieldResolved = false;

    private GuiRenderUtil() {}

    /**
     * 绘制 1px 粗的矩形边框（4 条线）。
     *
     * <p>调用前应已通过 {@code matrices.translate(0, 0, z)} 设定所需的 z-level，
     * 边框与背景同 z。颜色为 ARGB 格式（如 {@code 0xFF505050}）。</p>
     *
     * @param context 当前 DrawContext
     * @param x       矩形左上角 x
     * @param y       矩形左上角 y
     * @param width   矩形宽度
     * @param height  矩形高度
     * @param color   ARGB 颜色
     */
    public static void drawBorder(DrawContext context, int x, int y, int width, int height, int color) {
        context.fill(x, y, x + width, y + 1, color);                   // top
        context.fill(x, y + height - 1, x + width, y + height, color);  // bottom
        context.fill(x, y, x + 1, y + height, color);                   // left
        context.fill(x + width - 1, y, x + width, y + height, color);   // right
    }

    /**
     * 反射读取 {@link HandledScreen#focusedSlot}（intermediary: {@code field_2787}）。
     *
     * <p>Field 对象懒加载并缓存，仅首次调用时查找。查找/读取失败返回 null（调用方应静默跳过）。</p>
     *
     * @param screen 当前 HandledScreen
     * @return 鼠标悬停的 Slot；不可用或异常时返回 null
     */
    public static Slot getHoveredSlot(HandledScreen<?> screen) {
        Field f = resolveHoveredSlotField();
        if (f == null) return null;
        try {
            return (Slot) f.get(screen);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 懒加载并缓存 {@link HandledScreen} 的 focusedSlot 反射 Field */
    private static Field resolveHoveredSlotField() {
        if (hoveredSlotFieldResolved) return hoveredSlotField;
        try {
            Field f = HandledScreen.class.getDeclaredField(HOVERED_SLOT_INTERMEDIARY);
            f.setAccessible(true);
            hoveredSlotField = f;
        } catch (Throwable t) {
            ItemAlchemyExpansion.LOGGER.warn("[IAExp] Could not resolve HandledScreen.{} (focusedSlot); hover-slot feature disabled",
                    HOVERED_SLOT_INTERMEDIARY, t);
            hoveredSlotField = null;
        }
        hoveredSlotFieldResolved = true;
        return hoveredSlotField;
    }
}
