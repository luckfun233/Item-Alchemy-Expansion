package itemalchemy.expansion.compat.modmenu;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import itemalchemy.expansion.ItemAlchemyExpansion;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screen.Screen;

/**
 * Mod Menu 集成入口：在模组列表中为本模组添加「配置」按钮。
 *
 * <p>仅在已安装 modmenu 时由 modmenu 通过 {@code entrypoints.modmenu} 调用；
 * 未安装 modmenu 时本类不会被加载。</p>
 *
 * <p><b>关键设计</b>：配置界面依赖 cloth-config（软依赖）。cloth-config 是否可用必须在
 * {@link #getModConfigScreenFactory()} 返回前判断：已加载返回非 null 工厂
 * （工厂 {@code create()} 也必须返回非 null {@link Screen}）；未加载返回 {@code null} 工厂，
 * ModMenu 据此<b>隐藏</b>配置按钮，避免出现点击无响应的「死按钮」
 * （把判断放在 lambda 内部会导致 ModMenu 7.x 静默不切换屏幕）。</p>
 *
 * <p><b>延迟类加载</b>：{@code IAExpClothConfigScreen} 引用只在 {@link #createClothConfigScreen}
 * 方法体内出现，JVM 懒加载保证无 cloth-config 时不会触发其类初始化（NoClassDefFoundError）。</p>
 *
 * <p><b>mod id 检测</b>：双重保险——先按 fabric mod id 检查（主 id {@code "cloth-config"}，
 * 历史别名 {@code "cloth-config2"}），再用类存在性探测（{@link Class#forName}）兜底，
 * 对 mod id 拼写漂移完全免疫。</p>
 */
public class ModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        // 关键：在工厂创建前判断。未加载时返回 null 工厂，ModMenu 直接隐藏配置按钮。
        if (!isClothConfigLoaded()) {
            ItemAlchemyExpansion.LOGGER.info("[IAExp] cloth-config not loaded, ModMenu config button hidden.");
            return null;
        }
        // 已加载：工厂的 create() 必须返回非 null Screen。
        // 包一层 try-catch：即使 IAExpClothConfigScreen.create() 抛异常，
        // 也返回 null（ModMenu 点击时静默不切换），并在日志记录原因——
        // 避免异常冒泡到 Minecraft 按钮回调导致客户端卡顿/崩溃。
        return parent -> {
            try {
                Screen screen = createClothConfigScreen(parent);
                if (screen == null) {
                    ItemAlchemyExpansion.LOGGER.warn("[IAExp] IAExpClothConfigScreen.create() returned null screen.");
                }
                return screen;
            } catch (Throwable t) {
                ItemAlchemyExpansion.LOGGER.error("[IAExp] Failed to build cloth-config screen", t);
                return null;
            }
        };
    }

    /**
     * cloth-config 是否已加载。
     *
     * <p>双重检测：
     * <ol>
     *   <li>fabric mod id 检查：{@code "cloth-config"}（11.x 主 id）或 {@code "cloth-config2"}（历史别名）。</li>
     *   <li>类存在性探测：{@link Class#forName} 查找 {@code me.shedaniel.clothconfig2.api.ConfigBuilder}。
     *       对 mod id 拼写漂移、非 fabric 加载方式（如手动放 jar）完全免疫。</li>
     * </ol>
     * 任一通过即视为已加载。</p>
     */
    private static boolean isClothConfigLoaded() {
        // 1. fabric mod id 检查
        try {
            FabricLoader loader = FabricLoader.getInstance();
            if (loader.isModLoaded("cloth-config") || loader.isModLoaded("cloth-config2")) {
                return true;
            }
        } catch (Throwable ignored) {
            // 防御性：理论上 FabricLoader.getInstance() 不会抛
        }
        // 2. 类存在性探测兜底
        try {
            Class.forName("me.shedaniel.clothconfig2.api.ConfigBuilder");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * 单独抽出方法，确保 {@code IAExpClothConfigScreen} 类仅在本方法被调用时才加载。
     * 这样无 cloth-config 时不会触发其类初始化（NoClassDefFoundError）。
     */
    private static Screen createClothConfigScreen(Screen parent) {
        return itemalchemy.expansion.compat.clothconfig.IAExpClothConfigScreen.create(parent);
    }
}
