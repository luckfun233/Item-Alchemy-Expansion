package itemalchemy.expansion.gui;

import itemalchemy.expansion.ItemAlchemyExpansion;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.util.Identifier;
import net.pitan76.mcpitanlib.api.gui.SimpleScreenHandlerTypeBuilder;

/**
 * 制卡台 ScreenHandlerType 注册。
 */
public final class CardForgeScreenHandlers {

    private CardForgeScreenHandlers() {}

    private static final SimpleScreenHandlerTypeBuilder<CardForgeScreenHandler> BUILDER =
            new SimpleScreenHandlerTypeBuilder<>(e -> new CardForgeScreenHandler(e));

    /** 制卡台 ScreenHandlerType */
    public static final ScreenHandlerType<CardForgeScreenHandler> TYPE;

    static {
        TYPE = Registry.register(Registries.SCREEN_HANDLER,
                new Identifier(ItemAlchemyExpansion.MOD_ID, "card_forge"),
                BUILDER.build());
    }

    public static void init() {
        // 静态初始化已注册；此处仅用于显式触发类加载
        ItemAlchemyExpansion.LOGGER.info("[IAExp] card forge screen handler registered");
    }
}