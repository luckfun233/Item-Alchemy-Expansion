package itemalchemy.expansion.client.util;

import itemalchemy.expansion.ItemAlchemyExpansion;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.screen.slot.Slot;
import net.pitan76.itemalchemy.client.screen.AlchemyTableScreen;
import net.pitan76.itemalchemy.gui.screen.AlchemyTableScreenHandler;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 客户端 GUI 渲染共享工具：边框绘制与悬停槽位反射读取。统一维护反射字段名与缓存，
 * 供 {@code AlchemyTableScreenShulkerPreview}、{@code AlchemyTableSlotMatchOverlay}、
 * {@code SetEmcScreen}、{@code RepriceConfirmScreen} 复用。
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

    /** 缓存的 getScreenHandlerOverride Method，首次使用时查找 */
    private static Method getHandlerMethod;
    private static boolean getHandlerMethodResolved = false;

    private GuiRenderUtil() {}

    /**
     * 绘制 1px 粗的矩形边框（4 条线）。调用前应已通过 {@code matrices.translate(0, 0, z)}
     * 设定所需的 z-level，边框与背景同 z。颜色为 ARGB 格式（如 {@code 0xFF505050}）。
     */
    public static void drawBorder(MatrixStack matrices, int x, int y, int width, int height, int color) {
        fill(matrices, x, y, x + width, y + 1, color);                   // top
        fill(matrices, x, y + height - 1, x + width, y + height, color);  // bottom
        fill(matrices, x, y, x + 1, y + height, color);                   // left
        fill(matrices, x + width - 1, y, x + width, y + height, color);   // right
    }

    /** 1.19.2 用 DrawableHelper.fill（MatrixStack 版本） */
    private static void fill(MatrixStack matrices, int x1, int y1, int x2, int y2, int color) {
        net.minecraft.client.gui.DrawableHelper.fill(matrices, x1, y1, x2, y2, color);
    }

    /**
     * 反射读取 {@link HandledScreen#focusedSlot}（intermediary: {@code field_2787}）。
     * Field 懒加载并缓存，查找/读取失败返回 null（调用方应静默跳过）。
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

    /**
     * 反射调用 {@link AlchemyTableScreen#getScreenHandlerOverride()} 获取 ScreenHandler。
     * Method 懒加载并缓存以避免每帧反射查找；调用失败返回 null（调用方应静默跳过）。
     */
    public static AlchemyTableScreenHandler getScreenHandler(AlchemyTableScreen screen) {
        Method m = resolveGetHandlerMethod();
        if (m == null) return null;
        try {
            return (AlchemyTableScreenHandler) m.invoke(screen);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 懒加载并缓存 AlchemyTableScreen.getScreenHandlerOverride 的反射 Method */
    private static Method resolveGetHandlerMethod() {
        if (getHandlerMethodResolved) return getHandlerMethod;
        try {
            getHandlerMethod = AlchemyTableScreen.class.getMethod("getScreenHandlerOverride");
        } catch (Throwable t) {
            ItemAlchemyExpansion.LOGGER.warn("[IAExp] Could not resolve AlchemyTableScreen.getScreenHandlerOverride; preview/overlay disabled",
                    t);
            getHandlerMethod = null;
        }
        getHandlerMethodResolved = true;
        return getHandlerMethod;
    }
}
