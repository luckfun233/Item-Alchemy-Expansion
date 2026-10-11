package itemalchemy.expansion;

import itemalchemy.expansion.block.CardForgeBlocks;
import itemalchemy.expansion.block.EmcAutoBlocks;
import itemalchemy.expansion.command.IAExpCommand;
import itemalchemy.expansion.config.IAExpConfig;
import itemalchemy.expansion.config.IAExpConfigHolder;
import itemalchemy.expansion.gui.CardForgeScreenHandlers;
import itemalchemy.expansion.gui.EmcConverterScreenHandlers;
import itemalchemy.expansion.gui.EmcEmitterScreenHandlers;
import itemalchemy.expansion.item.IAExpItems;
import itemalchemy.expansion.nbt.ComponentNbtView;
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
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroupEntries;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.PreparedRecipes;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.ServerRecipeManager;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.resource.featuretoggle.FeatureSet;
import net.minecraft.util.Identifier;
import net.pitan76.mcpitanlib.api.command.CommandRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
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
		EmcEmitterScreenHandlers.init();

		// 本模组物品加入 Item Alchemy 创造物品栏：EMC 卡 + 制卡台
		// 统一走 Fabric 事件，不用 mcpitanlib 的 addGroup——后者依赖 mcpitanlib 注册期的栏位绑定，
		// 而本模组的物品用原版 Registry.register 注册，绑定会被静默跳过（26.2 分支同款做法）
		RegistryKey<ItemGroup> itemAlchemyTab = RegistryKey.of(RegistryKeys.ITEM_GROUP,
				Identifier.of("itemalchemy", "item_alchemy"));
		try {
			ItemGroupEvents.modifyEntriesEvent(itemAlchemyTab)
					.register(entries -> {
						entries.add(new ItemStack(IAExpItems.EMC_CARD));
						entries.add(new ItemStack(CardForgeBlocks.FORGE_ITEM));
					});
		} catch (Throwable t) {
			LOGGER.warn("[IAExp] Failed to register card forge in item group: {}", t.toString());
		}

		// 自动装置加入创造物品栏（总开关关闭时不出现在物品栏；回调内运行时判定，reload 后即时生效）
		try {
			ItemGroupEvents.modifyEntriesEvent(itemAlchemyTab)
					.register(entries -> {
						if (!IAExpConfigHolder.get().automationEnabled) return;
						entries.add(new ItemStack(EmcAutoBlocks.CONVERTER_ITEM));
						entries.add(new ItemStack(EmcAutoBlocks.EMITTER_ITEM));
					});
		} catch (Throwable t) {
			LOGGER.warn("[IAExp] Failed to register automation blocks in item group: {}", t.toString());
		}

		// 上游物品兜底：见 addMissingItemAlchemyEntries 的注释
		try {
			ItemGroupEvents.modifyEntriesEvent(itemAlchemyTab)
					.register(ItemAlchemyExpansion::addMissingItemAlchemyEntries);
		} catch (Throwable t) {
			LOGGER.warn("[IAExp] Failed to register item alchemy tab fallback: {}", t.toString());
		}

		SetEmcNetwork.registerServer();
		EmcCardNetwork.registerServer();
		CardForgeNetwork.registerServer();
		EmcAutoNetwork.registerServer();
		FilterModeNetwork.registerServer();
		CommandRegistry.register(MOD_ID, new IAExpCommand());

		// 自动定价分批扫描：每 tick 处理一批配方，未扫描时零开销
		ServerTickEvents.END_SERVER_TICK.register(RecipeAutoPricer::onServerTick);
		// EMC 卡关联共享账户：脏数据按间隔刷盘（避免红石自动化下每 tick 磁盘 I/O）
		ServerTickEvents.END_SERVER_TICK.register(CardAccountStore::onServerTick);
		// 服务器关闭时若扫描未完成则丢弃状态，避免下次启动残留
		ServerLifecycleEvents.SERVER_STOPPING.register(RecipeAutoPricer::onServerStopping);
		// 服务器关闭时保存 EMC 卡关联共享账户
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			try { CardAccountStore.save(server); } catch (Throwable t) {
				LOGGER.warn("[IAExp] Failed to save card accounts: {}", t.toString());
			}
		});

		// 指纹中的附魔等组件属于动态注册表，编解码需要当前注册表；数据包重载后需刷新
		ServerLifecycleEvents.SERVER_STARTED.register(server ->
				ComponentNbtView.setRegistryManager(server.getRegistryManager()));
		ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resourceManager, success) -> {
			if (success) ComponentNbtView.setRegistryManager(server.getRegistryManager());
		});

		// 自动装置总开关：启动时按配置状态同步合成配方（关闭则移除，阻止继续合成）
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			try {
				removedAutomationRecipes.clear(); // 新的服务器生命周期，配方表刚从数据包加载
				syncAutomationRecipes(server);
			} catch (Throwable t) {
				LOGGER.warn("[IAExp] Failed to sync automation recipes: {}", t.toString());
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
			try {
				SetEmcNetwork.pushSetEmcPermissionTo(handler.player);
			} catch (Throwable t) {
				LOGGER.warn("[IAExp] Failed to push set-emc permission on player join: {}", t.toString());
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
							handler.player.getName().getString());
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
	 * 上游物品入栏兜底。
	 *
	 * <p>mcpitanlib 4.x 的 {@code CompatibleItemSettings.addGroup(CreativeTabBuilder)} 只记录栏位 id、
	 * 不记录物品 id，而 {@code build()} 的入栏条件是「栏位 id 与物品 id 都不为空」，于是走该 API 的物品
	 * 会被整批漏掉（上游 Item Alchemy 1.4.1 的 64 个物品全部依赖它）。这里按命名空间补齐：
	 * 上游「注册物品数 == addGroup 数」，不会误加；栏位集合是 {@code ItemStackSet}，
	 * 环境正常（mcpitanlib 已修）时重复项会被去重。</p>
	 */
	private static void addMissingItemAlchemyEntries(FabricItemGroupEntries entries) {
		List<ItemStack> present = entries.getDisplayStacks();
		int added = 0;
		for (Item item : Registries.ITEM) {
			if (!"itemalchemy".equals(Registries.ITEM.getId(item).getNamespace())) continue;
			if (containsItem(present, item)) continue;
			entries.add(new ItemStack(item));
			added++;
		}
		if (added > 0) {
			debug("[IAExp] added {} missing itemalchemy entries to the creative tab", added);
		}
	}

	/** 栏位条目里是否已有该物品（按物品比较，忽略组件差异） */
	private static boolean containsItem(List<ItemStack> stacks, Item item) {
		for (ItemStack stack : stacks) {
			if (stack.isOf(item)) return true;
		}
		return false;
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

	/** 缓存因总开关关闭而被移除的自动装置配方（运行时重新开启时恢复，避免重启） */
	private static final List<RecipeEntry<?>> removedAutomationRecipes = new ArrayList<>();

	private static boolean isAutomationRecipe(Identifier id) {
		return id.equals(Identifier.of(MOD_ID, "emc_converter"))
				|| id.equals(Identifier.of(MOD_ID, "emc_emitter"));
	}

	/**
	 * 幂等地将自动装置合成配方与总开关状态同步（启动/配置 reload 后调用）。
	 *
	 * <p>关闭：从配方表移除 emc_converter / emc_emitter 并缓存；重新开启：从缓存恢复，
	 * 无需重启服务器。缓存仅在本次服务器生命周期内有效，SERVER_STARTED 时清空。</p>
	 */
	public static void syncAutomationRecipes(net.minecraft.server.MinecraftServer server) {
		boolean enabled = IAExpConfigHolder.get().automationEnabled;
		ServerRecipeManager recipeManager = server.getRecipeManager();
		if (!enabled) {
			if (!removedAutomationRecipes.isEmpty()) return; // 已移除
			List<RecipeEntry<?>> kept = new ArrayList<>();
			for (RecipeEntry<?> r : recipeManager.values()) {
				if (isAutomationRecipe(r.id().getValue())) {
					removedAutomationRecipes.add(r);
				} else {
					kept.add(r);
				}
			}
			if (!removedAutomationRecipes.isEmpty()) {
				replaceRecipes(server, recipeManager, kept);
				LOGGER.info("[IAExp] Automation disabled: removed emc_converter + emc_emitter recipes");
			}
		} else if (!removedAutomationRecipes.isEmpty()) {
			List<RecipeEntry<?>> all = new ArrayList<>(recipeManager.values());
			all.addAll(removedAutomationRecipes);
			removedAutomationRecipes.clear();
			replaceRecipes(server, recipeManager, all);
			LOGGER.info("[IAExp] Automation enabled: restored emc_converter + emc_emitter recipes");
		}
	}

	/**
	 * 用给定配方集合替换配方表。
	 *
	 * <p>1.21.4 的 {@code ServerRecipeManager} 无公开 setter（旧版的 {@code setRecipes} 已移除），
	 * 只能换掉私有的 {@code preparedRecipes} 字段再 {@code initialize} 重建派生索引。
	 * 字段名在 dev 为 yarn、prod 为 intermediary，故按类型反射定位。</p>
	 */
	private static void replaceRecipes(net.minecraft.server.MinecraftServer server,
									   ServerRecipeManager recipeManager,
									   List<RecipeEntry<?>> recipes) {
		try {
			Field target = null;
			for (Class<?> c = recipeManager.getClass(); c != null && target == null; c = c.getSuperclass()) {
				for (Field f : c.getDeclaredFields()) {
					if (f.getType() == PreparedRecipes.class) {
						target = f;
						break;
					}
				}
			}
			if (target == null) {
				LOGGER.warn("[IAExp] preparedRecipes field not found; automation recipes left unchanged");
				return;
			}
			target.setAccessible(true);
			target.set(recipeManager, PreparedRecipes.of(recipes));
			// 重建 recipes / recipesByKey / propertySets 等派生数据，避免配方书仍显示已移除配方
			FeatureSet features = server.getSaveProperties().getEnabledFeatures();
			recipeManager.initialize(features);
		} catch (Throwable t) {
			LOGGER.warn("[IAExp] Failed to replace automation recipes: {}", t.toString());
		}
	}
}
