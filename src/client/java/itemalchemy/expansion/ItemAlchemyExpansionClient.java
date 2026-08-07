package itemalchemy.expansion;

import itemalchemy.expansion.client.SetEmcClientNetwork;
import itemalchemy.expansion.client.SetEmcKeybind;
import net.fabricmc.api.ClientModInitializer;

/**
 * 客户端初始化入口。
 *
 * <p>职责：
 * <ol>
 *   <li>确保客户端也初始化配置服务（防御性；{@link ItemAlchemyExpansion#onInitialize()} 已调用
 *       {@link IAExpServices#init()}，但客户端入口显式调用保证客户端独立运行时配置可用）。</li>
 *   <li>注册「设置 EMC」快捷键（{@link SetEmcKeybind}）：手持物品时按下打开 EMC 设置 GUI。</li>
 *   <li>注册精确 EMC map S2C 接收器（{@link SetEmcClientNetwork#registerClientReceiver}）：
 *       玩家上线或他人修改精确 EMC 时同步给客户端，供 {@code SetEmcScreen} 显示当前值。</li>
 * </ol>
 * </p>
 *
 * <p><b>Shift 潜影盒预览</b>：已改为 Mixin 注入（{@code MixinSimpleInventoryScreen}），
 * 不再用 {@code ScreenEvents.afterRender}。原因：Fabric 的 afterRender 注入到
 * {@code Screen.render()} 的 RETURN，但 mcpitanlib 重写了 render()，导致 afterRender
 * 在 tooltip 渲染之前触发，预览被 tooltip 遮挡。Mixin 注入到
 * {@code SimpleInventoryScreen.renderOverride} 的 RETURN（在 callDrawMouseboxTooltip 之后），
 * 确保预览在 tooltip 之上。</p>
 */
public class ItemAlchemyExpansionClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// 1. 防御性初始化配置服务（理论上 onInitialize 已调用；客户端入口再调一次幂等）
		try {
			IAExpServices.init();
		} catch (Throwable t) {
			ItemAlchemyExpansion.LOGGER.warn("[IAExp] client-side IAExpServices.init() failed (non-fatal): {}", t.toString());
		}

		// 2. 「设置 EMC」快捷键（默认未绑定，玩家在按键绑定界面手动绑定）
		SetEmcKeybind.register();

		// 3. 注册精确 EMC map S2C 接收器（玩家上线时服务端会推一次，他人修改也会推）
		try {
			SetEmcClientNetwork.registerClientReceiver();
		} catch (Throwable t) {
			ItemAlchemyExpansion.LOGGER.warn("[IAExp] Failed to register precise emc S2C receiver: {}", t.toString());
		}

		ItemAlchemyExpansion.LOGGER.info("[IAExp] client initialized: set-emc keybind registered, shulker preview via mixin, precise emc S2C receiver registered.");
	}
}
