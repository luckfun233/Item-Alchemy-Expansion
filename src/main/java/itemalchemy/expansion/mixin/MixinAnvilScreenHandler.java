package itemalchemy.expansion.mixin;

import itemalchemy.expansion.ItemAlchemyExpansion;
import itemalchemy.expansion.item.EmcCardItem;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.AnvilScreenHandler;
import net.minecraft.screen.Property;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;

/**
 * 铁砧合并 EMC 卡：左右输入槽各放一张卡时，结果槽显示合并后的卡。
 *
 * <p>updateResult 声明于父类 ForgingScreenHandler（抽象），AnvilScreenHandler override
 * 有实现体，故目标类是 AnvilScreenHandler。1.20.1 无 refmap，原版方法名写 intermediary
 * 名 {@code method_24928}（loom 构建时会警告 Cannot remap，保留原样即为所需）。
 * 槽位通过 {@code getSlot}（字节码调用，loom 会重映射）；
 * {@code levelCost}（{@code field_7770}）用反射访问。</p>
 *
 * <p>合并规则：{@code target}（左槽）保留，{@code source}（右槽）的 base_emc + stored_emc
 * 转入 {@code target}。取走结果时原版 {@code onTakeOutput} 会清空左右槽，无需额外处理。</p>
 */
@Mixin(value = AnvilScreenHandler.class)
public abstract class MixinAnvilScreenHandler {

    /** {@link AnvilScreenHandler#levelCost} 的 intermediary 字段名 */
    @Unique
    private static final String LEVEL_COST_FIELD = "field_7770";

    @Unique
    private static Field levelCostField;
    @Unique
    private static boolean levelCostFieldResolved = false;

    @Unique
    private static final int LEFT_SLOT = 0;
    @Unique
    private static final int RIGHT_SLOT = 1;
    @Unique
    private static final int OUTPUT_SLOT = 2;

    @Inject(method = "method_24928", at = @At("HEAD"), cancellable = true)
    private void iaexp$mergeCardsInAnvil(CallbackInfo ci) {
        try {
            AnvilScreenHandler self = (AnvilScreenHandler) (Object) this;
            ItemStack left = self.getSlot(LEFT_SLOT).getStack();
            ItemStack right = self.getSlot(RIGHT_SLOT).getStack();
            if (left.getCount() != 1 || right.getCount() != 1) return;
            if (!(left.getItem() instanceof EmcCardItem) || !(right.getItem() instanceof EmcCardItem)) return;

            ItemStack merged = EmcCardItem.mergeCards(left, right);
            self.getSlot(OUTPUT_SLOT).setStack(merged);
            setLevelCost(self, 0);
            self.sendContentUpdates();
            ci.cancel();
        } catch (Throwable t) {
            ItemAlchemyExpansion.LOGGER.warn("[IAExp] anvil merge failed: {}", t.toString());
        }
    }

    /** 反射把 levelCost 清零（避免合并时收取经验）。字段查找失败时静默跳过。 */
    @Unique
    private static void setLevelCost(AnvilScreenHandler self, int value) {
        Field f = resolveLevelCostField();
        if (f == null) return;
        try {
            Object prop = f.get(self);
            if (prop instanceof Property) {
                ((Property) prop).set(value);
            }
        } catch (Throwable t) {
            // ignore
        }
    }

    @Unique
    private static Field resolveLevelCostField() {
        if (levelCostFieldResolved) return levelCostField;
        try {
            Field f = AnvilScreenHandler.class.getDeclaredField(LEVEL_COST_FIELD);
            f.setAccessible(true);
            levelCostField = f;
        } catch (Throwable t) {
            levelCostField = null;
        }
        levelCostFieldResolved = true;
        return levelCostField;
    }
}