package itemalchemy.expansion.mixin;

import itemalchemy.expansion.nbt.ItemVariantKey;
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
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 重写 sortBySearch 以支持变体键。
 *
 * <p>原实现的两个问题：
 * <ol>
 *   <li>{@code CompatIdentifier.of(id)} 对变体键（含 \u0001）解析失败 → 搜索崩溃或漏结果。</li>
 *   <li>{@code id = id.toLowerCase()} 会破坏 NBT 指纹的大小写（如 AmmoId → ammoid），导致提取时 SNBT 解析失败。</li>
 * </ol>
 * 本 Mixin 在 HEAD cancel 并重写：对每个变体键提取 itemId 部分做搜索匹配，匹配则把**原变体键**
 * （保留 NBT 指纹大小写）加入结果列表。</p>
 */
@Mixin(value = AlchemyTableScreenHandler.class, priority = 500)
public abstract class MixinAlchemyTableScreenHandler {

    @Shadow public Player player;
    @Shadow public String searchText;
    @Shadow public String searchNamespace;
    @Shadow public ExtractInventory extractInventory;

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
        if (searchText == null || searchText.isEmpty()) {
            extractInventory.placeExtractSlots();
            ci.cancel();
            return;
        }

        List<String> ids;
        try {
            // 用 public API 获取 registeredItems（兼容 1.1.3/1.3.3 的 ModState 签名差异）
            ids = new ArrayList<>(PlayerRegisteredItemUtil.getItemsAsString(player));
        } catch (Throwable t) {
            ci.cancel();
            return;
        }

        List<String> sortedIds = new ArrayList<>();

        // 提取 @(NAMESPACE) 前缀
        String localSearch = searchText;
        String localNamespace = "";
        Pattern pattern = Pattern.compile("@([a-zA-Z0-9_-]+)");
        Matcher matcher = pattern.matcher(localSearch);
        if (matcher.find()) {
            localNamespace = matcher.group(1);
            localSearch = localSearch.replaceFirst("@" + localNamespace + " ?", "");
        }
        String searchLower = localSearch.toLowerCase();
        String nsFilterLower = localNamespace.toLowerCase();

        // 反射读取 translations 一次，循环外缓存（避免每次迭代都反射）
        NbtCompound translations = iaexp$getTranslations();

        for (String raw : ids) {
            ItemVariantKey vk = ItemVariantKey.fromStorageString(raw);
            if (vk == null) continue;
            String itemId = vk.itemId;

            CompatIdentifier itemIdentifier;
            try {
                itemIdentifier = CompatIdentifier.of(itemId);
            } catch (Throwable t) {
                continue;
            }
            if (!ItemUtil.isExist(itemIdentifier)) continue;

            ItemWrapper item = ItemWrapper.of(itemIdentifier);
            String itemTranslationKey = item.getTranslationKey();
            String translatedName = (translations != null && translations.has(itemTranslationKey))
                    ? translations.getString(itemTranslationKey) : "";

            String path = itemIdentifier.getPath();
            String ns = itemIdentifier.getNamespace();

            boolean nsOk = localNamespace.isEmpty() || ns.toLowerCase().contains(nsFilterLower);
            boolean textOk = path.toLowerCase().contains(searchLower)
                    || translatedName.toLowerCase().contains(searchLower)
                    || item.getName().toLowerCase().contains(searchLower);

            if (nsOk && textOk) {
                // 保留原变体键（含 NBT 指纹，不小写化）
                sortedIds.add(raw);
            }
        }

        extractInventory.placeExtractSlots(sortedIds);
        ci.cancel();
    }
}
