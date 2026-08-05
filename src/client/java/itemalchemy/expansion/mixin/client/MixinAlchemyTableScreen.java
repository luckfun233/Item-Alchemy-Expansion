package itemalchemy.expansion.mixin.client;

import itemalchemy.expansion.client.AlchemyTableScreenShulkerPreview;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.pitan76.itemalchemy.client.screen.AlchemyTableScreen;
import net.pitan76.mcpitanlib.api.client.render.handledscreen.KeyEventArgs;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 拦截 {@link AlchemyTableScreen} 的 {@code keyPressed} / {@code keyReleased}，
 * 在 Shift 预览激活时消费 WASD / 方向键事件，阻止焦点被切换到搜索框。
 *
 * <p><b>问题根因</b>：原版 {@code AlchemyTableScreen.keyPressed} 在搜索框未聚焦时调用
 * {@code super.keyPressed(args)}，mcpitanlib/vanilla 的 {@code HandledScreen} 会把焦点
 * 切换到下一个 widget（搜索框）。一旦搜索框获得焦点，{@code keyReleased} 中的
 * {@code PlayerRegisteredItemUtil.getItems()} 被触发，对变体键解析崩溃。</p>
 *
 * <p><b>修复策略</b>：当 Shift 按下（预览激活条件之一）+ 预览功能已开启 + 搜索框未聚焦时，
 * 消费 WASD / 方向键的 {@code keyPressed} 和 {@code keyReleased} 事件（返回 true），
 * 阻止事件向上传递。焦点导航由 {@link AlchemyTableScreenShulkerPreview#updateFocus} 通过
 * {@code InputUtil.isKeyPressed} 轮询 GLFW 状态实现，不依赖事件传递，所以消费事件不影响导航。</p>
 *
 * <p><b>搜索框已聚焦时放行</b>：让搜索框正常处理方向键（移动文本光标）。</p>
 *
 * <p><b>target 为模组类</b>：{@code AlchemyTableScreen} 是 Item Alchemy 的客户端类，
 * {@code keyPressed}/{@code keyReleased} 不是 Minecraft 原版方法，{@code remap = false}。</p>
 */
@Mixin(value = AlchemyTableScreen.class, remap = false)
public class MixinAlchemyTableScreen {

    @Shadow
    public TextFieldWidget searchBox;

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true, remap = false)
    private void iaexp$interceptDirectionKeys(KeyEventArgs args, CallbackInfoReturnable<Boolean> cir) {
        // 只在 Shift 按下（预览激活条件之一）时拦截
        if (!Screen.hasShiftDown()) return;
        // 预览功能未开启时不拦截
        if (!AlchemyTableScreenShulkerPreview.isPreviewFeatureEnabled()) return;
        // 搜索框已聚焦时放行（让搜索框处理光标移动）
        if (searchBox != null && searchBox.isFocused()) return;

        if (isDirectionKey(args.keyCode)) {
            // 消费事件，阻止 super.keyPressed 把焦点切换到搜索框
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "keyReleased", at = @At("HEAD"), cancellable = true, remap = false)
    private void iaexp$interceptDirectionKeyRelease(KeyEventArgs args, CallbackInfoReturnable<Boolean> cir) {
        if (!Screen.hasShiftDown()) return;
        if (!AlchemyTableScreenShulkerPreview.isPreviewFeatureEnabled()) return;
        if (searchBox != null && searchBox.isFocused()) return;

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
}
