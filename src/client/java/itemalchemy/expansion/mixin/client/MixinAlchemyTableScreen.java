package itemalchemy.expansion.mixin.client;

import itemalchemy.expansion.client.AlchemyTableScreenShulkerPreview;
import itemalchemy.expansion.client.FilterModeClientNetwork;
import itemalchemy.expansion.search.IAlchemyTableScreenHandlerExt;
import itemalchemy.expansion.search.SearchFilterMode;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.pitan76.itemalchemy.client.screen.AlchemyTableScreen;
import net.pitan76.itemalchemy.gui.screen.AlchemyTableScreenHandler;
import net.pitan76.mcpitanlib.api.client.render.handledscreen.KeyEventArgs;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 拦截 {@link AlchemyTableScreen} 的 {@code keyPressed} / {@code keyReleased}，
 * 在 Shift 预览激活时消费 WASD / 方向键事件，阻止焦点被切换到搜索框。
 *
 * <p>原版 {@code AlchemyTableScreen.keyPressed} 在搜索框未聚焦时调用 {@code super.keyPressed(args)}，
 * mcpitanlib/vanilla 的 {@code HandledScreen} 会把焦点切换到下一个 widget（搜索框）；
 * 一旦搜索框获得焦点，{@code keyReleased} 中的 {@code PlayerRegisteredItemUtil.getItems()}
 * 会对变体键解析崩溃。</p>
 *
 * <p>策略：Shift 按下 + 预览功能已开启 + 搜索框未聚焦时，消费 WASD / 方向键的
 * {@code keyPressed} 和 {@code keyReleased} 事件（返回 true）。焦点导航由
 * {@link AlchemyTableScreenShulkerPreview#updateFocus} 通过 {@code InputUtil.isKeyPressed}
 * 轮询 GLFW 状态实现，不依赖事件传递，所以消费事件不影响导航。搜索框已聚焦时放行
 * （让搜索框正常处理方向键移动文本光标）。</p>
 *
 * <p><b>筛选按钮</b>：在 {@code initOverride} 末尾注入一个三档循环按钮（全部 / 仅物品 / 仅潜影盒），
 * 点击切换 filterMode、发送 C2S 包同步服务端、立即本地 {@code sortBySearch}。</p>
 *
 * <p><b>target 为模组类</b>：{@code AlchemyTableScreen} 是 Item Alchemy 的客户端类，
 * {@code keyPressed}/{@code keyReleased}/{@code initOverride} 不是 Minecraft 原版方法，{@code remap = false}。</p>
 */
@Mixin(value = AlchemyTableScreen.class, remap = false)
public abstract class MixinAlchemyTableScreen {

    @Shadow
    public TextFieldWidget searchBox;

    /** 筛选按钮引用（每次 initOverride 重建） */
    @Unique
    private ButtonWidget iaexp$filterButton;

    /**
     * 获取当前 ScreenHandler。
     *
     * <p>{@code getScreenHandlerOverride()} 是 mcpitanlib {@code SimpleHandledScreen} 上的
     * <b>public</b> 方法（mcpitanlib 自有类，名字不被 yarn/intermediary 重映射，dev 与 prod 一致），
     * 因此直接强转 {@code this} 调用即可，无需反射。</p>
     */
    private AlchemyTableScreenHandler iaexp$getHandler() {
        try {
            return ((AlchemyTableScreen) (Object) this).getScreenHandlerOverride();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 把控件加入 Screen。
     *
     * <p>{@code addDrawableChild_compatibility} 是 mcpitanlib {@code SimpleHandledScreen} 上的
     * <b>public</b> 方法，内部直接委托 vanilla {@code HandledScreen.addDrawableChild}。
     * 直接强转 {@code this} 调用即可：方法名是 mcpitanlib 自有（不重映射），描述符里的 vanilla 类引用
     * 由 loom 自动重映射，dev 与 prod 行为一致。{@link ClickableWidget} 满足泛型上界
     * {@code <T extends Element & Drawable & Selectable>}。</p>
     */
    private void iaexp$addDrawableChild(ClickableWidget widget) {
        try {
            ((AlchemyTableScreen) (Object) this).addDrawableChild_compatibility(widget);
        } catch (Throwable t) {
            itemalchemy.expansion.ItemAlchemyExpansion.LOGGER
                    .warn("[IAExp] Failed to add filter button via addDrawableChild_compatibility: {}", t.toString());
        }
    }

    // ====== 方向键拦截（Shift 预览激活时） ======

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true, remap = false)
    private void iaexp$interceptDirectionKeys(KeyEventArgs args, CallbackInfoReturnable<Boolean> cir) {
        // 只在 Shift 按下（预览激活条件之一）时拦截
        if (!Screen.hasShiftDown()) return;
        if (!AlchemyTableScreenShulkerPreview.isPreviewFeatureEnabled()) return;

        // 强制搜索框失焦，让方向键/WASD 控制预览焦点
        if (searchBox != null && searchBox.isFocused()) {
            searchBox.setFocused(false);
        }

        if (isDirectionKey(args.keyCode)) {
            // 消费事件，阻止 super.keyPressed 把焦点切换到搜索框
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "keyReleased", at = @At("HEAD"), cancellable = true, remap = false)
    private void iaexp$interceptDirectionKeyRelease(KeyEventArgs args, CallbackInfoReturnable<Boolean> cir) {
        if (!Screen.hasShiftDown()) return;
        if (!AlchemyTableScreenShulkerPreview.isPreviewFeatureEnabled()) return;

        if (isDirectionKey(args.keyCode)) {
            // 消费事件，阻止 keyReleased 触发搜索更新逻辑
            cir.setReturnValue(true);
        }
    }

    /** 判断是否为 WASD 或方向键 */
    private static boolean isDirectionKey(int keyCode) {
        return keyCode == GLFW.GLFW_KEY_W || keyCode == GLFW.GLFW_KEY_A
                || keyCode == GLFW.GLFW_KEY_S || keyCode == GLFW.GLFW_KEY_D
                || keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN
                || keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_RIGHT;
    }

    // ====== 筛选按钮注入 ======

    /**
     * 在 initOverride 末尾添加筛选按钮。按钮位于搜索框右侧（48×14），
     * 文字根据当前 filterMode 显示，点击切换 filterMode 并发包同步服务端、本地重排、更新按钮文字。
     */
    @Inject(method = "initOverride", at = @At("RETURN"), remap = false)
    private void iaexp$addFilterButton(CallbackInfo ci) {
        // 基于搜索框位置推算按钮坐标，垂直居中对齐
        int bx, by;
        if (searchBox != null) {
            bx = searchBox.getX() + searchBox.getWidth() + 3;
            by = searchBox.getY() - 2;
        } else {
            // searchBox 不可用时回退
            bx = 0;
            by = 0;
        }

        AlchemyTableScreenHandler handler = iaexp$getHandler();
        if (handler == null) return;
        SearchFilterMode current = (handler instanceof IAlchemyTableScreenHandlerExt)
                ? ((IAlchemyTableScreenHandlerExt) handler).iaexp$getFilterMode()
                : SearchFilterMode.ALL;

        iaexp$filterButton = ButtonWidget.builder(Text.literal(iaexp$labelFor(current)), btn -> {
            iaexp$onFilterButtonClicked(btn);
        })
                .dimensions(bx, by, 48, 14)
                .tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
                        Text.translatable("itemalchemy-expansion.filter_button.tooltip")))
                .build();

        iaexp$addDrawableChild(iaexp$filterButton);
    }

    /** 筛选按钮点击回调 */
    @Unique
    private void iaexp$onFilterButtonClicked(ButtonWidget btn) {
        AlchemyTableScreenHandler handler = iaexp$getHandler();
        if (handler == null) return;
        if (!(handler instanceof IAlchemyTableScreenHandlerExt)) return;
        IAlchemyTableScreenHandlerExt ext = (IAlchemyTableScreenHandlerExt) handler;

        SearchFilterMode newMode = ext.iaexp$cycleFilterMode();

        // 发包同步服务端，客户端独立运行时无网络则忽略
        try {
            FilterModeClientNetwork.send(newMode);
        } catch (Throwable ignored) {
        }

        // 客户端预测：立即本地重新搜索
        try {
            handler.index = 0;
            handler.sortBySearch();
        } catch (Throwable ignored) {
        }

        btn.setMessage(Text.literal(iaexp$labelFor(newMode)));
    }

    /** 根据筛选模式返回按钮显示文字（短词，适配 48px 宽） */
    @Unique
    private static String iaexp$labelFor(SearchFilterMode mode) {
        if (mode == null) return "?";
        switch (mode) {
            case DIRECT_ONLY:
                return Text.translatable("itemalchemy-expansion.filter_button.direct").getString();
            case SHULKER_ONLY:
                return Text.translatable("itemalchemy-expansion.filter_button.shulker").getString();
            case ALL:
            default:
                return Text.translatable("itemalchemy-expansion.filter_button.all").getString();
        }
    }
}
