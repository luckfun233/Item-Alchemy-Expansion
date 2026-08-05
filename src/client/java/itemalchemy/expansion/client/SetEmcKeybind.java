package itemalchemy.expansion.client;

import itemalchemy.expansion.ItemAlchemyExpansion;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import org.lwjgl.glfw.GLFW;

/**
 * 「设置 EMC」快捷键：手持物品时按下打开 {@link SetEmcScreen}。
 *
 * <p>默认绑定到 K 键。玩家可在「选项 → 控制 → 按键绑定」中的
 * 「Item Alchemy Expansion」分类重新绑定或解绑。</p>
 *
 * <p>触发条件（在 {@link ClientTickEvents#END_CLIENT_TICK} 中检测）：
 * <ul>
 *   <li>按键被按下（wasPressed，每 tick 至多触发一次）</li>
 *   <li>玩家在主世界（非 GUI 中）</li>
 *   <li>玩家主手或副手有物品</li>
 * </ul>
 * </p>
 *
 * <p><b>选物优先级</b>：主手优先；主手为空时用副手。两手都空则不打开 GUI
 * （没有物品可设置 EMC）。</p>
 */
public final class SetEmcKeybind {

    /** 按键分类，显示在按键绑定界面的分组名 */
    public static final String KEY_CATEGORY = "itemalchemy-expansion.keybind.category";

    /** 翻译键 */
    public static final String KEY_NAME = "itemalchemy-expansion.keybind.set_emc";

    /** 唯一 KeyBinding 实例 */
    private static KeyBinding keyBinding;

    private SetEmcKeybind() {}

    /** 注册按键 + 注册 tick 监听。在 {@code onInitializeClient} 中调用。 */
    public static void register() {
        keyBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                KEY_NAME,
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_K,
                KEY_CATEGORY
        ));

        ClientTickEvents.END_CLIENT_TICK.register(SetEmcKeybind::onEndTick);
    }

    private static void onEndTick(MinecraftClient client) {
        // wasPressed 返回 true 后会消费一次按下状态，避免重复触发
        while (keyBinding.wasPressed()) {
            tryOpenSetEmcScreen(client);
        }
    }

    private static void tryOpenSetEmcScreen(MinecraftClient client) {
        if (client.player == null) return;
        // 玩家在 GUI 中时不触发（避免与转换桌等界面的按键冲突）
        if (client.currentScreen != null) return;

        PlayerEntity player = client.player;
        ItemStack target = pickHeldItem(player);
        if (target.isEmpty()) {
            // 没有手持物品：不打开 GUI，静默（玩家可能误按）
            return;
        }

        // 切到主线程打开 GUI（tick 回调本身在主线程，直接 setScreen 即可）
        client.setScreen(new SetEmcScreen(target));
    }

    /** 主手优先，主手为空时用副手 */
    private static ItemStack pickHeldItem(PlayerEntity player) {
        ItemStack main = player.getStackInHand(Hand.MAIN_HAND);
        if (!main.isEmpty()) return main;
        return player.getStackInHand(Hand.OFF_HAND);
    }
}
