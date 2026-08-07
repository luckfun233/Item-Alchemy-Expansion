package itemalchemy.expansion.recipe;

import itemalchemy.expansion.IAExpServices;
import itemalchemy.expansion.ItemAlchemyExpansion;
import itemalchemy.expansion.config.IAExpConfig;
import itemalchemy.expansion.config.IAExpConfigHolder;
import itemalchemy.expansion.mixin.MixinEMCManager;
import itemalchemy.expansion.nbt.ItemVariantKey;
import itemalchemy.expansion.network.AutoEmcStore;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.Recipe;
import net.minecraft.recipe.RecipeManager;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import net.pitan76.itemalchemy.EMCManager;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配方自动定价器：扫描所有注册到原版 RecipeManager 的配方，
 * 对未定义 EMC 的物品按材料 EMC 之和自动定价。
 *
 * <h3>核心算法</h3>
 * <ol>
 *   <li><b>第一轮</b>：遍历所有配方，对能算出全部材料 EMC 的配方直接累加。</li>
 *   <li><b>迭代</b>：处理依赖未定价材料的配方（A→B→C 链）。最多 8 轮，每轮把
 *       仍未算出的加入 next 列表；若 next 大小未变则提前退出（无新进展）。</li>
 *   <li><b>强制轮</b>：最后一轮把剩余配方按"材料缺失按 0 计"算出（避免无限等待）。</li>
 *   <li><b>聚合</b>：变体键 -> MIN（多条配方产同变体取最小值，防套利）；
 *       通用层 = 精确层按 ID 派生的 MIN。</li>
 * </ol>
 *
 * <h3>兼容性</h3>
 * <p>不硬编码配方类型，用通用反射遍历：
 * <ul>
 *   <li><b>输出</b>：优先反射调 {@code getOutput()}（无参，TACZ 用）；
 *       否则回退到 {@link Recipe#getOutput(net.minecraft.registry.DynamicRegistryManager)}。
 *       对 ShapedRecipe/ShapelessRecipe 也可走 {@code craft(world)} 但反射路径已足够通用。</li>
 *   <li><b>输入</b>：优先反射调 {@code getInputs()}（无参，返回 List）；
 *       否则回退到 {@link Recipe#getIngredients()}。
 *       元素若是 {@link Ingredient} 直接用；若是含 {@code getIngredient()} 的包装类
 *       （如 TACZ {@code GunSmithTableIngredient}）解包并乘以 count。</li>
 *   <li><b>异常</b>：单条配方异常不影响整体，try-catch 跳过。</li>
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
 * </p>
 *
 * <h3>触发时机</h3>
 * <ul>
 *   <li>服务端 {@code SERVER_STARTED}：在 {@code PerSaveEmcStore.load} 与 {@code PreciseEmcStore.load} 之后</li>
 *   <li>缓存命中跳过重算（启动快）</li>
 *   <li>玩家手动 {@code /itemalchemy-expansion reprice} 强制重算</li>
 * </ul>
 * </p>
 */
public final class RecipeAutoPricer {

    /** 最大迭代轮数（防死循环） */
    private static final int MAX_ITER = 8;
    /** 反射缓存：getOutput() 无参方法（按类缓存，避免重复反射查找） */
    private static final Map<Class<?>, Method> GET_OUTPUT_CACHE = new HashMap<>();
    /** 反射缓存：getInputs() 无参方法 */
    private static final Map<Class<?>, Method> GET_INPUTS_CACHE = new HashMap<>();
    /** 反射缓存：包装类 getIngredient() */
    private static final Map<Class<?>, Method> GET_INGREDIENT_CACHE = new HashMap<>();
    /** 反射缓存：包装类 getCount() */
    private static final Map<Class<?>, Method> GET_COUNT_CACHE = new HashMap<>();

    private RecipeAutoPricer() {}

    /**
     * 扫描所有配方，计算未定义物品的 EMC，写入 {@link AutoEmcStore} 并写缓存。
     *
     * <p>若缓存文件存在且版本匹配则直接加载，跳过重算。</p>
     */
    public static void computeAndStore(MinecraftServer server) {
        // 1. 缓存命中跳过
        if (AutoEmcStore.tryLoadCache(server)) {
            ItemAlchemyExpansion.LOGGER.info("[IAExp] RecipeAutoPricer: cache hit, skip recompute");
            return;
        }

        ItemAlchemyExpansion.LOGGER.info("[IAExp] RecipeAutoPricer: computing emc from recipes...");
        long startTime = System.currentTimeMillis();

        World world = server.getOverworld();
        if (world == null) {
            ItemAlchemyExpansion.LOGGER.warn("[IAExp] RecipeAutoPricer: overworld is null, abort");
            return;
        }
        RecipeManager rm = world.getRecipeManager();

        // 2. 收集所有 Recipe（1.20.1 yarn 无 RecipeEntry 包装，values() 返回 Collection<Recipe<?>>）
        Collection<Recipe<?>> entries;
        try {
            entries = rm.values();
        } catch (Throwable t) {
            ItemAlchemyExpansion.LOGGER.error("[IAExp] RecipeAutoPricer: cannot list recipes: {}", t.toString());
            return;
        }

        ItemAlchemyExpansion.debug("[IAExp] RecipeAutoPricer: total recipes = {}", entries.size());

        // 3. 第一轮 + 迭代
        Map<String, Long> preciseAcc = new LinkedHashMap<>();   // 变体键 -> 候选 EMC
        List<Recipe<?>> deferred = new ArrayList<>();

        for (Recipe<?> recipe : entries) {
            try {
                processRecipe(recipe, world, preciseAcc, deferred, false);
            } catch (Throwable ignore) {
                // 单条配方异常跳过
            }
        }

        // 4. 迭代处理依赖未定价材料的配方
        int iter = 0;
        while (!deferred.isEmpty() && iter < MAX_ITER) {
            iter++;
            List<Recipe<?>> next = new ArrayList<>();
            for (Recipe<?> recipe : deferred) {
                try {
                    processRecipe(recipe, world, preciseAcc, next, false);
                } catch (Throwable ignore) {}
            }
            if (next.size() == deferred.size()) {
                // 无进展，跳出
                break;
            }
            deferred = next;
        }

        // 5. 强制轮：剩余配方按"材料缺失按 0 计"
        for (Recipe<?> recipe : deferred) {
            try {
                processRecipe(recipe, world, preciseAcc, new ArrayList<>(), true);
            } catch (Throwable ignore) {}
        }

        // 6. 聚合：精确层按变体键 MIN；通用层按 ID 派生 MIN
        Map<String, Long> preciseMap = new LinkedHashMap<>(preciseAcc);
        Map<String, Long> generalMap = deriveGeneralMin(preciseMap);

        // 7. 过滤：尊重上游 defaultEMCMap
        IAExpConfig cfg = IAExpConfigHolder.get();
        if (cfg.autoPricingRespectUpstream) {
            // 通用层移除上游已定义（精确层保留供精确模式细分使用）
            try {
                generalMap.keySet().removeAll(EMCManager.defaultEMCMap.keySet());
            } catch (Throwable ignore) {}
        }

        // 8. 写入 AutoEmcStore + 写缓存
        AutoEmcStore.store(preciseMap, generalMap);
        AutoEmcStore.writeCache(server);

        long elapsed = System.currentTimeMillis() - startTime;
        ItemAlchemyExpansion.LOGGER.info("[IAExp] RecipeAutoPricer: done. precise={} entries, general={} entries, took {} ms",
                preciseMap.size(), generalMap.size(), elapsed);
    }

    /**
     * 强制重算（玩家运行 {@code /itemalchemy-expansion reprice} 时调用）。
     *
     * <p>跳过缓存，删除缓存文件后重新扫描。</p>
     */
    public static void forceRecompute(MinecraftServer server) {
        AutoEmcStore.deleteCache(server);
        AutoEmcStore.clear();
        computeAndStore(server);
    }

    // ============ 内部：处理单条配方 ============

    /**
     * @param recipe    配方（1.20.1 yarn 中 Recipe 自带 id，无 RecipeEntry 包装）
     * @param world     世界
     * @param acc       累加器：变体键 -> 候选 EMC（按 MIN 聚合）
     * @param deferred  延迟列表：材料未定价时加入此列表等待下轮
     * @param forceLast 强制轮：材料缺失按 0 计
     */
    private static void processRecipe(Recipe<?> recipe, World world,
            Map<String, Long> acc, List<Recipe<?>> deferred, boolean forceLast) {
        if (recipe == null) return;

        // 1. 拿输出
        ItemStack outStack = extractOutput(recipe, world);
        if (outStack == null || outStack.isEmpty()) return;

        String itemId = resolveItemId(outStack);
        if ("minecraft:air".equals(itemId)) return;

        // 2. 过滤：尊重上游默认值（精确层仍算，但通用层会在最后移除）
        IAExpConfig cfg = IAExpConfigHolder.get();
        if (cfg.autoPricingRespectUpstream) {
            try {
                if (EMCManager.defaultEMCMap.containsKey(itemId)) return;
            } catch (Throwable ignore) {}
        }

        // 3. 拿输入
        List<InputEntry> inputs = extractInputs(recipe);
        if (inputs.isEmpty()) return;

        // 4. 算总 EMC
        long totalEmc = 0;
        boolean allInputsKnown = true;
        int outCount = outStack.getCount();
        if (outCount <= 0) outCount = 1;

        for (InputEntry ie : inputs) {
            if (ie.ingredient == null) {
                allInputsKnown = false;
                continue;
            }
            ItemStack[] stacks = ie.ingredient.getMatchingStacks();
            if (stacks.length == 0) {
                allInputsKnown = false;
                continue;
            }
            // 取第一个匹配堆的 EMC（按 ID 查，不递归本配方）
            long inEmc = MixinEMCManager.resolveEmcForInput(stacks[0]);
            if (inEmc <= 0) {
                allInputsKnown = false;
                continue;
            }
            // 上游 addEmcFromRecipe 的语义：材料 EMC / 产出数量
            totalEmc += (inEmc / outCount) * ie.count;
        }

        if (totalEmc <= 0) return;
        if (!allInputsKnown && !forceLast) {
            deferred.add(recipe);
            return;
        }

        // 5. 算变体键并按 MIN 聚合
        ItemVariantKey vk = IAExpServices.variantKeyOf(outStack);
        String vkStr = vk.toStorageString();
        acc.merge(vkStr, totalEmc, Math::min);

        ItemAlchemyExpansion.debug("[IAExp] RecipeAutoPricer: priced {} -> {} (recipe={})",
                vkStr, totalEmc, recipe.getId());
    }

    // ============ 反射：拿输出 ============

    /** 优先反射调 getOutput()（TACZ 无参版本）；失败回退到 1.20.1 yarn 的 getOutput(DynamicRegistryManager) */
    private static ItemStack extractOutput(Recipe<?> recipe, World world) {
        Method m = GET_OUTPUT_CACHE.get(recipe.getClass());
        if (m == null) {
            try {
                m = recipe.getClass().getMethod("getOutput");
            } catch (NoSuchMethodException e) {
                m = null;
            }
            GET_OUTPUT_CACHE.put(recipe.getClass(), m);
        }
        if (m != null) {
            try {
                Object r = m.invoke(recipe);
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
        // 回退：1.20.1 yarn 中 Recipe.getOutput(DynamicRegistryManager) 返回 ItemStack
        try {
            return recipe.getOutput(world.getRegistryManager());
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }

    // ============ 反射：拿输入 ============

    /** 优先反射调 getInputs()（TACZ 返回 List<GunSmithTableIngredient>）；失败回退到 getIngredients() */
    private static List<InputEntry> extractInputs(Recipe<?> recipe) {
        List<InputEntry> result = new ArrayList<>();

        Method m = GET_INPUTS_CACHE.get(recipe.getClass());
        if (m == null) {
            try {
                m = recipe.getClass().getMethod("getInputs");
            } catch (NoSuchMethodException e) {
                m = null;
            }
            GET_INPUTS_CACHE.put(recipe.getClass(), m);
        }
        if (m != null) {
            try {
                Object r = m.invoke(recipe);
                if (r instanceof List) {
                    for (Object item : (List<?>) r) {
                        InputEntry ie = wrapInput(item);
                        if (ie != null) result.add(ie);
                    }
                    if (!result.isEmpty()) return result;
                }
            } catch (Throwable ignore) {}
        }

        // 回退：getIngredients()（原版默认实现）
        try {
            for (Ingredient ing : recipe.getIngredients()) {
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
        Method getIng = GET_INGREDIENT_CACHE.get(cls);
        Method getCt = GET_COUNT_CACHE.get(cls);
        if (getIng == null) {
            try {
                getIng = cls.getMethod("getIngredient");
            } catch (NoSuchMethodException e) {
                getIng = null;
            }
            GET_INGREDIENT_CACHE.put(cls, getIng);
        }
        if (getCt == null) {
            try {
                getCt = cls.getMethod("getCount");
            } catch (NoSuchMethodException e) {
                getCt = null;
            }
            GET_COUNT_CACHE.put(cls, getCt);
        }
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

    /** 解析 ItemStack 的 itemId */
    private static String resolveItemId(ItemStack stack) {
        Identifier id = Registries.ITEM.getId(stack.getItem());
        return id == null ? "minecraft:air" : id.toString();
    }

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
