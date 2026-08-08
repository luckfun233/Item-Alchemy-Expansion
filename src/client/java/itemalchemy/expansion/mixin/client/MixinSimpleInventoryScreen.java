package itemalchemy.expansion.mixin.client;

import itemalchemy.expansion.client.AlchemyTableScreenShulkerPreview;
import itemalchemy.expansion.client.AlchemyTableSlotMatchOverlay;
import net.minecraft.client.gui.DrawContext;
import net.pitan76.itemalchemy.client.screen.AlchemyTableScreen;
import net.pitan76.mcpitanlib.api.client.gui.screen.SimpleInventoryScreen;
import net.pitan76.mcpitanlib.api.client.render.handledscreen.RenderArgs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin 注入到 {@link SimpleInventoryScreen#renderOverride} 的 RETURN，
 * 在 {@code callDrawMouseoverTooltip()} 之后渲染：
 * <ol>
 *   <li>{@link AlchemyTableSlotMatchOverlay} — 搜索匹配小标志 + 匹配列表 tooltip</li>
 *   <li>{@link AlchemyTableScreenShulkerPreview} — Shift 潜影盒预览（含匹配红框）</li>
 * </ol>
 *
 * <p>顺序：先画小标志（在槽位图标之上但低于 Shift 预览面板），再画 Shift 预览，
 * 确保 Shift 预览的红框/焦点框覆盖在小标志之上。</p>
 *
 * <p>不用 {@code ScreenEvents.afterRender}：Fabric 的 {@code afterRender} 注入到
 * {@code Screen.render()} 的 RETURN，但 mcpitanlib 的 {@code SimpleHandledScreen} 重写了
 * {@code render()}，{@code afterRender} 在 {@code Screen.render()} 返回时触发，
 * 此时 {@code HandledScreen.render()} 尚未渲染槽位和 tooltip，预览会被后续渲染的 tooltip 遮挡。
 * {@code SimpleInventoryScreen.renderOverride()} 在最后调用 {@code callDrawMouseoverTooltip()}
 * 渲染 tooltip 后返回，Mixin 注入到它的 RETURN 即可确保覆盖渲染在 tooltip 之后。</p>
 *
 * <p><b>target 为第三方模组类</b>：{@code SimpleInventoryScreen} 是 mcpitanlib 的类，
 * {@code renderOverride} 不是 Minecraft 原版方法，因此 {@code remap = false}。
 * 如果 mcpitanlib 更新后移除此方法，Mixin 不会导致崩溃（{@code defaultRequire = 0}）。</p>
 */
@Mixin(value = SimpleInventoryScreen.class, remap = false)
public class MixinSimpleInventoryScreen {

    @Inject(method = "renderOverride", at = @At("RETURN"), remap = false)
    private void itemalchemy_expansion$afterRenderOverride(RenderArgs args, CallbackInfo ci) {
        // 只在 AlchemyTableScreen 上触发（Mixin 对所有 SimpleInventoryScreen 子类生效）
        if (!((Object) this instanceof AlchemyTableScreen)) return;
        AlchemyTableScreen screen = (AlchemyTableScreen) (Object) this;
        DrawContext context = args.drawObjectDM.getContext();

        // 先画小标志，再画 Shift 预览，确保 Shift 预览的红框/焦点框覆盖在小标志之上
        AlchemyTableSlotMatchOverlay.onAfterRender(screen, context, args.mouseX, args.mouseY);
        AlchemyTableScreenShulkerPreview.onAfterRender(screen, context, args.mouseX, args.mouseY, args.delta);
    }
}
