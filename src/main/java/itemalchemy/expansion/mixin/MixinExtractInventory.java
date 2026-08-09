package itemalchemy.expansion.mixin;

import itemalchemy.expansion.IAExpServices;
import itemalchemy.expansion.ItemAlchemyExpansion;
import itemalchemy.expansion.nbt.ItemVariantKey;
import net.minecraft.item.ItemStack;
import net.pitan76.itemalchemy.EMCManager;
import net.pitan76.itemalchemy.gui.inventory.ExtractInventory;
import net.pitan76.itemalchemy.gui.screen.AlchemyTableScreenHandler;
import net.pitan76.mcpitanlib.api.entity.Player;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;

/**
 * 让提取槽按变体键重建带组件的展示堆。
 *
 * <p>原 {@code placeExtractSlots(List)} 用 {@code CompatIdentifier.of(keys.get(i))} 解析纯物品 ID，
 * 对变体键（含 \u0001 + NBT 指纹）解析失败，导致同 ID 不同 NBT 物品坍缩或被跳过。
 * 本 Mixin 在 HEAD 处 cancel 并重写：把每个 key 解析为 {@link ItemVariantKey}，
 * 用 {@link IAExpServices#rebuildStack(ItemVariantKey)} 重建带组件的 ItemStack。
 * 重建后的堆存入 {@code definedStacks}，{@code AlchemyTableScreenHandler#onSlotClick} 会用其 copy 购买，
 * 自动携带组件；{@code EMCManager.get(stack)} 仍按物品 ID 计价。</p>
 *
 * <p><b>shadow 类型</b>：{@code ExtractInventory} 的 {@code setStack} 与 {@code definedStacks}
 * 用的是原版 {@code net.minecraft.item.ItemStack}（见该类 import）。
 * 必须与目标一致，否则 Mixin apply 失败（运行时报 @Shadow not located）。</p>
 */
@Mixin(value = ExtractInventory.class, priority = 500)
public abstract class MixinExtractInventory {

    @Shadow public Player player;
    @Shadow @Nullable public AlchemyTableScreenHandler screenHandler;
    @Shadow public Map<Integer, ItemStack> definedStacks;
    @Shadow public boolean isSettingStack;

    @Shadow
    public abstract void setStack(int slot, ItemStack stack);

    @Inject(method = "placeExtractSlots(Ljava/util/List;)V", at = @At("HEAD"), cancellable = true)
    private void iaexp$placeExtractSlotsVariant(List<String> keys, CallbackInfo ci) {
        ItemAlchemyExpansion.debug("[IAExp] placeExtractSlots called, keys={}", keys);
        if (player == null || !player.isServerPlayerEntity()) {
            ci.cancel();
            return;
        }
        try {
            EMCManager.syncS2C(player);
        } catch (Throwable ignored) {
            // 防御性：sync 失败不阻断展示
        }

        isSettingStack = true;
        definedStacks.clear();

        int index = (screenHandler != null) ? screenHandler.index : 0;
        int notExists = 0;

        for (int i = 0; i < 13; i++) {
            int idIndex = i + (index * 13) + notExists;
            if (keys.size() < idIndex + 1) {
                setStack(i + 64, ItemStack.EMPTY);
                continue;
            }
            String raw = keys.get(idIndex);
            ItemVariantKey vk = ItemVariantKey.fromStorageString(raw);
            if (vk == null) {
                i--;
                notExists++;
                continue;
            }
            ItemStack stack = IAExpServices.rebuildStack(vk);
            if (stack.isEmpty()) {
                i--;
                notExists++;
                continue;
            }
            setStack(i + 64, stack);
        }
        isSettingStack = false;
        ItemAlchemyExpansion.debug("[IAExp] placeExtractSlots done, definedStacks.size={}", definedStacks.size());
        ci.cancel();
    }
}
