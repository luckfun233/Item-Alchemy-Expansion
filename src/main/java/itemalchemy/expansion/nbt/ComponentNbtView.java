package itemalchemy.expansion.nbt;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.potion.Potion;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.pitan76.mcpitanlib.api.util.CustomDataUtil;

import java.util.Optional;

/**
 * 1.20.1 NBT 与 1.21.1 Data Components 之间的桥接层。
 *
 * <p>1.20.1 的 {@code stack.getNbt()} 返回包含所有自定义数据的 NbtCompound；
 * 1.21.1 中数据被拆分到多个 data component。本类把影响物品身份的关键组件
 * 收集为一个 NbtCompound（模拟 1.20.1 的 NBT 结构），供指纹生成与变体键使用。</p>
 *
 * <h3>收集的组件</h3>
 * <ul>
 *   <li>{@code CUSTOM_DATA}：模组自定义数据（如 TACZ 的 AmmoId），直接合并到根</li>
 *   <li>{@code CUSTOM_NAME}：自定义名称 → {@code display.Name}（Text 序列化为 JSON）</li>
 *   <li>{@code DAMAGE}：耐久损耗 → {@code Damage}</li>
 *   <li>{@code REPAIR_COST}：修复花费 → {@code RepairCost}</li>
 *   <li>{@code BLOCK_ENTITY_DATA}：方块实体数据 → {@code BlockEntityTag}</li>
 *   <li>{@code POTION_CONTENTS}：药水内容 → {@code Potion}（药水 id 字符串）</li>
 * </ul>
 *
 * <p>未收集的组件（如 ENCHANTMENTS、CUSTOM_MODEL_DATA、LORE）不参与指纹，
 * 这些不影响 TACZ 子弹区分、潜影盒内容物区分与原版药水区分的核心需求。</p>
 */
public final class ComponentNbtView {

    private ComponentNbtView() {}

    /**
     * 把物品的关键 data component 收集为 NbtCompound（模拟 1.20.1 NBT 结构）。
     *
     * <p>返回的 NbtCompound 可能为空（无任何相关组件），表示该物品无需 NBT 区分。
     * 该方法不修改物品堆本身。</p>
     *
     * @param stack 物品堆
     * @return 包含关键组件数据的 NbtCompound（非 null，可能为空）
     */
    public static NbtCompound collectEffectiveNbt(ItemStack stack) {
        NbtCompound result = new NbtCompound();

        // 1. CUSTOM_DATA 组件：模组自定义数据（TACZ AmmoId 等），直接合并到根
        NbtCompound customData = CustomDataUtil.getNbt(stack);
        if (customData != null && !customData.isEmpty()) {
            for (String key : customData.getKeys()) {
                result.put(key, customData.get(key));
            }
        }

        // 2. CUSTOM_NAME → display.Name（Text 序列化为 JSON 字符串）
        Text customName = stack.get(DataComponentTypes.CUSTOM_NAME);
        if (customName != null) {
            NbtCompound display = result.contains("display", NbtElement.COMPOUND_TYPE)
                    ? result.getCompound("display") : new NbtCompound();
            String nameJson = Text.Serialization.toJsonString(customName);
            display.put("Name", NbtString.of(nameJson));
            result.put("display", display);
        }

        // 3. DAMAGE → Damage（耐久损耗，0 不收集避免空指纹）
        Integer damage = stack.get(DataComponentTypes.DAMAGE);
        if (damage != null && damage > 0) {
            result.putInt("Damage", damage);
        }

        // 4. REPAIR_COST → RepairCost
        Integer repairCost = stack.get(DataComponentTypes.REPAIR_COST);
        if (repairCost != null && repairCost > 0) {
            result.putInt("RepairCost", repairCost);
        }

        // 5. BLOCK_ENTITY_DATA → BlockEntityTag（潜影盒锁、自定义名等方块实体数据）
        NbtComponent blockEntityData = stack.get(DataComponentTypes.BLOCK_ENTITY_DATA);
        if (blockEntityData != null) {
            NbtCompound beNbt = blockEntityData.copyNbt();
            if (!beNbt.isEmpty()) {
                result.put("BlockEntityTag", beNbt);
            }
        }

        // 6. POTION_CONTENTS → Potion（药水 id 字符串）
        PotionContentsComponent potionContents = stack.get(DataComponentTypes.POTION_CONTENTS);
        if (potionContents != null) {
            Optional<RegistryEntry<Potion>> potionOpt = potionContents.potion();
            if (potionOpt.isPresent()) {
                Optional<Identifier> idOpt = potionOpt.get().getKey().map(registryKey -> registryKey.getValue());
                if (idOpt.isPresent()) {
                    result.putString("Potion", idOpt.get().toString());
                }
            }
        }

        return result;
    }

    /**
     * 把指纹 NbtCompound 中的数据写回物品堆的对应 data component。
     *
     * <p>用于从变体键重建带完整数据的 ItemStack。指纹中的 key 按以下规则分发：
     * <ul>
     *   <li>{@code BlockEntityTag} → {@code BLOCK_ENTITY_DATA} 组件</li>
     *   <li>{@code display.Name} → {@code CUSTOM_NAME} 组件</li>
     *   <li>{@code Damage} → {@code DAMAGE} 组件</li>
     *   <li>{@code RepairCost} → {@code REPAIR_COST} 组件</li>
     *   <li>{@code Potion} → {@code POTION_CONTENTS} 组件</li>
     *   <li>其余 key → {@code CUSTOM_DATA} 组件（模组自定义数据）</li>
     * </ul></p>
     *
     * @param stack 目标物品堆（会被修改）
     * @param nbt   指纹 NbtCompound（来自 {@link #collectEffectiveNbt} 的等价结构）
     */
    public static void applyEffectiveNbt(ItemStack stack, NbtCompound nbt) {
        if (nbt == null || nbt.isEmpty()) return;

        // 分离出各组件专属的 key，剩余的放入 CUSTOM_DATA
        NbtCompound customData = new NbtCompound();

        for (String key : nbt.getKeys()) {
            switch (key) {
                case "BlockEntityTag":
                    NbtCompound beNbt = nbt.getCompound(key);
                    if (!beNbt.isEmpty()) {
                        stack.set(DataComponentTypes.BLOCK_ENTITY_DATA, NbtComponent.of(beNbt));
                    }
                    break;
                case "display":
                    NbtCompound display = nbt.getCompound(key);
                    if (display.contains("Name", NbtElement.STRING_TYPE)) {
                        Text name = Text.Serialization.fromJson(display.getString("Name"));
                        if (name != null) {
                            stack.set(DataComponentTypes.CUSTOM_NAME, name);
                        }
                    }
                    break;
                case "Damage":
                    stack.set(DataComponentTypes.DAMAGE, nbt.getInt(key));
                    break;
                case "RepairCost":
                    stack.set(DataComponentTypes.REPAIR_COST, nbt.getInt(key));
                    break;
                case "Potion":
                    String potionId = nbt.getString(key);
                    Potion potion = Registries.POTION.get(Identifier.tryParse(potionId));
                    if (potion != null) {
                        stack.set(DataComponentTypes.POTION_CONTENTS,
                                new PotionContentsComponent(Optional.of(potion.getRegistryEntry()),
                                        Optional.empty(), java.util.Collections.emptyList()));
                    }
                    break;
                default:
                    // 其余 key 视为模组自定义数据
                    customData.put(key, nbt.get(key));
                    break;
            }
        }

        if (!customData.isEmpty()) {
            CustomDataUtil.setNbt(stack, customData);
        }
    }

    /**
     * 物品是否有任何影响身份的 data component（用于判断是否需要生成指纹）。
     */
    public static boolean hasEffectiveNbt(ItemStack stack) {
        return CustomDataUtil.hasNbt(stack)
                || stack.contains(DataComponentTypes.CUSTOM_NAME)
                || stack.contains(DataComponentTypes.BLOCK_ENTITY_DATA)
                || stack.contains(DataComponentTypes.POTION_CONTENTS)
                || (stack.get(DataComponentTypes.DAMAGE) != null && stack.get(DataComponentTypes.DAMAGE) > 0)
                || (stack.get(DataComponentTypes.REPAIR_COST) != null && stack.get(DataComponentTypes.REPAIR_COST) > 0);
    }

    /** 清空所有用于指纹的 data component（用于重建堆前清空旧数据） */
    public static void clearEffectiveNbt(ItemStack stack) {
        stack.remove(DataComponentTypes.CUSTOM_DATA);
        stack.remove(DataComponentTypes.CUSTOM_NAME);
        stack.remove(DataComponentTypes.DAMAGE);
        stack.remove(DataComponentTypes.REPAIR_COST);
        stack.remove(DataComponentTypes.BLOCK_ENTITY_DATA);
        stack.remove(DataComponentTypes.POTION_CONTENTS);
    }
}
