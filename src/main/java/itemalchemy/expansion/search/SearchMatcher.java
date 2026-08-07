package itemalchemy.expansion.search;

import itemalchemy.expansion.nbt.ShulkerBoxSupport;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import net.minecraft.registry.Registries;
import net.pitan76.mcpitanlib.api.util.CompatIdentifier;
import net.pitan76.mcpitanlib.midohra.item.ItemWrapper;
import net.pitan76.mcpitanlib.midohra.nbt.NbtCompound;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 搜索匹配工具：统一服务端排序与客户端渲染的匹配逻辑。
 *
 * <p><b>匹配范围</b>（搜索词 = 去掉 {@code @namespace} 前缀后小写化的文本）：</p>
 * <ul>
 *   <li><b>直接物品（非潜影盒）</b>：物品 id path / 翻译名 / 物品显示名 包含搜索词。</li>
 *   <li><b>潜影盒</b>：
 *     <ul>
 *       <li>自身名：物品 id path / 翻译名 / 物品显示名 / 自定义名（{@code display.Name}）包含搜索词。</li>
 *       <li>内容物：任一内容物的 id path / 翻译名 / 物品显示名 包含搜索词。</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p><b>命名空间过滤</b>：搜索词形如 {@code @minecraft stone} 时，{@code @minecraft} 限制命名空间，
 * 剩余 {@code stone} 作为搜索词。直接物品与潜影盒内容物都会检查命名空间；
 * 潜影盒自身名匹配时不检查命名空间（潜影盒自身 id 固定为 {@code minecraft:*_shulker_box}，
 * 限制 ns 会误伤「按自定义名搜索潜影盒」的语义）。</p>
 *
 * <p>注：{@code translations} 为 mcpitanlib {@link NbtCompound}（key=translationKey, value=翻译名），
 * 由 {@code AlchemyTableScreen.keyReleased} 构造并同步到服务端。两端共用同一份。</p>
 */
public final class SearchMatcher {

    private SearchMatcher() {}

    /** 匹配类型 */
    public enum MatchType {
        /** 不匹配 */
        NO_MATCH,
        /** 直接物品匹配（非潜影盒，且物品自身名命中） */
        DIRECT_MATCH,
        /** 潜影盒匹配（自身名或内容物命中） */
        SHULKER_MATCH
    }

    /** 解析后的搜索上下文 */
    public static final class SearchContext {
        /** 原始搜索文本（含可能的 @ns 前缀） */
        public final String rawSearchText;
        /** 去掉 @ns 后的搜索词小写形式（空字符串表示无文本过滤，仅 ns 过滤） */
        public final String searchLower;
        /** 命名空间过滤小写形式（空字符串表示不过滤） */
        public final String nsFilterLower;
        /** 翻译表（可能为 null） */
        public final NbtCompound translations;

        public SearchContext(String rawSearchText, String searchLower, String nsFilterLower, NbtCompound translations) {
            this.rawSearchText = rawSearchText;
            this.searchLower = searchLower;
            this.nsFilterLower = nsFilterLower;
            this.translations = translations;
        }

        /** 是否完全没有过滤条件（空搜索 + 无 ns） */
        public boolean isEmpty() {
            return searchLower.isEmpty() && nsFilterLower.isEmpty();
        }
    }

    private static final Pattern NS_PATTERN = Pattern.compile("@([a-zA-Z0-9_-]+)");

    /**
     * 解析搜索文本为上下文。
     *
     * <p>提取 {@code @(NAMESPACE)} 前缀，剩余部分小写化作为搜索词。
     * 与原版 {@code AlchemyTableScreenHandler.sortBySearch} 的解析逻辑一致，
     * 确保客户端重算与服务端排序使用相同的上下文。</p>
     */
    public static SearchContext parse(String searchText, NbtCompound translations) {
        if (searchText == null) searchText = "";
        String localSearch = searchText;
        String localNamespace = "";
        Matcher matcher = NS_PATTERN.matcher(localSearch);
        if (matcher.find()) {
            localNamespace = matcher.group(1);
            localSearch = localSearch.replaceFirst("@" + localNamespace + " ?", "");
        }
        return new SearchContext(searchText, localSearch.toLowerCase(), localNamespace.toLowerCase(), translations);
    }

    /** 判断物品 id 是否符合命名空间过滤 */
    private static boolean nsOk(String itemId, SearchContext ctx) {
        if (ctx.nsFilterLower.isEmpty()) return true;
        int idx = itemId.indexOf(':');
        String ns = (idx > 0) ? itemId.substring(0, idx) : "";
        return ns.toLowerCase().contains(ctx.nsFilterLower);
    }

    /**
     * 判断 path / 翻译名 / 显示名三者中是否有任一包含搜索词。
     *
     * <p>统一直接物品、潜影盒自身名、内容物三处的字段匹配逻辑。
     * 调用方负责空搜索与命名空间过滤的前置判断。</p>
     */
    private static boolean matchesAnyField(String path, String translated, String displayName, SearchContext ctx) {
        String search = ctx.searchLower;
        return path.toLowerCase().contains(search)
                || translated.toLowerCase().contains(search)
                || displayName.toLowerCase().contains(search);
    }

    /** 获取 ItemWrapper 的翻译名（从 translations 表查），无则返回空字符串 */
    private static String translatedName(ItemWrapper item, SearchContext ctx) {
        if (ctx.translations == null) return "";
        try {
            String key = item.getTranslationKey();
            if (ctx.translations.has(key)) return ctx.translations.getString(key);
        } catch (Throwable ignored) {}
        return "";
    }

    /** 获取原版 ItemStack 的翻译名（客户端渲染时用） */
    private static String translatedName(ItemStack stack, SearchContext ctx) {
        if (ctx.translations == null) return "";
        try {
            String key = stack.getTranslationKey();
            if (ctx.translations.has(key)) return ctx.translations.getString(key);
        } catch (Throwable ignored) {}
        return "";
    }

    /**
     * 判断直接物品（非潜影盒）是否匹配搜索上下文。
     * 服务端 sortBySearch 用（基于 ItemWrapper）。
     */
    public static boolean matchesDirectItem(String itemId, ItemWrapper item, SearchContext ctx) {
        if (!nsOk(itemId, ctx)) return false;
        if (ctx.searchLower.isEmpty()) return true; // 仅 ns 过滤
        CompatIdentifier id;
        try {
            id = CompatIdentifier.of(itemId);
        } catch (Throwable t) {
            return false;
        }
        String path = id.getPath();
        String translated = translatedName(item, ctx);
        String displayName;
        try {
            displayName = item.getName();
        } catch (Throwable t) {
            displayName = "";
        }
        return matchesAnyField(path, translated, displayName, ctx);
    }

    /**
     * 判断直接物品（原版 ItemStack）是否匹配。客户端渲染用。
     */
    public static boolean matchesDirectItem(ItemStack stack, SearchContext ctx) {
        if (ShulkerBoxSupport.isShulkerBox(stack)) return false;
        Identifier rawId = Registries.ITEM.getId(stack.getItem());
        String itemId = rawId == null ? "" : rawId.toString();
        if (!nsOk(itemId, ctx)) return false;
        if (ctx.searchLower.isEmpty()) return true;
        String path = rawId == null ? "" : rawId.getPath();
        String translated = translatedName(stack, ctx);
        String displayName;
        try {
            displayName = stack.getName().getString();
        } catch (Throwable t) {
            displayName = "";
        }
        return matchesAnyField(path, translated, displayName, ctx);
    }

    /**
     * 判断潜影盒自身名是否匹配（不检查命名空间，不检查内容物）。
     * 自定义名（display.Name）通过 {@link ItemStack#getName()} 自动包含。
     */
    public static boolean matchesShulkerBoxItself(ItemStack shulkerBox, SearchContext ctx) {
        if (ctx.searchLower.isEmpty()) return false; // 仅 ns 过滤时不算自身名匹配
        Identifier rawId = Registries.ITEM.getId(shulkerBox.getItem());
        String path = rawId == null ? "" : rawId.getPath();
        String translated = translatedName(shulkerBox, ctx);
        String displayName;
        try {
            displayName = shulkerBox.getName().getString();
        } catch (Throwable t) {
            displayName = "";
        }
        return matchesAnyField(path, translated, displayName, ctx);
    }

    /**
     * 返回潜影盒内匹配搜索词的内容物列表（按槽位顺序）。
     *
     * <p>基于 {@link ShulkerBoxSupport#getContents} 遍历 27 格，仅返回匹配的非空内容物。
     * 空列表表示无匹配。</p>
     */
    public static List<ItemStack> findShulkerMatchingContents(ItemStack shulkerBox, SearchContext ctx) {
        List<ItemStack> result = new ArrayList<>();
        if (!ShulkerBoxSupport.isShulkerBox(shulkerBox)) return result;
        ItemStack[] contents = ShulkerBoxSupport.getContents(shulkerBox);
        for (ItemStack content : contents) {
            if (content.isEmpty()) continue;
            if (matchesContentItem(content, ctx)) {
                result.add(content);
            }
        }
        return result;
    }

    /**
     * 判断内容物是否匹配（id path / 翻译名 / 显示名 + 命名空间过滤）。
     *
     * <p>public 供 {@code AlchemyTableScreenShulkerPreview} 在 Shift 预览时按格子判断是否画红框。</p>
     */
    public static boolean matchesContentItem(ItemStack content, SearchContext ctx) {
        Identifier rawId = Registries.ITEM.getId(content.getItem());
        String itemId = rawId == null ? "" : rawId.toString();
        if (!nsOk(itemId, ctx)) return false;
        if (ctx.searchLower.isEmpty()) return true; // 仅 ns 过滤
        String path = rawId == null ? "" : rawId.getPath();
        String translated = translatedName(content, ctx);
        String displayName;
        try {
            displayName = content.getName().getString();
        } catch (Throwable t) {
            displayName = "";
        }
        return matchesAnyField(path, translated, displayName, ctx);
    }

    /**
     * 综合判断一个槽位物品的匹配类型（客户端渲染用）。
     *
     * @param stack 槽位展示的 ItemStack
     * @return DIRECT_MATCH / SHULKER_MATCH / NO_MATCH
     */
    public static MatchType matchSlot(ItemStack stack, SearchContext ctx) {
        if (stack == null || stack.isEmpty()) return MatchType.NO_MATCH;
        if (ShulkerBoxSupport.isShulkerBox(stack)) {
            if (matchesShulkerBoxItself(stack, ctx)) return MatchType.SHULKER_MATCH;
            if (!findShulkerMatchingContents(stack, ctx).isEmpty()) return MatchType.SHULKER_MATCH;
            return MatchType.NO_MATCH;
        } else {
            return matchesDirectItem(stack, ctx) ? MatchType.DIRECT_MATCH : MatchType.NO_MATCH;
        }
    }
}
