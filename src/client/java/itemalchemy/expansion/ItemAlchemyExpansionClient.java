package itemalchemy.expansion;

import itemalchemy.expansion.client.EmcAutoClientNetwork;
import itemalchemy.expansion.client.EmcCardClientNetwork;
import itemalchemy.expansion.client.CardForgeClientNetwork;
import itemalchemy.expansion.client.SetEmcClientNetwork;
import itemalchemy.expansion.client.SetEmcKeybind;
import itemalchemy.expansion.client.CardForgeScreen;
import itemalchemy.expansion.client.EmcConverterScreen;
import itemalchemy.expansion.gui.CardForgeScreenHandlers;
import itemalchemy.expansion.gui.CardForgeScreenHandler;
import itemalchemy.expansion.gui.EmcConverterScreenHandlers;
import itemalchemy.expansion.gui.EmcConverterScreenHandler;
import net.fabricmc.api.ClientModInitializer;

/**
 * 客户端初始化入口：初始化配置服务、注册「设置 EMC」快捷键与精确 EMC S2C 接收器。
 *
 * <p>Shift 潜影盒预览通过 {@code MixinSimpleInventoryScreen} 注入到
 * {@code SimpleInventoryScreen.renderOverride} 的 RETURN（在 callDrawMouseboxTooltip 之后），
 * 确保预览渲染在 tooltip 之上；Fabric 的 afterRender 会因 mcpitanlib 重写 render()
 * 而早于 tooltip 触发，导致遮挡。</p>
 */
public class ItemAlchemyExpansionClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// 防御性初始化配置服务（幂等；保证客户端独立运行时配置可用）
		try {
			IAExpServices.init();
		} catch (Throwable t) {
			ItemAlchemyExpansion.LOGGER.warn("[IAExp] client-side IAExpServices.init() failed (non-fatal): {}", t.toString());
		}

		// 「设置 EMC」快捷键默认未绑定，玩家在按键绑定界面手动绑定
		SetEmcKeybind.register();

		// 精确 EMC S2C 接收器：玩家上线时服务端推送一次，他人修改也会推送
		try {
			SetEmcClientNetwork.registerClientReceiver();
		} catch (Throwable t) {
			ItemAlchemyExpansion.LOGGER.warn("[IAExp] Failed to register precise emc S2C receiver: {}", t.toString());
		}

		// EMC 卡 S2C 接收器：右键卡时服务端发信号让客户端打开 GUI
		try {
			EmcCardClientNetwork.registerClientReceiver();
		} catch (Throwable t) {
			ItemAlchemyExpansion.LOGGER.warn("[IAExp] Failed to register emc card S2C receiver: {}", t.toString());
		}

		// 制卡台 S2C 接收器：绑定页在线玩家列表下发
		try {
			CardForgeClientNetwork.registerClientReceiver();
		} catch (Throwable t) {
			ItemAlchemyExpansion.LOGGER.warn("[IAExp] Failed to register card forge S2C receiver: {}", t.toString());
		}

		// 制卡台 Screen 注册
		try {
			net.minecraft.client.gui.screen.ingame.HandledScreens.register(
					CardForgeScreenHandlers.TYPE, CardForgeScreen::new);
		} catch (Throwable t) {
			ItemAlchemyExpansion.LOGGER.warn("[IAExp] Failed to register card forge screen: {}", t.toString());
		}

		// EMC 转能器 Screen 注册
		try {
			net.minecraft.client.gui.screen.ingame.HandledScreens.register(
					EmcConverterScreenHandlers.TYPE, EmcConverterScreen::new);
		} catch (Throwable t) {
			ItemAlchemyExpansion.LOGGER.warn("[IAExp] Failed to register emc converter screen: {}", t.toString());
		}

		// 自动装置 S2C 接收器（输出器打开/列表/所选）
		try {
			EmcAutoClientNetwork.registerClientReceiver();
		} catch (Throwable t) {
			ItemAlchemyExpansion.LOGGER.warn("[IAExp] Failed to register emc auto S2C receiver: {}", t.toString());
		}

		ItemAlchemyExpansion.LOGGER.info("[IAExp] client initialized: set-emc keybind registered, shulker preview via mixin, precise emc S2C receiver registered, emc card S2C receiver registered, emc automation receivers registered.");
	}
}
