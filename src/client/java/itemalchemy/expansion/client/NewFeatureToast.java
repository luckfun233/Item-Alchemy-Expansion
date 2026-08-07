package itemalchemy.expansion.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.toast.Toast;
import net.minecraft.client.toast.ToastManager;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * 「新功能」升级提醒 Toast（屏幕右上角弹窗）。
 *
 * <p>当 {@code IAExpConfigHolder.wasUpgradedFromLegacy()} 返回 true（即玩家从旧版本配置升级）
 * 且 {@code featureNoticeShown=false} 时，服务端在玩家加入时推 {@code new_feature_toast}
 * S2C 包，客户端收到后弹此 toast 一次。</p>
 *
 * <p><b>布局</b>（比 {@link NoEmcShulkerBoxToast} 宽，容纳两行说明）：
 * <pre>
 *   ┌──────────────────────────────────────────┐
 *   │ [★]  Item Alchemy Expansion 新功能        │  标题（金色）
 *   │      已新增「配方自动定价」与「精确模式」    │  描述行 1（白色）
 *   │      （实验性，默认关闭）。在 Mod 菜单中开启 │  描述行 2（灰色）
 *   └──────────────────────────────────────────┘
 * </pre>
 * 图标用 vanilla 的 {@code experience_bottle} 纹理（16×16），表示「新知识」。</p>
 *
 * <p><b>持续时长</b>：8000ms（8 秒，比一般 toast 长一倍，确保玩家能读完）。</p>
 *
 * <p><b>去重</b>：服务端已用 {@code featureNoticeShown} 标志保证只推一次，
 * 客户端这里不重复抑制（toast 实例本身只显示一次后即被 ToastManager 移除）。</p>
 */
public class NewFeatureToast implements Toast {

    /** Toast 背景纹理（vanilla 的 toast 纹理） */
    private static final Identifier TEXTURE = new Identifier("minecraft", "textures/gui/toasts.png");

    /** 经验瓶纹理（作为「新功能」图标） */
    private static final Identifier ICON_TEXTURE =
            new Identifier("minecraft", "textures/item/experience_bottle.png");

    /** Toast 持续时长（毫秒）—— 比一般 toast 长，确保玩家读完 */
    private static final long DURATION_MS = 8000L;

    /** Toast 创建时间，用于淡入淡出 */
    private long startTime = -1;

    @Override
    public int getWidth() {
        return 240;
    }

    @Override
    public int getHeight() {
        return 44;
    }

    @Override
    public Visibility draw(DrawContext context, ToastManager manager, long currentTime) {
        if (startTime == -1) startTime = currentTime;

        // 淡入淡出（vanilla Toast 标准逻辑）
        int fade;
        long elapsed = currentTime - startTime;
        if (elapsed < 200) {
            fade = Math.toIntExact(elapsed * 255 / 200);
        } else if (elapsed > DURATION_MS - 200) {
            fade = Math.toIntExact((DURATION_MS - elapsed) * 255 / 200);
        } else {
            fade = 255;
        }
        if (fade < 0) fade = 0;
        if (fade > 255) fade = 255;

        // 背景：用 vanilla toast 纹理（上半部分），但宽度自适应
        // vanilla toast 纹理单格是 160x32，我们需要 240x44，分块绘制
        context.drawTexture(TEXTURE, 0, 0, 0, 0, 160, 32);
        context.drawTexture(TEXTURE, 160, 0, 160, 0, getWidth() - 160, 32);
        context.drawTexture(TEXTURE, 0, 32, 0, 0, 160, getHeight() - 32);
        context.drawTexture(TEXTURE, 160, 32, 160, 0, getWidth() - 160, getHeight() - 32);

        MinecraftClient client = MinecraftClient.getInstance();

        // 标题：「Item Alchemy Expansion 新功能」（金色 0xFFFFAA00）
        Text title = Text.translatable("itemalchemy-expansion.new_feature_toast.title");
        context.drawText(client.textRenderer, title, 30, 7, 0xFFFFAA00, false);

        // 描述行 1（白色）
        Text desc1 = Text.translatable("itemalchemy-expansion.new_feature_toast.desc1");
        context.drawText(client.textRenderer, desc1, 30, 20, 0xFFFFFFFF, false);

        // 描述行 2（灰色，省略号结尾以紧凑）
        Text desc2 = Text.translatable("itemalchemy-expansion.new_feature_toast.desc2");
        String desc2Str = desc2.getString();
        int maxW = getWidth() - 35;
        if (client.textRenderer.getWidth(desc2Str) > maxW) {
            while (client.textRenderer.getWidth(desc2Str + "...") > maxW && desc2Str.length() > 1) {
                desc2Str = desc2Str.substring(0, desc2Str.length() - 1);
            }
            desc2Str = desc2Str + "...";
        }
        context.drawText(client.textRenderer, desc2Str, 30, 31, 0xFFA0A0A0, false);

        // 左侧图标：经验瓶（16x16）
        context.drawTexture(ICON_TEXTURE, 8, 12, 0, 0, 16, 16, 16, 16);

        return elapsed >= DURATION_MS ? Visibility.HIDE : Visibility.SHOW;
    }

    // ===== 静态触发入口 =====

    /**
     * 显示「新功能」Toast 通知。
     *
     * <p>由 {@code SetEmcClientNetwork} 在收到 {@code new_feature_toast} S2C 包后调用。</p>
     */
    public static void show() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;
        try {
            client.getToastManager().add(new NewFeatureToast());
        } catch (Throwable ignored) {
            // 防御性：Toast 显示失败不影响游戏逻辑
        }
    }
}
