package itemalchemy.expansion;

import itemalchemy.expansion.command.IAExpCommand;
import itemalchemy.expansion.config.IAExpConfig;
import itemalchemy.expansion.config.IAExpConfigHolder;
import itemalchemy.expansion.network.AutoEmcStore;
import itemalchemy.expansion.network.FilterModeNetwork;
import itemalchemy.expansion.network.PerSaveEmcStore;
import itemalchemy.expansion.network.PreciseEmcStore;
import itemalchemy.expansion.network.SetEmcNetwork;
import itemalchemy.expansion.recipe.RecipeAutoPricer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.pitan76.mcpitanlib.api.command.CommandRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ItemAlchemyExpansion implements ModInitializer {
	public static final String MOD_ID = "itemalchemy-expansion";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// 加载配置 + 初始化 NBT 指纹器等服务
		IAExpServices.init();

		// 注册「设置 EMC」C2S 网络包接收器（服务端）
		SetEmcNetwork.registerServer();

		// 注册「筛选模式切换」C2S 网络包接收器（服务端）
		FilterModeNetwork.registerServer();

		// 注册命令 /itemalchemy-expansion reprice（强制重算自动定价）
		CommandRegistry.register(MOD_ID, new IAExpCommand());

		// 服务端启动后（EMCManager 已 init）应用本存档 EMC 覆盖 + 精确覆盖 + 自动定价。
		// SERVER_STARTED 在世界加载、EMCManager.init 之后触发，
		// 此时 emc_config.json 全局值已在内存 map，本存档 overrides 覆盖其上。
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			try {
				PerSaveEmcStore.load(server);
			} catch (Throwable t) {
				LOGGER.warn("[IAExp] Failed to apply per-save emc overrides on server start: {}", t.toString());
			}
			try {
				// 精确覆盖加载（不依赖 autoPricing，玩家手动精确值在任何精确模式 ON 时都生效）
				PreciseEmcStore.load(server);
			} catch (Throwable t) {
				LOGGER.warn("[IAExp] Failed to load precise emc store: {}", t.toString());
			}
			try {
				// 配方自动定价（仅开启时执行；缓存命中则跳过重算）
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

		// 玩家加入时推送精确 map + 自动定价 map + 检查是否需要弹「新功能」toast
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
			// 升级 toast：仅当配置从旧版本升级 && 未提示过时弹一次
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
		});

		LOGGER.info("[IAExp] Item Alchemy Expansion initialized. config={}",
				IAExpConfigHolder.configPath());
	}

	/**
	 * 调试日志开关：仅当 {@code config.debugLogging=true} 时输出 INFO 级别日志。
	 * <p>用于变体键生成、提取槽重建、注册/移除等高频路径的详细日志，关闭时静默以避免刷屏。
	 * warn/error 始终通过 {@link #LOGGER} 直接输出。</p>
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
