package itemalchemy.expansion.client;

import itemalchemy.expansion.ItemAlchemyExpansion;
import itemalchemy.expansion.client.util.GuiRenderUtil;
import itemalchemy.expansion.config.IAExpConfig;
import itemalchemy.expansion.config.IAExpConfigHolder;
import itemalchemy.expansion.nbt.ShulkerBoxSupport;
import itemalchemy.expansion.search.SearchMatcher;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import net.pitan76.itemalchemy.EMCManager;
import net.pitan76.itemalchemy.client.screen.AlchemyTableScreen;
import net.pitan76.itemalchemy.gui.screen.AlchemyTableScreenHandler;
import org.lwjgl.glfw.GLFW;

/**
 * 转换桌界面内置 Shift 预览：在 AlchemyTableScreen 渲染后，若 SHIFT 按下且
 * 鼠标悬停在提取槽的潜影盒上，绘制 9×3 内容物预览面板。
 *
 * <p>预览位置优先放在鼠标左上方（避开原版 tooltip 的默认右下方位置），空间不足时回退右下方。
 * 整体通过 {@code matrices.translate(0, 0, Z_LAYER)} 提升 z-level 到所有普通 UI 之上，
 * 避免被槽位图标 / tooltip 遮挡；数量文字额外 {@code translate(0, 0, 250)} 高于图标层。
 * EMC 用 {@link String#format} 预格式化为 String 再传入 {@link Text#translatable}，
 * 避免 {@code %,d} 格式化 long 参数时的渲染问题。</p>
 *
 * <p><b>让出条件</b>：配置关闭 / 已装 ShulkerBoxTooltip / 未按 Shift / 非潜影盒。</p>
 *
 * <p><b>焦点实现</b>：SHIFT 激活时可用 WASD / 方向键移动白色焦点方块，焦点格在面板上方
 * 显示物品名 + 单格 EMC + 总和。通过 {@link InputUtil#isKeyPressed} 轮询按键状态，
 * {@code MOVE_INTERVAL_MS} 节流避免焦点移动过快；搜索框聚焦时不响应方向键。</p>
 */
public final class AlchemyTableScreenShulkerPreview {

    private static final int COLS = 9;
    private static final int ROWS = 3;
    private static final int SLOT_SIZE = 18;
    private static final int PADDING = 4;
    /** 标题行高度（面板内顶部留出一行放标题） */
    private static final int TITLE_HEIGHT = 12;
    /**
     * 整体渲染层级。原版 tooltip 的 z 约 300~400，普通槽位图标 z=0。
     * 用 500 确保预览渲染在所有普通 UI 之上，避免被遮挡。
     */
    private static final int Z_LAYER = 500;
    /** 鼠标到预览面板的偏移量（增大到 16，给原版 tooltip 留出空间） */
    private static final int MOUSE_OFFSET = 16;

    // ====== 焦点状态（静态，跨帧保持） ======
    /** 焦点行：-1 表示未激活（预览未显示）；激活时为 0~ROWS-1 */
    private static int focusRow = -1;
    /** 焦点列：-1 表示未激活；激活时为 0~COLS-1 */
    private static int focusCol = -1;
    /** 上一次焦点移动时间（毫秒），用于按键重复节流 */
    private static long lastMoveTime = 0;
    /** 按住方向键时，每次移动的最小间隔（毫秒）。180ms ≈ 5.5 次/秒，可控 */
    private static final long MOVE_INTERVAL_MS = 180;
    /** 上一帧是否处于激活状态，用于检测从激活→失活的转换（重置焦点） */
    private static boolean wasActive = false;

    private AlchemyTableScreenShulkerPreview() {}

    /**
     * 判断内置 Shift 预览功能是否已启用（配置开启 + 未装 ShulkerBoxTooltip）。
     *
     * <p>供 {@code MixinAlchemyTableScreen} 在 {@code keyPressed}/{@code keyReleased} 中判断
     * 是否需要拦截方向键事件。不检查 Shift 是否按下（由调用方自行检查），
     * 也不检查悬停槽位（按键时可能还没渲染到槽位）。</p>
     */
    public static boolean isPreviewFeatureEnabled() {
        try {
            if (!IAExpConfigHolder.get().builtInShulkerPreview) return false;
        } catch (Throwable t) {
            return false;
        }
        try {
            if (FabricLoader.getInstance().isModLoaded("shulkerboxtooltip")) return false;
        } catch (Throwable ignored) {
            return false;
        }
        return true;
    }

    /**
     * 在 {@code SimpleInventoryScreen.renderOverride} 返回后（即 tooltip 渲染之后）触发，
     * 确保 Shift 预览面板渲染在原版 tooltip <b>之上</b>，不被遮挡。
     */
    public static void onAfterRender(AlchemyTableScreen screen, DrawContext context, int mouseX, int mouseY, float delta) {
        IAExpConfig config;
        try {
            config = IAExpConfigHolder.get();
        } catch (Throwable t) {
            return;
        }
        if (!config.builtInShulkerPreview) {
            deactivate();
            return;
        }

        // 已装 ShulkerBoxTooltip 时让出，避免双重预览
        try {
            if (FabricLoader.getInstance().isModLoaded("shulkerboxtooltip")) {
                deactivate();
                return;
            }
        } catch (Throwable ignored) {
            // 防御性
        }

        if (!Screen.hasShiftDown()) {
            deactivate();
            return;
        }

        Slot hovered = GuiRenderUtil.getHoveredSlot(screen);
        if (hovered == null || !hovered.hasStack()) {
            deactivate();
            return;
        }

        ItemStack shulkerBox = hovered.getStack();
        if (!ShulkerBoxSupport.isShulkerBox(shulkerBox)) {
            deactivate();
            return;
        }

        // 首次激活时把焦点初始化到 (0,0)
        if (!wasActive) {
            focusRow = 0;
            focusCol = 0;
            lastMoveTime = 0;
            wasActive = true;
        }

        updateFocus(screen);

        // 搜索上下文用于红框标记匹配内容物
        SearchMatcher.SearchContext searchCtx = null;
        AlchemyTableScreenHandler handler = GuiRenderUtil.getScreenHandler(screen);
        if (handler != null) {
            searchCtx = SearchMatchCache.getContext(handler);
        }

        try {
            renderPreview(context, mouseX, mouseY, shulkerBox, searchCtx);
        } catch (Throwable t) {
            ItemAlchemyExpansion.LOGGER.warn("[IAExp] Failed to render shulker box preview", t);
        }
    }

    /** 失活：重置焦点状态，避免下次激活时残留旧位置 */
    private static void deactivate() {
        wasActive = false;
        focusRow = -1;
        focusCol = -1;
    }

    /**
     * 通过 {@link InputUtil#isKeyPressed} 轮询按键状态，移动焦点。
     *
     * <p>用 {@code MOVE_INTERVAL_MS} 节流：按住按键时每 180ms 移动一格，
     * 避免焦点移动过快。搜索框聚焦时不响应方向键（让搜索框正常处理光标移动）。</p>
     */
    private static void updateFocus(AlchemyTableScreen screen) {
        long now = System.currentTimeMillis();
        if (now - lastMoveTime < MOVE_INTERVAL_MS) return;

        MinecraftClient client = MinecraftClient.getInstance();
        long handle = client.getWindow().getHandle();

        // 搜索框聚焦时不响应方向键，让搜索框正常处理光标移动
        boolean searchFocused = false;
        try {
            if (screen.searchBox != null) searchFocused = screen.searchBox.isFocused();
        } catch (Throwable ignored) {}

        if (searchFocused) return;

        boolean moved = false;
        int newRow = focusRow;
        int newCol = focusCol;

        // WASD 或方向键，循环环绕
        if (InputUtil.isKeyPressed(handle, GLFW.GLFW_KEY_W) || InputUtil.isKeyPressed(handle, GLFW.GLFW_KEY_UP)) {
            newRow = (focusRow - 1 + ROWS) % ROWS;
            moved = true;
        } else if (InputUtil.isKeyPressed(handle, GLFW.GLFW_KEY_S) || InputUtil.isKeyPressed(handle, GLFW.GLFW_KEY_DOWN)) {
            newRow = (focusRow + 1) % ROWS;
            moved = true;
        } else if (InputUtil.isKeyPressed(handle, GLFW.GLFW_KEY_A) || InputUtil.isKeyPressed(handle, GLFW.GLFW_KEY_LEFT)) {
            newCol = (focusCol - 1 + COLS) % COLS;
            moved = true;
        } else if (InputUtil.isKeyPressed(handle, GLFW.GLFW_KEY_D) || InputUtil.isKeyPressed(handle, GLFW.GLFW_KEY_RIGHT)) {
            newCol = (focusCol + 1) % COLS;
            moved = true;
        }

        if (moved) {
            focusRow = newRow;
            focusCol = newCol;
            lastMoveTime = now;
        }
    }

    /**
     * 绘制 9×3 预览面板（含标题行 + 焦点高亮 + 焦点格物品名标签）。
     */
    private static void renderPreview(DrawContext context, int mouseX, int mouseY, ItemStack shulkerBox,
                                      SearchMatcher.SearchContext searchCtx) {
        // 单次 NBT 解析同时获取内容物列表和 EMC 总和，避免双重解析
        ShulkerBoxSupport.ContentsAndEmc cae = ShulkerBoxSupport.getContentsAndSumEmc(shulkerBox);
        ItemStack[] contents = cae.contents;
        int bgWidth = COLS * SLOT_SIZE + PADDING * 2;
        int bgHeight = PADDING + TITLE_HEIGHT + 2 + ROWS * SLOT_SIZE + PADDING;

        MinecraftClient client = MinecraftClient.getInstance();
        int screenW = client.getWindow().getScaledWidth();
        int screenH = client.getWindow().getScaledHeight();

        // 优先放在鼠标左上方，避开原版 tooltip 的默认右下方位置
        int x = mouseX - bgWidth - MOUSE_OFFSET;
        int y = mouseY - bgHeight - MOUSE_OFFSET;
        // 左上方空间不足时回退到鼠标右下方
        if (x < 0) x = mouseX + MOUSE_OFFSET;
        if (y < 0) y = mouseY + MOUSE_OFFSET;
        if (x + bgWidth > screenW) x = screenW - bgWidth;
        if (y + bgHeight > screenH) y = screenH - bgHeight;
        if (x < 0) x = 0;
        if (y < 0) y = 0;

        // 提升 z-level 到所有普通 UI 之上
        context.getMatrices().push();
        context.getMatrices().translate(0, 0, Z_LAYER);

        try {
            // 背景 high alpha 避免下方图标透过来
            context.fill(x, y, x + bgWidth, y + bgHeight, 0xF0101010);
            GuiRenderUtil.drawBorder(context, x, y, bgWidth, bgHeight, 0xFF505050);

            // 标题：潜影盒名称 + EMC 之和，复用已解析的 cae.sumEmc
            String sumEmcStr = String.format("%,d", cae.sumEmc);
            Text title = Text.translatable("itemalchemy-expansion.shulker_box.preview_title",
                    shulkerBox.getName(), sumEmcStr);
            context.drawTextWithShadow(client.textRenderer, title, x + PADDING, y + PADDING, 0xFFFFFF);

            int gridY = y + PADDING + TITLE_HEIGHT + 2;

            // 红框标记需配置开启 + 有非空搜索上下文
            boolean redFrameEnabled = false;
            if (searchCtx != null && !searchCtx.isEmpty()) {
                try {
                    redFrameEnabled = IAExpConfigHolder.get().shulkerMatchRedFrame;
                } catch (Throwable t) {
                    redFrameEnabled = false;
                }
            }

            for (int row = 0; row < ROWS; row++) {
                for (int col = 0; col < COLS; col++) {
                    int idx = row * COLS + col;
                    int sx = x + PADDING + col * SLOT_SIZE;
                    int sy = gridY + row * SLOT_SIZE;

                    context.fill(sx, sy, sx + SLOT_SIZE, sy + SLOT_SIZE, 0x80202020);
                    context.fill(sx, sy, sx + SLOT_SIZE, sy + 1, 0xFF303030);
                    context.fill(sx, sy + SLOT_SIZE - 1, sx + SLOT_SIZE, sy + SLOT_SIZE, 0xFF303030);
                    context.fill(sx, sy, sx + 1, sy + SLOT_SIZE, 0xFF303030);
                    context.fill(sx + SLOT_SIZE - 1, sy, sx + SLOT_SIZE, sy + SLOT_SIZE, 0xFF303030);

                    ItemStack item = contents[idx];
                    if (item.isEmpty()) continue;

                    context.drawItem(item, sx + 1, sy + 1);

                    // 数量文字提升 z 到图标之上，避免被同一格图标的高光层遮挡
                    if (item.getCount() > 1) {
                        String countText = String.valueOf(item.getCount());
                        context.getMatrices().push();
                        context.getMatrices().translate(0, 0, 250);
                        context.drawTextWithShadow(client.textRenderer, countText,
                                sx + SLOT_SIZE - client.textRenderer.getWidth(countText) - 1,
                                sy + SLOT_SIZE - client.textRenderer.fontHeight - 1,
                                0xFFFFFF);
                        context.getMatrices().pop();
                    }

                    // 焦点格由白框覆盖优先级更高，红框只画在非焦点格上
                    if (redFrameEnabled && (row != focusRow || col != focusCol)
                            && SearchMatcher.matchesContentItem(item, searchCtx)) {
                        renderRedFrame(context, sx, sy);
                    }
                }
            }

            if (focusRow >= 0 && focusCol >= 0) {
                renderFocus(context, client, x, gridY, bgWidth, bgHeight, screenW, contents);
            }
        } finally {
            context.getMatrices().pop();
        }
    }

    /**
     * 渲染焦点：白色边框 + 焦点格物品名标签。
     *
     * <p>标签显示在面板上方（空间不足时下方），含物品名 + 单格 EMC + 总和。</p>
     */
    private static void renderFocus(DrawContext context, MinecraftClient client,
                                    int panelX, int gridY, int panelWidth, int panelHeight,
                                    int screenW, ItemStack[] contents) {
        int fsx = panelX + PADDING + focusCol * SLOT_SIZE;
        int fsy = gridY + focusRow * SLOT_SIZE;
        int fcx = fsx + SLOT_SIZE;
        int fcy = fsy + SLOT_SIZE;

        // 焦点边框提升 z 到所有内容物之上
        context.getMatrices().push();
        context.getMatrices().translate(0, 0, 300);
        int white = 0xFFFFFFFF;
        // 2px 粗边框，外延 1px
        context.fill(fsx - 1, fsy - 1, fcx + 1, fsy, white);
        context.fill(fsx - 1, fcy, fcx + 1, fcy + 1, white);
        context.fill(fsx - 1, fsy, fsx, fcy, white);
        context.fill(fcx, fsy, fcx + 1, fcy, white);
        context.getMatrices().pop();

        int focusIdx = focusRow * COLS + focusCol;
        if (focusIdx >= contents.length) return;
        ItemStack focused = contents[focusIdx];
        if (focused.isEmpty()) return;

        String name = focused.getName().getString();
        long itemEmc = EMCManager.get(focused.getItem());
        long totalEmc = itemEmc * focused.getCount();
        String emcLine = (itemEmc > 0)
                ? String.format("EMC: %,d × %d = %,d", itemEmc, focused.getCount(), totalEmc)
                : String.format("EMC: 0 × %d = 0（无 EMC）", focused.getCount());

        int nameWidth = client.textRenderer.getWidth(name);
        int emcWidth = client.textRenderer.getWidth(emcLine);
        int labelW = Math.max(nameWidth, emcWidth) + 8;
        int labelH = 24;
        int labelX = fsx + SLOT_SIZE / 2 - labelW / 2;
        int panelY = gridY - PADDING - TITLE_HEIGHT - 2;
        int labelY = panelY - labelH - 2;
        if (labelY < 0) {
            labelY = panelY + panelHeight + 2;
        }
        if (labelX < 0) labelX = 0;
        if (labelX + labelW > screenW) labelX = screenW - labelW;

        // 标签背景与文字提升 z 到焦点边框之上
        context.getMatrices().push();
        context.getMatrices().translate(0, 0, 350);
        context.fill(labelX, labelY, labelX + labelW, labelY + labelH, 0xF0101010);
        GuiRenderUtil.drawBorder(context, labelX, labelY, labelW, labelH, 0xFF505050);
        context.drawTextWithShadow(client.textRenderer, name, labelX + 4, labelY + 4, 0xFFFFFF);
        context.drawTextWithShadow(client.textRenderer, emcLine, labelX + 4, labelY + 14, 0xFFFFD700);
        context.getMatrices().pop();
    }

    /**
     * 绘制红框标记匹配搜索词的内容物格子。
     *
     * <p>z=260：高于物品图标(z=0)与数量文字(z=250)，低于焦点白框(z=300)。
     * 焦点格不调用本方法，确保焦点白框优先级更高。</p>
     */
    private static void renderRedFrame(DrawContext context, int sx, int sy) {
        context.getMatrices().push();
        context.getMatrices().translate(0, 0, 260);
        int red = 0xFFFF3030;
        // 2px 粗边框，外延 1px
        context.fill(sx - 1, sy - 1, sx + SLOT_SIZE + 1, sy, red);                       // top
        context.fill(sx - 1, sy + SLOT_SIZE, sx + SLOT_SIZE + 1, sy + SLOT_SIZE + 1, red); // bottom
        context.fill(sx - 1, sy, sx, sy + SLOT_SIZE, red);                               // left
        context.fill(sx + SLOT_SIZE, sy, sx + SLOT_SIZE + 1, sy + SLOT_SIZE, red);       // right
        context.getMatrices().pop();
    }
}
