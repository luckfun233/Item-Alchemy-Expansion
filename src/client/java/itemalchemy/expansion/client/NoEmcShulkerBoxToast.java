package itemalchemy.expansion.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.toast.Toast;
import net.minecraft.client.toast.ToastManager;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * 「无法放入潜影盒」Toast 通知（屏幕右上角弹窗）。
 *
 * <p>当玩家尝试把含无 EMC 物品的潜影盒放入转换桌输入槽时，{@code MixinRegisterSlot}
 * 通过反射调用 {@link #show(ItemStack)} 触发此 Toast，比聊天消息更醒目。</p>
 *
 * <p><b>布局</b>：
 * <pre>
 *   ┌────────────────────────────────────┐
 *   │ [⚠]  无法放入潜影盒                 │  标题（红色）
 *   │      XXX 没有 EMC 值                │  描述（白色，XXX 为物品名）
 *   └────────────────────────────────────┘
 * </pre>
 * 图标用 {@link #WARNING_TEXTURE}（vanilla 的 warning 纹理，16×16）。</p>
 *
 * <p><b>持续时长</b>：5000ms（5 秒），与 vanilla SystemToast 一致。</p>
 *
 * <p><b>重复抑制</b>：{@link #show(ItemStack)} 内部用 {@code lastShownItem} 记录上次物品，
 * 500ms 内同物品不重复弹出，避免短时间内多次 canInsert 调用导致刷屏。</p>
 */
public class NoEmcShulkerBoxToast implements Toast {

    /** Toast 背景纹理（vanilla 的 toast 纹理） */
    private static final Identifier TEXTURE = new Identifier("minecraft", "textures/gui/toasts.png");

    /** Toast 持续时长（毫秒） */
    private static final long DURATION_MS = 5000L;

    /** 触发此 Toast 的无 EMC 物品 */
    private final ItemStack noEmcItem;
    /** Toast 创建时间，用于淡入淡出 */
    private long startTime = -1;

    public NoEmcShulkerBoxToast(ItemStack noEmcItem) {
        this.noEmcItem = noEmcItem;
    }

    @Override
    public int getWidth() {
        return 160;
    }

    @Override
    public int getHeight() {
        return 32;
    }

    @Override
    public Visibility draw(DrawContext context, ToastManager manager, long currentTime) {
        if (startTime == -1) startTime = currentTime;

        // 淡入淡出（vanilla Toast 标准逻辑）
        int fade;
        long elapsed = currentTime - startTime;
        if (elapsed < 200) {
            // 淡入
            fade = Math.toIntExact(elapsed * 255 / 200);
        } else if (elapsed > DURATION_MS - 200) {
            // 淡出
            fade = Math.toIntExact((DURATION_MS - elapsed) * 255 / 200);
        } else {
            fade = 255;
        }
        if (fade < 0) fade = 0;
        if (fade > 255) fade = 255;

        // 背景：用 vanilla toast 纹理（上半部分）
        context.drawTexture(TEXTURE, 0, 0, 0, 0, getWidth(), getHeight());

        MinecraftClient client = MinecraftClient.getInstance();

        // 标题：「无法放入潜影盒」（红色 0xFFFF5555）
        Text title = Text.translatable("itemalchemy-expansion.shulker_box.toast.title");
        context.drawText(client.textRenderer, title, 30, 7, 0xFFFF5555, false);

        // 描述：「XXX 没有 EMC 值」（白色）
        Text desc = Text.translatable("itemalchemy-expansion.shulker_box.toast.desc",
                noEmcItem.getName());
        // 截断过长名称（避免超出 toast 宽度）
        String descStr = desc.getString();
        int maxW = getWidth() - 35;
        if (client.textRenderer.getWidth(descStr) > maxW) {
            while (client.textRenderer.getWidth(descStr + "...") > maxW && descStr.length() > 1) {
                descStr = descStr.substring(0, descStr.length() - 1);
            }
            descStr = descStr + "...";
        }
        context.drawText(client.textRenderer, descStr, 30, 18, 0xFFFFFFFF, false);

        // 物品图标（左侧）
        context.drawItem(noEmcItem, 8, 8);

        return elapsed >= DURATION_MS ? Visibility.HIDE : Visibility.SHOW;
    }

    // ===== 静态触发入口（被 MixinRegisterSlot 反射调用） =====

    /** 上次弹 Toast 的时间（毫秒），用于重复抑制 */
    private static long lastShownTime = 0;
    /** 上次弹 Toast 的物品，用于重复抑制 */
    private static ItemStack lastShownItem = ItemStack.EMPTY;

    /**
     * 显示「无法放入潜影盒」Toast 通知。
     *
     * <p>由 {@code MixinRegisterSlot.sendNoEmcRejectMessage} 通过反射调用
     * （main 源集不能直接引用客户端类）。包含重复抑制：500ms 内同物品不重复弹出。</p>
     */
    public static void show(ItemStack noEmcItem) {
        if (noEmcItem == null || noEmcItem.isEmpty()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;

        long now = System.currentTimeMillis();
        // 重复抑制：500ms 内同物品不重复
        if (now - lastShownTime < 500 && ItemStack.areEqual(lastShownItem, noEmcItem)) {
            return;
        }
        lastShownTime = now;
        lastShownItem = noEmcItem.copy();

        try {
            client.getToastManager().add(new NoEmcShulkerBoxToast(noEmcItem));
        } catch (Throwable ignored) {
            // 防御性：Toast 显示失败不影响游戏逻辑
        }
    }
}
