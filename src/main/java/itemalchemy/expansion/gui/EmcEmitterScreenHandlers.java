package itemalchemy.expansion.gui;

import itemalchemy.expansion.ItemAlchemyExpansion;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.util.Identifier;
import net.pitan76.mcpitanlib.api.gui.SimpleScreenHandlerTypeBuilder;

/**
 * EMC 输出器 ScreenHandlerType 注册。
 */
public final class EmcEmitterScreenHandlers {

    private EmcEmitterScreenHandlers() {}

    private static final SimpleScreenHandlerTypeBuilder<EmcEmitterScreenHandler> BUILDER =
            new SimpleScreenHandlerTypeBuilder<>(e -> new EmcEmitterScreenHandler(e));

    /** EMC 输出器 ScreenHandlerType */
    public static final ScreenHandlerType<EmcEmitterScreenHandler> TYPE;

    static {
        TYPE = Registry.register(Registries.SCREEN_HANDLER,
                new Identifier(ItemAlchemyExpansion.MOD_ID, "emc_emitter"),
                BUILDER.build());
    }

    public static void init() {
        ItemAlchemyExpansion.LOGGER.info("[IAExp] emc emitter screen handler registered");
    }
}
