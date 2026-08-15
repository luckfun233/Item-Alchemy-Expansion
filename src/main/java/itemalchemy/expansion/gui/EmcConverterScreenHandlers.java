package itemalchemy.expansion.gui;

import itemalchemy.expansion.ItemAlchemyExpansion;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.util.Identifier;
import net.pitan76.mcpitanlib.api.gui.SimpleScreenHandlerTypeBuilder;

/**
 * EMC 转能器 ScreenHandlerType 注册。
 */
public final class EmcConverterScreenHandlers {

    private EmcConverterScreenHandlers() {}

    private static final SimpleScreenHandlerTypeBuilder<EmcConverterScreenHandler> BUILDER =
            new SimpleScreenHandlerTypeBuilder<>(e -> new EmcConverterScreenHandler(e));

    public static final ScreenHandlerType<EmcConverterScreenHandler> TYPE;

    static {
        TYPE = Registry.register(Registries.SCREEN_HANDLER,
                new Identifier(ItemAlchemyExpansion.MOD_ID, "emc_converter"),
                BUILDER.build());
    }

    public static void init() {
        ItemAlchemyExpansion.LOGGER.info("[IAExp] emc converter screen handler registered");
    }
}