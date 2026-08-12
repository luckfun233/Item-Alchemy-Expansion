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
 * <p>当 {@code IAExpConfigHolder.wasUpgradedFromLegacy()} 返回 true（玩家从旧版本配置升级）
 * 且 {@code featureNoticeShown=false} 时，服务端在玩家加入时推 {@code new_feature_toast}
 * S2C 包，客户端收到后弹此 toast 一次。</p>
 *
 * <p>布局：标题（金色）+ 描述行 1（白色）+ 描述行 2（灰色），左侧经验瓶图标。</p>
 *
 * <p>持续时长 8000ms，比一般 toast 长一倍以确保玩家能读完。服务端已用
 * {@code featureNoticeShown} 标志去重，客户端不重复抑制。</p>
 *
 * <p><b>背景绘制</b>：1.21.1 移除了 {@code minecraft:textures/gui/toasts.png}，
 * 迁移到 gui.png-atlas sprite 系统。这里直接用 {@link DrawContext#fill} 画半透明深色
 * 矩形 + 边框，不依赖 vanilla 纹理。经验瓶图标仍是独立纹理文件（未迁移到 atlas）。</p>
 */
public class NewFeatureToast implements Toast {

    /** 经验瓶纹理（作为「新功能」图标，独立纹理文件，未迁移到 atlas） */
    private static final Identifier ICON_TEXTURE =
            Identifier.of("minecraft", "textures/item/experience_bottle.png");

    /** Toast 持续时长（毫秒），比一般 toast 长，确保玩家读完 */
    private static final long DURATION_MS = 8000L;

    /** 背景颜色（ARGB：深色半透明） */
    private static final int BG_COLOR = 0xC0202020;
    /** 背景边框颜色（ARGB：浅灰） */
    private static final int BORDER_COLOR = 0xFF505050;

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

        // vanilla Toast 标准淡入淡出
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

        // 1.21.1 移除了 toasts.png 纹理文件，直接用 fill 画半透明深色背景 + 边框
        int w = getWidth();
        int h = getHeight();
        context.fill(0, 0, w, h, BG_COLOR);
        // 1px 边框
        context.fill(0, 0, w, 1, BORDER_COLOR);
        context.fill(0, h - 1, w, h, BORDER_COLOR);
        context.fill(0, 0, 1, h, BORDER_COLOR);
        context.fill(w - 1, 0, w, h, BORDER_COLOR);

        MinecraftClient client = MinecraftClient.getInstance();

        Text title = Text.translatable("itemalchemy-expansion.new_feature_toast.title");
        context.drawText(client.textRenderer, title, 30, 7, 0xFFFFAA00, false);

        Text desc1 = Text.translatable("itemalchemy-expansion.new_feature_toast.desc1");
        context.drawText(client.textRenderer, desc1, 30, 20, 0xFFFFFFFF, false);

        // 描述行 2 超宽时省略号截断以紧凑显示
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

        context.drawTexture(ICON_TEXTURE, 8, 12, 0, 0, 16, 16, 16, 16);

        return elapsed >= DURATION_MS ? Visibility.HIDE : Visibility.SHOW;
    }

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
