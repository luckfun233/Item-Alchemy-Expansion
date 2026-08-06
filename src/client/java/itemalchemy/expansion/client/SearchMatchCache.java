package itemalchemy.expansion.client;

import itemalchemy.expansion.search.SearchMatcher;
import net.minecraft.item.ItemStack;
import net.pitan76.itemalchemy.gui.inventory.ExtractInventory;
import net.pitan76.itemalchemy.gui.screen.AlchemyTableScreenHandler;
import net.pitan76.mcpitanlib.midohra.nbt.NbtCompound;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端搜索匹配缓存：根据当前 searchText + definedStacks 计算每个提取槽的匹配结果。
 *
 * <p>供客户端渲染钩子（右下角小标志、tooltip 匹配列表）与 Shift 预览红框共用，
 * 避免每帧重复解析 SearchContext。</p>
 *
 * <p><b>缓存策略</b>：SearchContext 基于 searchText 缓存，searchText 变化时重新解析。
 * 槽位匹配结果不缓存（每帧重算），因为 definedStacks 可能因翻页/搜索变化，
 * 且 13 个槽位的匹配计算开销可接受。</p>
 *
 * <p><b>translations 读取</b>：反射读 {@code AlchemyTableScreenHandler.translations} 字段
 * （mcpitanlib NbtCompound）。与服务端 Mixin 的反射逻辑一致，容忍字段缺失。</p>
 */
public final class SearchMatchCache {

    private SearchMatchCache() {}

    /** 单个槽位的匹配结果 */
    public static final class SlotMatch {
        /** 匹配类型（NO_MATCH 时表示不匹配，不应缓存此类结果） */
        public final SearchMatcher.MatchType matchType;
        /** 匹配的内容物列表（仅 SHULKER_MATCH 且有搜索词时非空；DIRECT_MATCH 或空搜索时为空列表） */
        public final List<ItemStack> matchedContents;
        /** 第一个匹配的内容物（用于右下角小标志；DIRECT_MATCH 时为 null；SHULKER_MATCH 无内容物匹配时为 null） */
        public final ItemStack firstMatchedContent;

        public SlotMatch(SearchMatcher.MatchType matchType, List<ItemStack> matchedContents, ItemStack firstMatchedContent) {
            this.matchType = matchType;
            this.matchedContents = matchedContents;
            this.firstMatchedContent = firstMatchedContent;
        }
    }

    // ====== SearchContext 缓存 ======
    private static String cachedSearchText = "\u0000sentinel"; // 初始哨兵值，确保首次 != 真实 searchText
    private static SearchMatcher.SearchContext cachedCtx = null;

    /** 反射读取 translations 字段（缓存 Field） */
    private static Field translationsField;
    private static boolean translationsFieldResolved = false;

    /**
     * 获取当前搜索上下文（带缓存）。
     *
     * @param handler 当前 AlchemyTableScreenHandler
     * @return SearchContext；handler 为 null 时返回空上下文
     */
    public static SearchMatcher.SearchContext getContext(AlchemyTableScreenHandler handler) {
        String searchText = (handler != null) ? handler.searchText : "";
        if (searchText == null) searchText = "";
        if (!searchText.equals(cachedSearchText)) {
            cachedSearchText = searchText;
            NbtCompound translations = readTranslations(handler);
            cachedCtx = SearchMatcher.parse(searchText, translations);
        }
        return cachedCtx;
    }

    /** 反射读取 AlchemyTableScreenHandler.translations 字段 */
    private static NbtCompound readTranslations(AlchemyTableScreenHandler handler) {
        if (handler == null) return null;
        if (!translationsFieldResolved) {
            try {
                translationsField = AlchemyTableScreenHandler.class.getDeclaredField("translations");
                translationsField.setAccessible(true);
            } catch (Throwable t) {
                translationsField = null;
            }
            translationsFieldResolved = true;
        }
        if (translationsField == null) return null;
        try {
            return (NbtCompound) translationsField.get(handler);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 计算当前 13 个提取槽（slot 64~76）的匹配结果。
     *
     * @param handler 当前 AlchemyTableScreenHandler
     * @param extractInv 关联的 ExtractInventory（读 definedStacks）
     * @return Map<slotIndex, SlotMatch>，仅包含匹配的槽位（NO_MATCH 不加入）；空搜索时返回空 Map
     */
    public static Map<Integer, SlotMatch> computeSlotMatches(AlchemyTableScreenHandler handler, ExtractInventory extractInv) {
        Map<Integer, SlotMatch> result = new HashMap<>();
        if (handler == null || extractInv == null) return result;
        SearchMatcher.SearchContext ctx = getContext(handler);
        // 空搜索时不需要匹配标记（全部显示，无小标志）
        if (ctx.isEmpty()) return result;

        Map<Integer, ItemStack> definedStacks = extractInv.definedStacks;
        if (definedStacks == null || definedStacks.isEmpty()) return result;

        for (Map.Entry<Integer, ItemStack> entry : definedStacks.entrySet()) {
            int slotIndex = entry.getKey();
            ItemStack stack = entry.getValue();
            if (stack == null || stack.isEmpty()) continue;

            SearchMatcher.MatchType type = SearchMatcher.matchSlot(stack, ctx);
            if (type == SearchMatcher.MatchType.NO_MATCH) continue;

            List<ItemStack> matchedContents;
            ItemStack firstContent;
            if (type == SearchMatcher.MatchType.SHULKER_MATCH) {
                matchedContents = SearchMatcher.findShulkerMatchingContents(stack, ctx);
                firstContent = matchedContents.isEmpty() ? null : matchedContents.get(0);
            } else {
                matchedContents = Collections.emptyList();
                firstContent = null;
            }
            result.put(slotIndex, new SlotMatch(type, matchedContents, firstContent));
        }
        return result;
    }

    /** 重置缓存（屏幕关闭/切换时调用，避免持有旧 ScreenHandler 数据） */
    public static void reset() {
        cachedSearchText = "\u0000sentinel";
        cachedCtx = null;
    }
}
