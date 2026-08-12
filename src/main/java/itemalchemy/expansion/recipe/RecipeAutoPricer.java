package itemalchemy.expansion.recipe;

import itemalchemy.expansion.IAExpServices;
import itemalchemy.expansion.ItemAlchemyExpansion;
import itemalchemy.expansion.config.IAExpConfig;
import itemalchemy.expansion.config.IAExpConfigHolder;
import itemalchemy.expansion.nbt.ItemVariantKey;
import itemalchemy.expansion.network.AutoEmcStore;
import itemalchemy.expansion.network.SetEmcNetwork;
import itemalchemy.expansion.util.EmcQueryUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.Ingredient;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.pitan76.itemalchemy.EMCManager;
import net.pitan76.mcpitanlib.midohra.recipe.Recipe;
import net.pitan76.mcpitanlib.midohra.recipe.ServerRecipeManager;
import net.pitan76.mcpitanlib.midohra.recipe.entry.RecipeEntry;
import net.pitan76.mcpitanlib.midohra.world.ServerWorld;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配方自动定价器：扫描所有注册到原版 RecipeManager 的配方，对未定义 EMC 的物品按材料 EMC 之和自动定价。
 *
 * <h3>分批 + 时间片扫描（防卡顿，300+ 模组可用）</h3>
 * <p>借鉴 JEI 的延迟执行 / 时间预算思想：扫描不在 {@code SERVER_STARTED} 同步一次性完成，
 * 而是切分为多个批次，每个服务器 tick 处理一批（受 {@link IAExpConfig#autoPricingBatchSize}
 * 与 {@link IAExpConfig#autoPricingTickBudgetMs} 双重限制，先达上限即让出主线程）。
 * 由 {@link #onServerTick} 驱动，完成后一次性写入 {@link AutoEmcStore} 并落盘缓存。</p>
 *
 * <h3>核心算法</h3>
 * <ol>
 *   <li><b>第一轮</b>：遍历所有配方，对能算出全部材料 EMC 的配方直接累加。</li>
 *   <li><b>迭代</b>：处理依赖未定价材料的配方（A→B→C 链）。最多 {@value #MAX_ITER} 轮，
 *       每轮把仍未算出的加入 deferred 列表；若无新进展（deferred 数量未减少）则提前退出。
 *       <b>本轮已算出的精确值会参与下一轮解析</b>（见 {@link #resolveInputEmc}），
 *       让多步合成链在迭代中逐步定价，而非全部落到强制轮按缺失材料=0 算（更准确）。</li>
 *   <li><b>强制轮</b>：剩余配方按"材料缺失按 0 计"算出（避免无限等待）。</li>
 *   <li><b>聚合</b>：变体键 -> MIN（多条配方产同变体取最小值，防套利）；
 *       通用层 = 精确层按 ID 派生的 MIN。</li>
 * </ol>
 *
 * <h3>兼容性（借鉴 JEI 的通用化思路）</h3>
 * <p>不硬编码配方类型，用通用反射遍历：
 * <ul>
 *   <li><b>输出</b>：优先反射调 {@code getOutput()}（无参，TACZ 用）；
 *       否则回退到 midohra {@link Recipe#getOutput(net.pitan76.mcpitanlib.midohra.world.World)}。</li>
 *   <li><b>输入</b>：优先反射调 {@code getInputs()}（无参，返回 List）；
 *       否则回退到 midohra {@link Recipe#getInputs()}（内部转发原版 {@code getIngredients()}）。
 *       元素若是 {@link Ingredient} 直接用；若是含 {@code getIngredient()} 的包装类
 *       （如 TACZ {@code GunSmithTableIngredient}）解包并乘以 count。</li>
 *   <li><b>标签 Ingredient 取最便宜</b>：对多可替代输入（如 {@code #minecraft:planks}）
 *       遍历所有匹配堆取单价最低的（防套利）。</li>
 *   <li><b>异常隔离</b>：单条配方异常不影响整体扫描（try-catch 跳过），慢配方（&gt;2ms）在 debug 下告警。</li>
 * </ul>
 * </p>
 *
 * <h3>过滤</h3>
 * <ul>
 *   <li>{@code autoPricingRespectUpstream=true}：跳过 {@link EMCManager#defaultEMCMap} 已定义的物品
 *       （上游/原版值不覆盖）。但精确层仍可能算（精确模式细分时使用）。</li>
 *   <li>已在 {@link EMCManager#map} 中的物品（玩家或上游设定）：通用层不覆盖，
 *       精确层仍算（精确模式优先精确值，回退通用）。</li>
 * </ul>
 *
 * <h3>触发时机</h3>
 * <ul>
 *   <li>服务端 {@code SERVER_STARTED}：在 {@code PerSaveEmcStore.load} 与 {@code PreciseEmcStore.load} 之后</li>
 *   <li>缓存命中跳过重算</li>
 *   <li>玩家手动 {@code /itemalchemy-expansion reprice} 强制重算</li>
 * </ul>
 *
 * <p><b>注</b>：JEI 的 {@code IRecipeManager} 运行时仅在客户端可用，而本定价器在服务端 {@code SERVER_STARTED} 运行，
 * 无法直接复用 JEI 运行时。故此处采用 JEI 的<b>设计技巧</b>（通用化配方遍历、归一化取最便宜、时间预算、异常隔离）
 * 增强对原版 RecipeManager 的扫描，而非依赖 JEI 运行时。</p>
 */
public final class RecipeAutoPricer {

    /** 最大迭代轮数（防死循环） */
    private static final int MAX_ITER = 8;
    /** 单条配方耗时超过此值（ms）在 debug 下告警（JEI safeCallPlugin 风格） */
    private static final long SLOW_RECIPE_WARN_MS = 2;
    /** 进度日志间隔（ms） */
    private static final long PROGRESS_LOG_INTERVAL_MS = 5000;
    /** 检查时间预算的频率（每处理这么多条配方检查一次，避免每条都 nanoTime） */
    private static final int TIME_CHECK_INTERVAL = 32;

    /** 反射缓存：getOutput() 无参方法（按类缓存，避免重复反射查找） */
    private static final Map<Class<?>, Method> GET_OUTPUT_CACHE = new HashMap<>();
    /** 反射缓存：getInputs() 无参方法 */
    private static final Map<Class<?>, Method> GET_INPUTS_CACHE = new HashMap<>();
    /** 反射缓存：包装类 getIngredient() */
    private static final Map<Class<?>, Method> GET_INGREDIENT_CACHE = new HashMap<>();
    /** 反射缓存：包装类 getCount() */
    private static final Map<Class<?>, Method> GET_COUNT_CACHE = new HashMap<>();
    /** 反射缓存：init() 无参方法（TACZ GunSmithTableRecipe 延迟构建 ItemStack） */
    private static final Map<Class<?>, Method> INIT_CACHE = new HashMap<>();

    /** 强制轮用的空 deferred 列表（避免重复分配） */
    private static final List<RecipeEntry> EMPTY_DEFERRED = Collections.emptyList();

    // ====== 分批扫描状态（仅在服务器主线程访问，无需同步；computing 用 volatile 供快速短路） ======
    private static volatile boolean computing = false;
    private static MinecraftServer currentServer;
    private static World currentWorld;
    /** 当前轮待处理配方（index 游标遍历，避免 List.remove 的 O(n)） */
    private static List<RecipeEntry> queue;
    private static int queueIndex;
    /** 当前轮被延迟的配方（将作为下一轮的 queue） */
    private static List<RecipeEntry> deferred;
    /** 累加器：变体键 -> 候选 EMC（按 MIN 聚合）；同时供下一轮解析中间产物用 */
    private static Map<String, Long> preciseAcc;
    private static int iter;
    private static int roundStartQueueSize;
    private static long startMs;
    private static int totalRecipes;
    private static long lastProgressLogMs;

    private RecipeAutoPricer() {}

    /**
     * 扫描所有配方，计算未定义物品的 EMC，写入 {@link AutoEmcStore} 并写缓存。
     *
     * <p>若缓存文件存在且版本匹配则直接加载，跳过重算。
     * 缓存未命中时启动<b>分批时间片扫描</b>（不阻塞当前 tick），由 {@link #onServerTick} 推进。</p>
     */
    public static void computeAndStore(MinecraftServer server) {
        if (AutoEmcStore.tryLoadCache(server)) {
            ItemAlchemyExpansion.LOGGER.info("[IAExp] RecipeAutoPricer: cache hit, skip recompute");
            return;
        }
        startAsyncCompute(server);
    }

    /**
     * 强制重算（玩家运行 {@code /itemalchemy-expansion reprice} 时调用）。
     *
     * <p>跳过缓存，删除缓存文件后重新启动分批扫描。若已有扫描在进行中，先中止再重启。</p>
     */
    public static void forceRecompute(MinecraftServer server) {
        if (computing) {
            ItemAlchemyExpansion.LOGGER.info("[IAExp] RecipeAutoPricer: aborting in-progress scan to restart on demand");
            resetState();
        }
        AutoEmcStore.deleteCache(server);
        AutoEmcStore.clear();
        startAsyncCompute(server);
    }

    /**
     * 服务器每 tick 回调（由 {@code ItemAlchemyExpansion} 在初始化时一次性注册）。
     *
     * <p>未在扫描时立即返回（零开销）。扫描中则处理一批配方，受 batch size 与时间预算双重限制，
     * 处理完一批即返回，把剩余 tick 时间还给游戏逻辑，保护 TPS。</p>
     */
    public static void onServerTick(MinecraftServer server) {
        if (!computing) return;
        // 安全检查：服务器实例变化（理论上不会，但防御性重置）
        if (server != currentServer) {
            ItemAlchemyExpansion.LOGGER.warn("[IAExp] RecipeAutoPricer: server instance changed mid-scan, resetting");
            resetState();
            return;
        }

        IAExpConfig cfg = IAExpConfigHolder.get();
        int budget = Math.max(1, cfg.autoPricingBatchSize);
        long timeBudgetNs = Math.max(1, cfg.autoPricingTickBudgetMs) * 1_000_000L;
        long tickStart = System.nanoTime();
        int processed = 0;

        while (processed < budget) {
            if (queueIndex >= queue.size()) {
                // 当前轮队列耗尽：尝试进入下一轮，或完成
                if (!advanceRound()) {
                    finalizeCompute();
                    return;
                }
                continue;
            }
            RecipeEntry recipeEntry = queue.get(queueIndex++);
            long rStart = System.nanoTime();
            try {
                processRecipe(recipeEntry, currentWorld, preciseAcc, deferred, false);
            } catch (Throwable t) {
                // 单条配方异常隔离（JEI safeCallPlugin 风格：不让单条影响整体扫描）
                ItemAlchemyExpansion.debug("[IAExp] recipe threw, skipped: id={}, {}",
                        recipeEntry.getId(), t.toString());
            }
            long rMs = (System.nanoTime() - rStart) / 1_000_000;
            if (rMs > SLOW_RECIPE_WARN_MS) {
                ItemAlchemyExpansion.debug("[IAExp] slow recipe ({}ms): id={}", rMs, recipeEntry.getId());
            }
            processed++;
            // 每处理若干条检查一次时间预算，超限即让出主线程
            if ((processed % TIME_CHECK_INTERVAL) == 0
                    && (System.nanoTime() - tickStart) >= timeBudgetNs) {
                break;
            }
        }

        maybeLogProgress();
    }

    /**
     * 服务器关闭回调：若扫描未完成则丢弃状态，避免下次启动残留。
     * 缓存未写入即等于未定价（下次启动会重新扫描）。
     */
    public static void onServerStopping(MinecraftServer server) {
        if (computing) {
            ItemAlchemyExpansion.LOGGER.info("[IAExp] RecipeAutoPricer: server stopping, discarding incomplete scan");
            resetState();
        }
    }

    /** 是否正在扫描（供 UI/命令显示状态） */
    public static boolean isComputing() {
        return computing;
    }

    // ============ 分批扫描编排 ============

    /** 初始化扫描状态，首个批次交给下一次 {@link #onServerTick} 处理（不在调用线程同步执行，保持启动轻量） */
    private static void startAsyncCompute(MinecraftServer server) {
        if (computing) {
            ItemAlchemyExpansion.LOGGER.info("[IAExp] RecipeAutoPricer: aborting in-progress scan to restart");
            resetState();
        }
        World world = server.getOverworld();
        if (world == null) {
            ItemAlchemyExpansion.LOGGER.warn("[IAExp] RecipeAutoPricer: overworld is null, abort");
            return;
        }
        ServerWorld midohraWorld = ServerWorld.of((net.minecraft.server.world.ServerWorld) world);
        ServerRecipeManager rm = ServerRecipeManager.of(midohraWorld);
        Collection<RecipeEntry> entries;
        try {
            entries = rm.getRecipeEntries();
        } catch (Throwable t) {
            ItemAlchemyExpansion.LOGGER.error("[IAExp] RecipeAutoPricer: cannot list recipes: {}", t.toString());
            return;
        }

        computing = true;
        currentServer = server;
        currentWorld = world;
        queue = new ArrayList<>(entries);
        queueIndex = 0;
        roundStartQueueSize = queue.size();
        deferred = new ArrayList<>();
        preciseAcc = new LinkedHashMap<>();
        iter = 0;
        totalRecipes = queue.size();
        startMs = System.currentTimeMillis();
        lastProgressLogMs = startMs;

        IAExpConfig cfg = IAExpConfigHolder.get();
        ItemAlchemyExpansion.LOGGER.info(
                "[IAExp] RecipeAutoPricer: starting time-sliced scan of {} recipes (batch={}, budget={}ms/tick)",
                totalRecipes, cfg.autoPricingBatchSize, cfg.autoPricingTickBudgetMs);
    }

    /**
     * 当前轮队列耗尽时调用。若有被延迟配方且有进展，则把它们作为下一轮队列；否则进入强制轮后返回 false 触发完成。
     *
     * @return true 表示已设置好下一轮队列，继续处理；false 表示扫描应完成（已做强制轮）
     */
    private static boolean advanceRound() {
        int newDeferred = deferred.size();
        if (newDeferred == 0) {
            // 全部解析完毕
            return false;
        }
        // 进展判定：本轮新延迟数 < 本轮输入数 才算有进展（与原版 next.size()==deferred.size() 等价）
        boolean progress = newDeferred < roundStartQueueSize;
        if (iter >= MAX_ITER || !progress) {
            // 强制轮：剩余配方按"材料缺失按 0 计"算出
            for (RecipeEntry recipeEntry : deferred) {
                try {
                    processRecipe(recipeEntry, currentWorld, preciseAcc, EMPTY_DEFERRED, true);
                } catch (Throwable ignore) {
                    // 强制轮单条异常跳过
                }
            }
            deferred.clear();
            return false;
        }
        iter++;
        queue = deferred;
        deferred = new ArrayList<>();
        queueIndex = 0;
        roundStartQueueSize = queue.size();
        return true;
    }

    /** 聚合 + 过滤 + 写入 + 落盘，并重置状态 */
    private static void finalizeCompute() {
        Map<String, Long> preciseMap = new LinkedHashMap<>(preciseAcc);
        Map<String, Long> generalMap = deriveGeneralMin(preciseMap);

        IAExpConfig cfg = IAExpConfigHolder.get();
        if (cfg.autoPricingRespectUpstream) {
            // 通用层移除上游已定义（精确层保留供精确模式细分使用）
            try {
                generalMap.keySet().removeAll(EMCManager.defaultEMCMap.keySet());
            } catch (Throwable ignore) {}
        }

        AutoEmcStore.store(preciseMap, generalMap);
        AutoEmcStore.writeCache(currentServer);

        // 异步扫描在若干 tick 后才完成，需把新结果推给所有在线玩家
        // （SERVER_STARTED 时玩家尚未上线，由 JOIN 回调兜底；此处覆盖 /reprice 及扫描期间已上线的情况）
        try {
            SetEmcNetwork.resyncAllPublic(currentServer);
        } catch (Throwable t) {
            ItemAlchemyExpansion.LOGGER.warn("[IAExp] RecipeAutoPricer: failed to resync auto emc to players: {}", t.toString());
        }

        long elapsed = System.currentTimeMillis() - startMs;
        ItemAlchemyExpansion.LOGGER.info(
                "[IAExp] RecipeAutoPricer: done. precise={} entries, general={} entries, iter={}, took {} ms (time-sliced over ticks)",
                preciseMap.size(), generalMap.size(), iter, elapsed);
        resetState();
    }

    /** 清空所有扫描状态 */
    private static void resetState() {
        computing = false;
        currentServer = null;
        currentWorld = null;
        queue = null;
        queueIndex = 0;
        deferred = null;
        preciseAcc = null;
        iter = 0;
        roundStartQueueSize = 0;
        totalRecipes = 0;
        lastProgressLogMs = 0;
    }

    /** 周期性输出进度（每 5s 一次），让大整合包扫描过程可见 */
    private static void maybeLogProgress() {
        long now = System.currentTimeMillis();
        if (now - lastProgressLogMs < PROGRESS_LOG_INTERVAL_MS) return;
        lastProgressLogMs = now;
        int deferredNow = deferred == null ? 0 : deferred.size();
        int remainingRound = queue == null ? 0 : Math.max(0, queue.size() - queueIndex);
        ItemAlchemyExpansion.LOGGER.info(
                "[IAExp] RecipeAutoPricer: scanning... iter={}, round remaining={}, deferred={}, elapsed={}ms",
                iter, remainingRound, deferredNow, now - startMs);
    }

    // ============ 内部：处理单条配方 ============

    /**
     * @param recipeEntry 配方条目（1.21.1 RecipeEntry）
     * @param world     世界
     * @param acc       累加器：变体键 -> 候选 EMC（按 MIN 聚合）；同时供多步链解析中间产物
     * @param deferred  延迟列表：材料未定价时加入此列表等待下轮
     * @param forceLast 强制轮：材料缺失按 0 计
     */
    private static void processRecipe(RecipeEntry recipeEntry, World world,
            Map<String, Long> acc, List<RecipeEntry> deferred, boolean forceLast) {
        if (recipeEntry == null) return;

        Recipe recipe = recipeEntry.getRecipe();
        ItemStack outStack = extractOutput(recipe, world);
        if (outStack == null || outStack.isEmpty()) {
            ItemAlchemyExpansion.debug("[IAExp] recipe skipped (output empty after init): id={}",
                    recipeEntry.getId());
            return;
        }

        String itemId = EmcQueryUtil.resolveItemId(outStack);
        if ("minecraft:air".equals(itemId)) return;

        // 尊重上游默认值（精确层仍算，但通用层会在最后移除）
        IAExpConfig cfg = IAExpConfigHolder.get();
        if (cfg.autoPricingRespectUpstream) {
            try {
                if (EMCManager.defaultEMCMap.containsKey(itemId)) {
                    ItemAlchemyExpansion.debug("[IAExp] recipe skipped (upstream defined): id={}, itemId={}",
                            recipeEntry.getId(), itemId);
                    return;
                }
            } catch (Throwable ignore) {}
        }

        List<InputEntry> inputs = extractInputs(recipe);
        if (inputs.isEmpty()) return;

        // 对每个 Ingredient 取所有匹配堆中单价最低的（标签类多可替代输入取最便宜，防套利）
        long totalEmc = 0;
        boolean allInputsKnown = true;
        int outCount = outStack.getCount();
        if (outCount <= 0) outCount = 1;

        for (InputEntry ie : inputs) {
            if (ie.ingredient == null) {
                allInputsKnown = false;
                continue;
            }
            ItemStack[] stacks;
            try {
                stacks = ie.ingredient.getMatchingStacks();
            } catch (Throwable t) {
                // 某些模组 Ingredient 实现可能在 getMatchingStacks 抛异常
                allInputsKnown = false;
                continue;
            }
            if (stacks.length == 0) {
                allInputsKnown = false;
                continue;
            }
            // 遍历所有匹配堆取单价最低的（JEI getUid 归一化思路：4×木板与 1×木板按 item 归一后取最便宜）
            long bestUnit = -1;
            for (ItemStack ms : stacks) {
                if (ms == null || ms.isEmpty()) continue;
                long total = resolveInputEmc(ms, acc);
                if (total <= 0) continue;
                int c = ms.getCount();
                long unit = c > 0 ? total / c : total;
                if (bestUnit < 0 || unit < bestUnit) bestUnit = unit;
            }
            if (bestUnit < 0) {
                allInputsKnown = false;
                continue;
            }
            // 上游 addEmcFromRecipe 的语义：材料 EMC / 产出数量
            totalEmc += (bestUnit / outCount) * ie.count;
        }

        if (totalEmc <= 0) {
            ItemAlchemyExpansion.debug("[IAExp] recipe skipped (totalEmc=0): id={}, itemId={}",
                    recipeEntry.getId(), itemId);
            return;
        }
        if (!allInputsKnown && !forceLast) {
            deferred.add(recipeEntry);
            return;
        }

        ItemVariantKey vk = IAExpServices.variantKeyOf(outStack);
        String vkStr = vk.toStorageString();
        acc.merge(vkStr, totalEmc, Math::min);

        ItemAlchemyExpansion.debug("[IAExp] RecipeAutoPricer: priced {} -> {} (recipe={})",
                vkStr, totalEmc, recipeEntry.getId());
    }

    /**
     * 解析输入物品的 EMC，<b>优先查本轮已算出的精确层</b>（让 A→B→C 多步合成链在迭代中逐步解析），
     * 再回退到 {@link EmcQueryUtil#resolveEmcForInput}（上游/手动/已缓存自动值）。
     * 关键增强：让中间产物一旦在某轮算出，下一轮即可被依赖它的配方正确取价，
     * 避免多步链最终落到强制轮按缺失材料=0 算（偏低）。
     */
    private static long resolveInputEmc(ItemStack stack, Map<String, Long> acc) {
        if (stack == null || stack.isEmpty()) return 0;
        try {
            ItemVariantKey vk = IAExpServices.variantKeyOf(stack);
            Long scanVal = acc.get(vk.toStorageString());
            if (scanVal != null && scanVal > 0) {
                return scanVal * stack.getCount();
            }
        } catch (Throwable ignore) {
            // 变体键计算异常时回退到通用查询
        }
        return EmcQueryUtil.resolveEmcForInput(stack);
    }

    // ============ 反射：拿输出 ============

    /**
     * 反射查找无参方法并按类缓存结果。
     *
     * <p>统一 {@code getOutput}/{@code getInputs}/{@code getIngredient}/{@code getCount}/{@code init}
     * 五处相同的「查缓存 → 未命中则反射 {@link Class#getMethod(String)} → 存缓存」模式。
     * 方法不存在时缓存 null，避免重复反射查找。</p>
     *
     * @param cls         目标类
     * @param methodName  无参方法名
     * @param cache       按类缓存的结果 Map
     * @return 找到的 Method；不存在或异常返回 null
     */
    private static Method findNoArgMethod(Class<?> cls, String methodName, Map<Class<?>, Method> cache) {
        Method m = cache.get(cls);
        if (m == null) {
            try {
                m = cls.getMethod(methodName);
            } catch (NoSuchMethodException e) {
                m = null;
            }
            cache.put(cls, m);
        }
        return m;
    }

    /**
     * 优先反射调 getOutput()（TACZ 无参版本）；失败回退到 1.20.1 yarn 的 getOutput(DynamicRegistryManager)。
     *
     * <p><b>TACZ 延迟构建兼容</b>：TACZ 的 {@code GunSmithTableRecipe} 的 result 在未调用
     * {@code init()} 时返回 {@link ItemStack#EMPTY}。若首次 getOutput() 返回空，
     * 尝试反射调用 {@code recipe.init()}（若存在）后重新取输出。
     * init() 是幂等的（调用后 raw=null，重复调用安全）。</p>
     */
    private static ItemStack extractOutput(Recipe recipe, World world) {
        ItemStack result = tryGetOutput(recipe, world);
        if (result == null || result.isEmpty()) {
            // 尝试 init() 后重试（TACZ 延迟构建）
            tryInitRecipe(recipe);
            result = tryGetOutput(recipe, world);
        }
        return result == null ? ItemStack.EMPTY : result;
    }

    /** 实际尝试获取输出（不含 init 重试逻辑） */
    private static ItemStack tryGetOutput(Recipe recipe, World world) {
        net.minecraft.recipe.Recipe<?> rawRecipe = recipe.getRaw();
        Method m = findNoArgMethod(rawRecipe.getClass(), "getOutput", GET_OUTPUT_CACHE);
        if (m != null) {
            try {
                Object r = m.invoke(rawRecipe);
                if (r instanceof ItemStack) return (ItemStack) r;
                // mcpitanlib ItemStack 包装类
                if (r != null) {
                    try {
                        Method toMc = r.getClass().getMethod("toMinecraft");
                        Object mc = toMc.invoke(r);
                        if (mc instanceof ItemStack) return (ItemStack) mc;
                    } catch (Throwable ignore) {}
                }
            } catch (Throwable ignore) {}
        }
        // 回退：midohra Recipe.getOutput(World)（内部通过 craft 实现）
        try {
            return recipe.getOutput(net.pitan76.mcpitanlib.midohra.world.World.of(world)).toMinecraft();
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }

    /**
     * 反射调用 recipe 的 init() 方法（若存在）。
     * 用于 TACZ GunSmithTableRecipe：result 在 init() 后才构建真实 ItemStack。
     * 幂等：init() 内部会把 raw 置 null，重复调用安全。
     */
    private static void tryInitRecipe(Recipe recipe) {
        Method m = findNoArgMethod(recipe.getRaw().getClass(), "init", INIT_CACHE);
        if (m != null) {
            try {
                m.invoke(recipe.getRaw());
            } catch (Throwable ignore) {}
        }
    }

    // ============ 反射：拿输入 ============

    /** 优先反射调 getInputs()（TACZ 返回 List<GunSmithTableIngredient>）；失败回退到 midohra getInputs() */
    private static List<InputEntry> extractInputs(Recipe recipe) {
        List<InputEntry> result = new ArrayList<>();

        Method m = findNoArgMethod(recipe.getRaw().getClass(), "getInputs", GET_INPUTS_CACHE);
        if (m != null) {
            try {
                Object r = m.invoke(recipe.getRaw());
                if (r instanceof List) {
                    for (Object item : (List<?>) r) {
                        InputEntry ie = wrapInput(item);
                        if (ie != null) result.add(ie);
                    }
                    if (!result.isEmpty()) return result;
                }
            } catch (Throwable ignore) {}
        }

        // 回退：midohra Recipe.getInputs()（内部转发原版 getIngredients()）
        try {
            for (Ingredient ing : recipe.getInputs()) {
                if (ing != null) result.add(new InputEntry(ing, 1));
            }
        } catch (Throwable ignore) {}
        return result;
    }

    /**
     * 包装一个输入元素为 InputEntry。
     *
     * <p>支持类型：
     * <ul>
     *   <li>{@link Ingredient}：count=1</li>
     *   <li>含 {@code getIngredient()} 返回 {@link Ingredient} + {@code getCount()} 返回 int 的包装类
     *       （如 TACZ {@code GunSmithTableIngredient}）</li>
     *   <li>mcpitanlib {@code Ingredient} 包装类（含 toMinecraft()）</li>
     * </ul>
     * </p>
     */
    private static InputEntry wrapInput(Object item) {
        if (item == null) return null;
        if (item instanceof Ingredient) return new InputEntry((Ingredient) item, 1);

        // 反射 getIngredient() + getCount()
        Class<?> cls = item.getClass();
        Method getIng = findNoArgMethod(cls, "getIngredient", GET_INGREDIENT_CACHE);
        Method getCt = findNoArgMethod(cls, "getCount", GET_COUNT_CACHE);
        if (getIng != null) {
            try {
                Object rawIng = getIng.invoke(item);
                Ingredient ing = unwrapIngredient(rawIng);
                int count = 1;
                if (getCt != null) {
                    try {
                        Object cv = getCt.invoke(item);
                        if (cv instanceof Number) count = ((Number) cv).intValue();
                    } catch (Throwable ignore) {}
                }
                if (ing != null) return new InputEntry(ing, count);
            } catch (Throwable ignore) {}
        }
        return null;
    }

    /** 解包 mcpitanlib Ingredient 或直接返回原版 Ingredient */
    private static Ingredient unwrapIngredient(Object rawIng) {
        if (rawIng == null) return null;
        if (rawIng instanceof Ingredient) return (Ingredient) rawIng;
        // mcpitanlib Ingredient 包装类有 toMinecraft()
        try {
            Method toMc = rawIng.getClass().getMethod("toMinecraft");
            Object mc = toMc.invoke(rawIng);
            if (mc instanceof Ingredient) return (Ingredient) mc;
        } catch (Throwable ignore) {}
        return null;
    }

    // ============ 工具 ============

    /** 从精确层派生通用层：按物品 ID 取该 ID 下所有变体的 MIN */
    private static Map<String, Long> deriveGeneralMin(Map<String, Long> preciseMap) {
        Map<String, Long> general = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : preciseMap.entrySet()) {
            String vk = e.getKey();
            // 解析出 itemId（去掉 \u0001 后面的指纹）
            String itemId;
            int sep = vk.indexOf(ItemVariantKey.SEPARATOR);
            itemId = sep < 0 ? vk : vk.substring(0, sep);
            general.merge(itemId, e.getValue(), Math::min);
        }
        return general;
    }

    /** 输入条目：Ingredient + count */
    private static final class InputEntry {
        final Ingredient ingredient;
        final int count;
        InputEntry(Ingredient ingredient, int count) {
            this.ingredient = ingredient;
            this.count = Math.max(1, count);
        }
    }
}