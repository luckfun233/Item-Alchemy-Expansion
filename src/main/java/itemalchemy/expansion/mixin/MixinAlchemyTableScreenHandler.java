package itemalchemy.expansion.mixin;

import itemalchemy.expansion.IAExpServices;
import itemalchemy.expansion.ItemAlchemyExpansion;
import itemalchemy.expansion.nbt.ItemVariantKey;
import itemalchemy.expansion.nbt.ShulkerBoxSupport;
import itemalchemy.expansion.search.IAlchemyTableScreenHandlerExt;
import itemalchemy.expansion.search.SearchFilterMode;
import itemalchemy.expansion.search.SearchMatcher;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.pitan76.itemalchemy.api.PlayerRegisteredItemUtil;
import net.pitan76.itemalchemy.gui.inventory.ExtractInventory;
import net.pitan76.itemalchemy.gui.screen.AlchemyTableScreenHandler;
import net.pitan76.mcpitanlib.api.entity.Player;
import net.pitan76.mcpitanlib.api.util.CompatIdentifier;
import net.pitan76.mcpitanlib.api.util.item.ItemUtil;
import net.pitan76.mcpitanlib.midohra.item.ItemWrapper;
import net.pitan76.mcpitanlib.midohra.nbt.NbtCompound;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * 重写 sortBySearch 以支持变体键、潜影盒内容物搜索、权重排序与筛选模式。
 *
 * <p><b>重写要点</b>：
 * <ul>
 *   <li><b>变体键保留</b>：原 {@code CompatIdentifier.of(id)} 对变体键（含 \u0001）解析失败，
 *       且 {@code id.toLowerCase()} 破坏 NBT 指纹大小写。本 Mixin 在 HEAD cancel 重写，保留原变体键。</li>
 *   <li><b>潜影盒内容物搜索</b>：对每个潜影盒变体键重建 ItemStack，用 {@link SearchMatcher}
 *       检查「自身名 + 内容物 id/翻译名/显示名」是否匹配搜索词。</li>
 *   <li><b>权重排序</b>：直接物品匹配在前，潜影盒匹配在后（用户需求：直接拥有的物品优先）。</li>
 *   <li><b>筛选模式</b>：{@link SearchFilterMode#ALL} 全部 / {@link SearchFilterMode#DIRECT_ONLY}
 *       仅直接物品 / {@link SearchFilterMode#SHULKER_ONLY} 仅潜影盒。</li>
 *   <li><b>筛选模式状态</b>：通过 {@link IAlchemyTableScreenHandlerExt} 接口暴露的 @Unique 字段，
 *       客户端与服务端各持一份，客户端切换时通过 {@code filter_mode} 网络包同步。</li>
 * </ul></p>
 */
@Mixin(value = AlchemyTableScreenHandler.class, priority = 500)
public abstract class MixinAlchemyTableScreenHandler implements IAlchemyTableScreenHandlerExt {

    @Shadow public Player player;
    @Shadow public String searchText;
    @Shadow public String searchNamespace;
    @Shadow public ExtractInventory extractInventory;

    /** 筛选模式状态（@Unique，通过接口暴露） */
    @Unique
    private SearchFilterMode iaexp$filterMode = SearchFilterMode.ALL;

    // ====== IAlchemyTableScreenHandlerExt 实现 ======

    @Override
    @Unique
    public SearchFilterMode iaexp$getFilterMode() {
        return iaexp$filterMode;
    }

    @Override
    @Unique
    public void iaexp$setFilterMode(SearchFilterMode mode) {
        iaexp$filterMode = (mode == null) ? SearchFilterMode.ALL : mode;
    }

    @Override
    @Unique
    public SearchFilterMode iaexp$cycleFilterMode() {
        iaexp$filterMode = iaexp$filterMode.next();
        return iaexp$filterMode;
    }

    // ====== sortBySearch 重写 ======

    /**
     * 反射读取 {@code translations} 字段（midohra NbtCompound）。
     *
     * <p>不直接 {@code @Shadow} 是因为 Loom 在 1.1.3 target 上找不到该字段的 intermediary 映射，
     * 可能导致 mixin apply 失败。反射容忍字段缺失（返回 null，跳过翻译名搜索）。</p>
     */
    private NbtCompound iaexp$getTranslations() {
        try {
            Field f = AlchemyTableScreenHandler.class.getDeclaredField("translations");
            f.setAccessible(true);
            return (NbtCompound) f.get(this);
        } catch (Throwable t) {
            return null;
        }
    }

    @Inject(method = "sortBySearch", at = @At("HEAD"), cancellable = true)
    private void iaexp$sortBySearchVariant(CallbackInfo ci) {
        // 快速路径：空搜索 + ALL → 走原 placeExtractSlots()（显示全部）
        boolean emptySearch = (searchText == null || searchText.isEmpty());
        if (emptySearch && iaexp$filterMode == SearchFilterMode.ALL) {
            extractInventory.placeExtractSlots();
            ci.cancel();
            return;
        }

        List<String> ids;
        try {
            ids = new ArrayList<>(PlayerRegisteredItemUtil.getItemsAsString(player));
        } catch (Throwable t) {
            ci.cancel();
            return;
        }

        // 解析搜索上下文
        NbtCompound translations = iaexp$getTranslations();
        SearchMatcher.SearchContext ctx = SearchMatcher.parse(searchText, translations);

        // 分类收集匹配结果：直接物品在前，潜影盒在后
        List<String> directMatches = new ArrayList<>();
        List<String> shulkerMatches = new ArrayList<>();

        for (String raw : ids) {
            ItemVariantKey vk = ItemVariantKey.fromStorageString(raw);
            if (vk == null) continue;

            CompatIdentifier itemIdentifier;
            try {
                itemIdentifier = CompatIdentifier.of(vk.itemId);
                if (!ItemUtil.isExist(itemIdentifier)) continue;
            } catch (Throwable t) {
                continue;
            }

            Item vanillaItem = ItemUtil.fromId(itemIdentifier);
            if (vanillaItem == null) continue;

            if (ShulkerBoxSupport.isShulkerBox(vanillaItem)) {
                if (iaexp$filterMode == SearchFilterMode.DIRECT_ONLY) continue;
                if (matchesShulker(vk, ctx)) shulkerMatches.add(raw);
            } else {
                if (iaexp$filterMode == SearchFilterMode.SHULKER_ONLY) continue;
                if (matchesDirect(vk.itemId, itemIdentifier, ctx)) directMatches.add(raw);
            }
        }

        // 合并：直接物品在前，潜影盒在后
        List<String> sortedIds = new ArrayList<>(directMatches.size() + shulkerMatches.size());
        sortedIds.addAll(directMatches);
        sortedIds.addAll(shulkerMatches);

        ItemAlchemyExpansion.debug("[IAExp] sortBySearch: search='{}', filter={}, direct={}, shulker={}, total={}",
                searchText, iaexp$filterMode, directMatches.size(), shulkerMatches.size(), sortedIds.size());

        extractInventory.placeExtractSlots(sortedIds);
        ci.cancel();
    }

    /** 潜影盒匹配：空搜索时全部算匹配；否则检查自身名 + 内容物 */
    private static boolean matchesShulker(ItemVariantKey vk, SearchMatcher.SearchContext ctx) {
        if (ctx.isEmpty()) return true;
        ItemStack shulkerStack = IAExpServices.rebuildStack(vk);
        if (shulkerStack.isEmpty()) return false;
        return SearchMatcher.matchesShulkerBoxItself(shulkerStack, ctx)
                || !SearchMatcher.findShulkerMatchingContents(shulkerStack, ctx).isEmpty();
    }

    /** 直接物品匹配：空搜索时全部算匹配；否则按 id/翻译名/显示名匹配 */
    private static boolean matchesDirect(String itemId, CompatIdentifier itemIdentifier,
                                          SearchMatcher.SearchContext ctx) {
        if (ctx.isEmpty()) return true;
        try {
            ItemWrapper item = ItemWrapper.of(itemIdentifier);
            return SearchMatcher.matchesDirectItem(itemId, item, ctx);
        } catch (Throwable t) {
            return false;
        }
    }
}
