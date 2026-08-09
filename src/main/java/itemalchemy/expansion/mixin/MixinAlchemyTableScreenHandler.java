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
 * 重写 {@code sortBySearch}：支持变体键、潜影盒内容物搜索、权重排序与筛选模式。
 *
 * <p>原实现对变体键（含 \u0001）解析失败，且 {@code id.toLowerCase()} 破坏 NBT 指纹大小写。
 * 本 Mixin 在 HEAD cancel 重写，保留原变体键串，按搜索上下文分类匹配：
 * 直接物品在前，潜影盒（自身或内容物匹配）在后。筛选模式通过 {@link IAlchemyTableScreenHandlerExt}
 * 暴露的 @Unique 字段在客户端与服务端各持一份，客户端切换时同步网络包。</p>
 */
@Mixin(value = AlchemyTableScreenHandler.class, priority = 500)
public abstract class MixinAlchemyTableScreenHandler implements IAlchemyTableScreenHandlerExt {

    @Shadow public Player player;
    @Shadow public String searchText;
    @Shadow public String searchNamespace;
    @Shadow public ExtractInventory extractInventory;

    /** 筛选模式状态（通过接口暴露） */
    @Unique
    private SearchFilterMode iaexp$filterMode = SearchFilterMode.ALL;

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

    /**
     * 反射读取 {@code translations} 字段（midohra NbtCompound）。
     * 不直接 @Shadow 是因为 Loom 在模组类目标上可能找不到该字段的 intermediary 映射；
     * 反射容忍字段缺失（返回 null，跳过翻译名搜索）。
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
        // 快速路径：空搜索 + ALL → 走原 placeExtractSlots() 显示全部
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

        NbtCompound translations = iaexp$getTranslations();
        SearchMatcher.SearchContext ctx = SearchMatcher.parse(searchText, translations);

        // 分类收集：直接物品在前，潜影盒在后
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

        List<String> sortedIds = new ArrayList<>(directMatches.size() + shulkerMatches.size());
        sortedIds.addAll(directMatches);
        sortedIds.addAll(shulkerMatches);

        ItemAlchemyExpansion.debug("[IAExp] sortBySearch: search='{}', filter={}, direct={}, shulker={}, total={}",
                searchText, iaexp$filterMode, directMatches.size(), shulkerMatches.size(), sortedIds.size());

        extractInventory.placeExtractSlots(sortedIds);
        ci.cancel();
    }

    /** 潜影盒匹配：空搜索时全部算匹配；否则检查自身名与内容物 */
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
