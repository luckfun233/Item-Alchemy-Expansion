package itemalchemy.expansion;

import itemalchemy.expansion.command.IAExpCommand;
import itemalchemy.expansion.config.IAExpConfig;
import itemalchemy.expansion.config.IAExpConfigHolder;
import itemalchemy.expansion.item.IAExpItems;
import itemalchemy.expansion.network.AutoEmcStore;
import itemalchemy.expansion.network.EmcCardNetwork;
import itemalchemy.expansion.network.FilterModeNetwork;
import itemalchemy.expansion.network.PerSaveEmcStore;
import itemalchemy.expansion.network.PreciseEmcStore;
import itemalchemy.expansion.network.SetEmcNetwork;
import itemalchemy.expansion.recipe.RecipeAutoPricer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.pitan76.mcpitanlib.api.command.CommandRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ItemAlchemyExpansion implements ModInitializer {
	public static final String MOD_ID = "itemalchemy-expansion";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		IAExpServices.init();

		IAExpItems.init();

		SetEmcNetwork.registerServer();
		EmcCardNetwork.registerServer();
		FilterModeNetwork.registerServer();
		CommandRegistry.register(MOD_ID, new IAExpCommand());

		// 自动定价分批扫描：每 tick 处理一批配方，未扫描时零开销
		ServerTickEvents.END_SERVER_TICK.register(RecipeAutoPricer::onServerTick);
		// 服务器关闭时若扫描未完成则丢弃状态，避免下次启动残留
		ServerLifecycleEvents.SERVER_STOPPING.register(RecipeAutoPricer::onServerStopping);

		// SERVER_STARTED 在 EMCManager.init 之后触发：此时全局 emc_config.json 已在内存 map，
		// 本存档 overrides 覆盖其上
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			try {
				PerSaveEmcStore.load(server);
			} catch (Throwable t) {
				LOGGER.warn("[IAExp] Failed to apply per-save emc overrides on server start: {}", t.toString());
			}
			try {
				// 精确覆盖加载不依赖 autoPricing，玩家手动精确值始终生效
				PreciseEmcStore.load(server);
			} catch (Throwable t) {
				LOGGER.warn("[IAExp] Failed to load precise emc store: {}", t.toString());
			}
			try {
				IAExpConfig cfg = IAExpConfigHolder.get();
				if (cfg.autoPricingFromRecipes) {
					RecipeAutoPricer.computeAndStore(server);
				} else {
					AutoEmcStore.clear();
				}
			} catch (Throwable t) {
				LOGGER.warn("[IAExp] Failed to run recipe auto-pricing: {}", t.toString());
			}
		});

		// 玩家加入时推送精确/自动定价 map + 升级/重新定价提示
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			try {
				SetEmcNetwork.pushPreciseMapTo(handler.player);
			} catch (Throwable t) {
				LOGGER.warn("[IAExp] Failed to push precise emc map on player join: {}", t.toString());
			}
			try {
				SetEmcNetwork.pushAutoEmcMapTo(handler.player);
			} catch (Throwable t) {
				LOGGER.warn("[IAExp] Failed to push auto emc map on player join: {}", t.toString());
			}
			// 升级 toast：仅当配置从旧版本升级且未提示过时弹一次
			try {
				if (IAExpConfigHolder.wasUpgradedFromLegacy()
						&& !IAExpConfigHolder.get().featureNoticeShown) {
					SetEmcNetwork.pushNewFeatureToast(handler.player);
					IAExpConfigHolder.get().featureNoticeShown = true;
					IAExpConfigHolder.save();
					IAExpConfigHolder.clearUpgradedFromLegacy();
					LOGGER.info("[IAExp] new feature toast pushed to {} (legacy upgrade detected)",
							handler.player.getEntityName());
				}
			} catch (Throwable t) {
				LOGGER.warn("[IAExp] Failed to push new feature toast: {}", t.toString());
			}
			try {
				IAExpConfig cfg = IAExpConfigHolder.get();
				if (cfg.autoPricingFromRecipes && !cfg.autoPricingRepricePromptShown) {
					SetEmcNetwork.handleRepriceCheck(server, handler.player);
				}
			} catch (Throwable t) {
				LOGGER.warn("[IAExp] Failed to check reprice prompt on player join: {}", t.toString());
			}
		});

		LOGGER.info("[IAExp] Item Alchemy Expansion initialized. config={}",
				IAExpConfigHolder.configPath());
	}

	/**
	 * 调试日志：仅当 {@code config.debugLogging=true} 时输出 INFO 级别日志，关闭时静默以避免刷屏。
	 * warn/error 始终通过 {@link #LOGGER} 直接输出。
	 */
	public static void debug(String format, Object... args) {
		if (IAExpConfigHolder.get().debugLogging) {
			LOGGER.info(format, args);
		}
	}

	/** 调试开关是否启用（供需要在日志外做条件分支的调用方使用） */
	public static boolean debugEnabled() {
		return IAExpConfigHolder.get().debugLogging;
	}
}
