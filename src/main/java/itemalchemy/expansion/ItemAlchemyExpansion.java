package itemalchemy.expansion;

import itemalchemy.expansion.block.CardForgeBlocks;
import itemalchemy.expansion.block.EmcAutoBlocks;
import itemalchemy.expansion.command.IAExpCommand;
import itemalchemy.expansion.config.IAExpConfig;
import itemalchemy.expansion.config.IAExpConfigHolder;
import itemalchemy.expansion.gui.CardForgeScreenHandlers;
import itemalchemy.expansion.gui.EmcConverterScreenHandlers;
import itemalchemy.expansion.item.IAExpItems;
import itemalchemy.expansion.network.AutoEmcStore;
import itemalchemy.expansion.network.CardAccountStore;
import itemalchemy.expansion.network.CardForgeNetwork;
import itemalchemy.expansion.network.EmcAutoNetwork;
import itemalchemy.expansion.network.EmcCardNetwork;
import itemalchemy.expansion.network.FilterModeNetwork;
import itemalchemy.expansion.network.PerSaveEmcStore;
import itemalchemy.expansion.network.PreciseEmcStore;
import itemalchemy.expansion.network.SetEmcNetwork;
import itemalchemy.expansion.recipe.RecipeAutoPricer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.Recipe;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.pitan76.mcpitanlib.api.command.CommandRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class ItemAlchemyExpansion implements ModInitializer {
	public static final String MOD_ID = "itemalchemy-expansion";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		IAExpServices.init();

		IAExpItems.init();
		CardForgeBlocks.init();
		EmcAutoBlocks.init();
		CardForgeScreenHandlers.init();
		EmcConverterScreenHandlers.init();

		// 制卡台加入 Item Alchemy 创造物品栏
		try {
			ItemGroupEvents.modifyEntriesEvent(
					RegistryKey.of(RegistryKeys.ITEM_GROUP, new Identifier("itemalchemy", "item_alchemy")))
					.register(entries -> entries.add(new ItemStack(CardForgeBlocks.FORGE_ITEM)));
		} catch (Throwable t) {
			LOGGER.warn("[IAExp] Failed to register card forge in item group: {}", t.toString());
		}

		// 自动装置加入创造物品栏（总开关关闭时不在物品栏出现）
		if (IAExpConfigHolder.get().automationEnabled) {
			try {
				ItemGroupEvents.modifyEntriesEvent(
						RegistryKey.of(RegistryKeys.ITEM_GROUP, new Identifier("itemalchemy", "item_alchemy")))
						.register(entries -> {
							entries.add(new ItemStack(EmcAutoBlocks.CONVERTER_ITEM));
							entries.add(new ItemStack(EmcAutoBlocks.EMITTER_ITEM));
						});
			} catch (Throwable t) {
				LOGGER.warn("[IAExp] Failed to register automation blocks in item group: {}", t.toString());
			}
		}

		SetEmcNetwork.registerServer();
		EmcCardNetwork.registerServer();
		CardForgeNetwork.registerServer();
		EmcAutoNetwork.registerServer();
		FilterModeNetwork.registerServer();
		CommandRegistry.register(MOD_ID, new IAExpCommand());

		// 自动定价分批扫描：每 tick 处理一批配方，未扫描时零开销
		ServerTickEvents.END_SERVER_TICK.register(RecipeAutoPricer::onServerTick);
		// 服务器关闭时若扫描未完成则丢弃状态，避免下次启动残留
		ServerLifecycleEvents.SERVER_STOPPING.register(RecipeAutoPricer::onServerStopping);
		// 服务器关闭时保存 EMC 卡关联共享账户
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			try { CardAccountStore.save(server); } catch (Throwable t) {
				LOGGER.warn("[IAExp] Failed to save card accounts: {}", t.toString());
			}
		});

		// 自动装置总开关关闭时：移除两个自动装置的合成配方（无法再合成）
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			try {
				if (!IAExpConfigHolder.get().automationEnabled) {
					removeAutomationRecipes(server);
				}
			} catch (Throwable t) {
				LOGGER.warn("[IAExp] Failed to remove automation recipes: {}", t.toString());
			}
		});

		// SERVER_STARTED 在 EMCManager.init 之后触发：此时全局 emc_config.json 已在内存 map，
		// 本存档 overrides 覆盖其上
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			try {
				CardAccountStore.load(server);
			} catch (Throwable t) {
				LOGGER.warn("[IAExp] Failed to load card accounts: {}", t.toString());
			}
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

	/** 从配方表移除两个自动装置配方（总开关关闭时调用，阻止继续合成） */
	private static void removeAutomationRecipes(net.minecraft.server.MinecraftServer server) {
		Identifier converter = new Identifier(MOD_ID, "emc_converter");
		Identifier emitter = new Identifier(MOD_ID, "emc_emitter");
		List<Recipe<?>> kept = new ArrayList<>();
		for (Recipe<?> r : server.getRecipeManager().values()) {
			Identifier id = r.getId();
			if (id.equals(converter) || id.equals(emitter)) continue;
			kept.add(r);
		}
		server.getRecipeManager().setRecipes(kept);
		LOGGER.info("[IAExp] Automation disabled: removed emc_converter + emc_emitter recipes");
	}
}
