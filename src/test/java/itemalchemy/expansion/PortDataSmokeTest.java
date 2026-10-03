package itemalchemy.expansion;

import itemalchemy.expansion.compat.port.StackData;
import itemalchemy.expansion.config.IAExpConfig;
import itemalchemy.expansion.nbt.*;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.alchemy.*;
import java.util.*;

/** Runs against real 26.2 registries without opening the user's worlds. */
public final class PortDataSmokeTest {
    private static int assertions;
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        StackData.serverLookup(net.minecraft.data.registries.VanillaRegistries.createLookup());
        net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(StackData.lookup())
                .forEach(net.minecraft.core.component.DataComponentInitializers.PendingComponents::apply);
        var config = new IAExpConfig();
        var fingerprinter = new NbtFingerprinter(config);
        check(StackData.fromNbt(StackData.writeNbt(ItemStack.EMPTY, new CompoundTag())).isEmpty(), "empty inventory slot roundtrip");
        ItemStack potion = new ItemStack(Items.POTION);
        potion.set(DataComponents.POTION_CONTENTS, new PotionContents(Potions.HEALING));
        ItemStack rebuilt = new ItemStack(Items.POTION);
        StackData.setNbt(rebuilt, StackData.getNbt(potion));
        check(ItemStack.isSameItemSameComponents(potion, rebuilt), "potion component roundtrip");
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        String clean = ItemVariantKey.fromStack(sword, fingerprinter).toStorageString();
        sword.setDamageValue(87);
        sword.set(DataComponents.REPAIR_COST, 12);
        check(clean.equals(ItemVariantKey.fromStack(sword, fingerprinter).toStorageString()), "ignore damage and repair cost");
        config.fullIgnoreDamageAndRepairCost = false;
        check(!clean.equals(ItemVariantKey.fromStack(sword, fingerprinter).toStorageString()), "respect damage when configured");
        List<ItemStack> contents = new ArrayList<>(Collections.nCopies(27, ItemStack.EMPTY));
        contents.set(7, potion.copyWithCount(1));
        contents.set(26, new ItemStack(Items.DIAMOND, 32));
        ItemStack box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(contents));
        ItemStack restoredBox = StackData.fromNbt(StackData.writeNbt(box, new CompoundTag()));
        check(ItemStack.isSameItemSameComponents(box, restoredBox), "shulker serialization");
        ItemStack[] restoredContents = ShulkerBoxSupport.getContents(restoredBox);
        check(restoredContents[0].isEmpty() && restoredContents[7].getItem() == Items.POTION && restoredContents[26].getCount() == 32, "shulker slot positions preserved");
        // 回归：潜影盒 EMC 求和不得因极端值溢出抛异常（set_emc 可写入接近 Long.MAX_VALUE 的值）
        check(ShulkerBoxSupport.saturatedMultiply(Long.MAX_VALUE - 10, 32) == Long.MAX_VALUE, "extreme emc multiply saturates");
        check(ShulkerBoxSupport.saturatedAdd(Long.MAX_VALUE - 1, 10) == Long.MAX_VALUE, "extreme emc add saturates");
        check(ShulkerBoxSupport.saturatedMultiply(100, 32) == 3200 && ShulkerBoxSupport.saturatedAdd(3200, 100) == 3300, "normal-range emc math unchanged");
        CompoundTag a = new CompoundTag(), b = new CompoundTag();
        CompoundTag nestedA = new CompoundTag(), nestedB = new CompoundTag();
        nestedA.putInt("z", 1); nestedA.putInt("a", 2);
        nestedB.putInt("a", 2); nestedB.putInt("z", 1);
        a.put("nested", nestedA); b.put("nested", nestedB);
        String fingerprint = fingerprinter.fingerprint(potion, a);
        check(fingerprint.equals(fingerprinter.fingerprint(potion, b)), "canonical nested ordering");
        check(a.equals(NbtFingerprinter.parseFingerprint(fingerprint)), "fingerprint parse roundtrip");
        ItemStack dataItem = new ItemStack(Items.PAPER);
        StackData.getOrCreateNbt(dataItem).putLong("storedEmc", 123456789L);
        check(StackData.getNbt(dataItem).getLongOr("storedEmc", 0) == 123456789L, "mutable custom data writes through");
        check(StackData.getNbt(StackData.fromNbt(StackData.writeNbt(dataItem, new CompoundTag()))).getLongOr("storedEmc", 0) == 123456789L, "custom data persistence");
        // 权限门禁决策表：单人放行 / 关闭校验放行 / 服务器无 OP 拒绝 / 服务器有 OP 放行
        var noPerm = net.minecraft.server.permissions.PermissionSet.NO_PERMISSIONS;
        var allPerm = net.minecraft.server.permissions.PermissionSet.ALL_PERMISSIONS;
        check(IAExpPermissions.allowed(true, true, noPerm), "singleplayer bypasses op requirement");
        check(IAExpPermissions.allowed(false, false, noPerm), "disabled op requirement allows everyone");
        check(!IAExpPermissions.allowed(false, true, noPerm), "server without op is denied");
        check(IAExpPermissions.allowed(false, true, allPerm), "server with op is allowed");
        check(!IAExpPermissions.allowed(false, true, null), "missing permission set is denied");
        System.out.println("PORT_DATA_SMOKE_PASS assertions=" + assertions);
    }
}
