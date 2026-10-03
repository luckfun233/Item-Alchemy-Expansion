package itemalchemy.expansion.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.world.item.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.*;
import net.minecraft.resources.*;
import itemalchemy.expansion.block.*;
import itemalchemy.expansion.client.*;
import itemalchemy.expansion.item.*;
import itemalchemy.expansion.network.*;
import itemalchemy.expansion.IAExpServices;
import itemalchemy.expansion.config.IAExpConfigHolder;
import net.pitan76.itemalchemy.EMCManager;
import net.pitan76.itemalchemy.api.PlayerRegisteredItemUtil;
import net.pitan76.itemalchemy.gui.inventory.RegisterInventory;
import net.pitan76.itemalchemy.gui.screen.AlchemyTableScreenHandler;
import net.pitan76.itemalchemy.client.screen.AlchemyTableScreen;
import net.pitan76.mcpitanlib.api.gui.args.CreateMenuEvent;

public final class PortClientGameTest implements FabricClientGameTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        System.out.println("PORT_GAME_CHECK " + message);
    }
    @Override public void runTest(ClientGameTestContext context) {
        String source = itemalchemy.expansion.ItemAlchemyExpansion.class.getProtectionDomain().getCodeSource().getLocation().toString();
        System.out.println("PORT_MOD_CODE_SOURCE " + source);
        if (Boolean.getBoolean("iaexp.test.packaged")) check(source.endsWith(".jar"), "running packaged mod JAR");
        try (var world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksDownload();
            world.getServer().runOnServer(server -> {
                for (String name : new String[]{"emc_card", "card_forge", "emc_converter", "emc_emitter"}) {
                    var id = Identifier.fromNamespaceAndPath("itemalchemy-expansion", name);
                    check(server.getRecipeManager().byKey(ResourceKey.create(Registries.RECIPE, id)).isPresent(), "recipe " + name);
                }
                var player = world.getConnection().getServerPlayer();
                var card = new ItemStack(IAExpItems.EMC_CARD);
                EmcCardItem.setStoredEmc(card, 12345L);
                check(EmcCardItem.getStoredEmc(card) == 12345L, "card EMC write/read");
                player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, card);
                player.inventoryMenu.broadcastChanges();
            });
            world.getConnection().waitForClientboundPackets();
            world.getServer().runOnServer(server -> {
                var player = world.getConnection().getServerPlayer();
                check(PlayerEmcUtil.ensureTeam(server, player.getUUID(), player.getGameProfile().name()), "player team available");
                check(PlayerEmcUtil.add(server, player.getUUID(), 1000L), "seed player EMC");
                EmcCardNetwork.sendOpenGui(player);
            });
            context.waitForScreen(EmcCardMainScreen.class);
            context.runOnClient(client -> EmcCardClientNetwork.sendDeposit(100L));
            world.getServer().waitFor(server -> EmcCardItem.getStoredEmc(world.getConnection().getServerPlayer().getMainHandItem()) == 12445L);
            world.getServer().runOnServer(server -> check(PlayerEmcUtil.getEmc(server, world.getConnection().getServerPlayer().getUUID()) == 900L, "deposit debits team EMC"));
            context.runOnClient(client -> EmcCardClientNetwork.sendWithdraw(50L));
            world.getServer().waitFor(server -> EmcCardItem.getStoredEmc(world.getConnection().getServerPlayer().getMainHandItem()) == 12395L);
            world.getServer().runOnServer(server -> check(PlayerEmcUtil.getEmc(server, world.getConnection().getServerPlayer().getUUID()) == 950L, "withdraw credits team EMC"));
            context.setScreen(EmcCardMainScreen::new);
            context.waitTicks(8);
            context.takeScreenshot("port-card-main");
            context.setScreen(EmcCardDepositScreen::new);
            context.waitTicks(4);
            context.setScreen(EmcCardWithdrawScreen::new);
            context.waitTicks(4);
            context.setScreen(EmcCardConfigScreen::new);
            context.waitTicks(4);
            // 26.2：文本输入（含输入法）只在文本框获焦时开启，配置界面金额框必须进入即获焦
            boolean configFieldFocused = context.computeOnClient(client -> {
                var focused = client.gui.screen().getFocused();
                return focused instanceof net.minecraft.client.gui.components.EditBox;
            });
            System.out.println("PORT_INPUT_DIAG configFieldAutoFocused=" + configFieldFocused);
            check(configFieldFocused, "emc card config amount field is focused on open");
            context.setScreen(EmcCardLogScreen::new);
            context.waitTicks(4);
            context.setScreen(() -> new SetEmcScreen(new ItemStack(Items.DIAMOND)));
            context.waitTicks(8);
            context.takeScreenshot("port-emc-editor");
            context.setScreen(() -> null);
            world.getServer().runOnServer(server -> {
                var level = server.overworld();
                var pos = world.getConnection().getServerPlayer().blockPosition().offset(1, 0, 0);
                level.setBlockAndUpdate(pos, CardForgeBlocks.FORGE.defaultBlockState());
                check(level.getBlockEntity(pos) instanceof CardForgeBlockEntity, "forge block entity");
                world.getConnection().getServerPlayer().openMenu((CardForgeBlockEntity) level.getBlockEntity(pos));
            });
            context.waitForScreen(CardForgeScreen.class);
            context.waitTicks(8);
            context.takeScreenshot("port-card-forge");
            context.runOnClient(client -> client.player.closeContainer());
            world.getConnection().waitForServerboundPackets();
            world.getServer().runOnServer(server -> {
                var player = world.getConnection().getServerPlayer();
                var wrapped = new net.pitan76.mcpitanlib.api.entity.Player(player);
                ItemStack named = new ItemStack(Items.DIAMOND);
                named.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Port variant"));
                String key = IAExpServices.variantKeyOf(named).toStorageString();
                var inventory = new RegisterInventory(128, wrapped);
                ((net.minecraft.world.Container) inventory).setItem(0, named);
                check(PlayerRegisteredItemUtil.getItemsAsString(wrapped).contains(key), "conversion table learns component variant");
                check(ItemStack.isSameItemSameComponents(named, IAExpServices.rebuildStack(itemalchemy.expansion.nbt.ItemVariantKey.fromStorageString(key))), "learned variant reconstructed");
                ItemStack shulker = new ItemStack(Items.SHULKER_BOX);
                shulker.set(net.minecraft.core.component.DataComponents.CONTAINER,
                        net.minecraft.world.item.component.ItemContainerContents.fromItems(java.util.List.of(new ItemStack(Items.DIAMOND, 3))));
                ((net.minecraft.world.Container) inventory).setItem(1, shulker);
                String shulkerKey = IAExpServices.variantKeyOf(shulker).toStorageString();
                check(PlayerRegisteredItemUtil.getItemsAsString(wrapped).contains(shulkerKey), "conversion table learns filled shulker");
                player.openMenu(new net.minecraft.world.SimpleMenuProvider(
                        (id, inv, p) -> new AlchemyTableScreenHandler(new CreateMenuEvent(id, inv, p)),
                        net.minecraft.network.chat.Component.literal("Port Alchemy Table")));
            });
            context.waitForScreen(AlchemyTableScreen.class);
            context.waitTicks(8);
            context.takeScreenshot("port-alchemy-table");
            // 槽位坐标是 GUI 缩放空间，而 setCursorPos/MouseHandler.xpos() 用窗口像素空间，需乘缩放系数。
            double[] hoverPixels = context.computeOnClient(client -> {
                var screen = (AlchemyTableScreen) client.gui.screen();
                var slot = screen.getMenu().slots.stream().filter(s -> s.getItem().is(Items.SHULKER_BOX)).findFirst().orElseThrow();
                try {
                    var left = net.minecraft.client.gui.screens.inventory.AbstractContainerScreen.class.getDeclaredField("leftPos");
                    left.setAccessible(true);
                    var top = net.minecraft.client.gui.screens.inventory.AbstractContainerScreen.class.getDeclaredField("topPos");
                    top.setAccessible(true);
                    double scale = client.getWindow().getGuiScale();
                    return new double[]{(left.getInt(screen) + slot.x + 8) * scale, (top.getInt(screen) + slot.y + 8) * scale};
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException(e);
                }
            });
            // MouseHandler 会丢弃首个移动事件（ignoreFirstMove），因此抖动几下直到命中槽位。
            boolean resolved = false;
            for (int attempt = 0; attempt < 8 && !resolved; attempt++) {
                context.getInput().setCursorPos(hoverPixels[0] + (attempt % 2), hoverPixels[1] + (attempt % 2));
                context.waitTicks(3);
                resolved = context.computeOnClient(client -> {
                    var screen = (AlchemyTableScreen) client.gui.screen();
                    double scale = client.getWindow().getGuiScale();
                    var candidate = itemalchemy.expansion.client.util.GuiRenderUtil.getHoveredSlot(
                            screen, client.mouseHandler.xpos() / scale, client.mouseHandler.ypos() / scale);
                    return candidate != null && candidate.getItem().is(Items.SHULKER_BOX);
                });
            }
            if (!resolved) {
                context.runOnClient(client -> {
                    System.out.println("PORT_HOVER_DIAG scale=" + client.getWindow().getGuiScale()
                            + " mouse=" + client.mouseHandler.xpos() + "," + client.mouseHandler.ypos()
                            + " target=" + hoverPixels[0] + "," + hoverPixels[1]
                            + " screen=" + client.gui.screen());
                });
            }
            check(resolved, "hovered shulker slot resolution");
            context.runOnClient(client -> check(
                    itemalchemy.expansion.client.AlchemyTableScreenShulkerPreview.diagnosticInvocationCount() > 0,
                    "alchemy table render hook fired"));
            // 红框标记需要非空搜索上下文（客户端渲染直接读 handler.searchText）
            context.runOnClient(client -> {
                var screen = (AlchemyTableScreen) client.gui.screen();
                screen.searchBox.setValue("diamond");
                ((AlchemyTableScreenHandler) screen.getMenu()).searchText = "diamond";
                screen.searchBox.setFocused(false);
            });
            context.waitTicks(2);
            context.getInput().holdShift();
            boolean previewActive = false;
            for (int attempt = 0; attempt < 6 && !previewActive; attempt++) {
                context.waitTicks(2);
                previewActive = context.computeOnClient(client ->
                        itemalchemy.expansion.client.AlchemyTableScreenShulkerPreview.isPreviewActive());
            }
            check(previewActive, "shulker preview engaged");
            // 焦点移开 (0,0)（钻石所在格）：焦点格优先画白框、不画红框
            context.getInput().holdKey(org.lwjgl.glfw.GLFW.GLFW_KEY_D);
            context.waitTicks(14);
            context.getInput().releaseKey(org.lwjgl.glfw.GLFW.GLFW_KEY_D);
            context.takeScreenshot("port-shulker-preview");
            context.getInput().releaseShift();

            // ===== 转换桌搜索框输入回归 =====
            double[] searchPixels = context.computeOnClient(client -> {
                var screen = (AlchemyTableScreen) client.gui.screen();
                double scale = client.getWindow().getGuiScale();
                var box = screen.searchBox;
                return new double[]{(box.getX() + box.getWidth() / 2.0) * scale,
                        (box.getY() + box.getHeight() / 2.0) * scale};
            });
            context.runOnClient(client ->
                    ((AlchemyTableScreen) client.gui.screen()).searchBox.setValue(""));
            // MouseHandler 会丢弃首个移动事件，抖动几下直到点击落到搜索框上
            for (int attempt = 0; attempt < 4; attempt++) {
                context.getInput().setCursorPos(searchPixels[0] + (attempt % 2), searchPixels[1] + (attempt % 2));
                context.waitTicks(2);
            }
            context.getInput().pressMouse(0);
            context.getInput().releaseMouse(0);
            context.waitTicks(3);
            boolean searchFocused = context.computeOnClient(client ->
                    ((AlchemyTableScreen) client.gui.screen()).searchBox.isFocused());
            context.getInput().typeChars("diamond");
            context.waitTicks(5);
            String typedValue = context.computeOnClient(client ->
                    ((AlchemyTableScreen) client.gui.screen()).searchBox.getValue());
            String directChain = context.computeOnClient(client -> {
                var screen = (AlchemyTableScreen) client.gui.screen();
                screen.searchBox.setFocused(true);
                boolean handled = screen.charTyped(new net.minecraft.client.input.CharacterEvent('x'));
                return "handled=" + handled + ",value=" + screen.searchBox.getValue();
            });
            System.out.println("PORT_SEARCH_DIAG clickedFocused=" + searchFocused
                    + ",typedValue='" + typedValue + "',directCharTyped{" + directChain + "}");
            // 26.2 的输入法路径：preedit（候选/组字）必须能分发到焦点控件
            boolean preeditHandled = context.computeOnClient(client -> {
                var screen = (AlchemyTableScreen) client.gui.screen();
                screen.searchBox.setFocused(true);
                return screen.preeditUpdated(new net.minecraft.client.input.PreeditEvent(
                        "ni", 2, java.util.List.of("ni"), 0));
            });
            System.out.println("PORT_SEARCH_DIAG preeditHandled=" + preeditHandled);
            check(preeditHandled, "alchemy search box accepts IME preedit events");
            check("diamond".equals(typedValue), "alchemy search box receives typed characters");

            // 真实按键走一遍上游 keyReleased → setSearchText → sortBySearch 链路
            context.getInput().pressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_D);
            context.getInput().releaseKey(org.lwjgl.glfw.GLFW.GLFW_KEY_D);
            context.waitTicks(4);
            String[] searchState = context.computeOnClient(client -> {
                var screen = (AlchemyTableScreen) client.gui.screen();
                var handler = (AlchemyTableScreenHandler) screen.getMenu();
                return new String[]{screen.searchBox.getValue(), handler.searchText,
                        String.valueOf(handler.extractInventory != null
                                && handler.extractInventory.definedStacks != null
                                && !handler.extractInventory.definedStacks.isEmpty())};
            });
            System.out.println("PORT_SEARCH_DIAG box='" + searchState[0] + "',handlerSearchText='"
                    + searchState[1] + "',extractSlotsFilled=" + searchState[2]);
            check(searchState[1] != null && searchState[1].equals(searchState[0]),
                    "alchemy search propagates typed text to the screen handler");

            // 服务端是否真的收到了搜索词（客户端本地已生效，服务端要靠 itemalchemy:search 包）
            world.getServer().runOnServer(server -> {
                var player = world.getConnection().getServerPlayer();
                Object menu = player.containerMenu;
                System.out.println("PORT_SEARCH_DIAG serverMenu=" + menu.getClass().getName()
                        + ",serverSearchText='" + (menu instanceof AlchemyTableScreenHandler h ? h.searchText : "<n/a>")
                        + "'");
            });

            context.runOnClient(client -> client.player.closeContainer());
            world.getConnection().waitForServerboundPackets();
            world.getServer().runOnServer(server -> {
                var level = server.overworld();
                var player = world.getConnection().getServerPlayer();
                var pos = player.blockPosition().offset(2, 0, 0);
                IAExpConfigHolder.get().automationEnabled = true;
                IAExpConfigHolder.get().automationIntervalTicks = 1;
                level.setBlockAndUpdate(pos, EmcAutoBlocks.CONVERTER.defaultBlockState());
                level.setBlockAndUpdate(pos.below(), net.minecraft.world.level.block.Blocks.REDSTONE_BLOCK.defaultBlockState());
                var converter = (EmcConverterBlockEntity) level.getBlockEntity(pos);
                converter.setItem(0, new ItemStack(IAExpItems.EMC_CARD));
                converter.setItem(1, new ItemStack(Items.DIAMOND, 2));
                converter.tick(new net.pitan76.mcpitanlib.api.event.tile.TileTickEvent<>(level, pos, level.getBlockState(pos), converter));
                check(converter.getItem(1).getCount() == 1, "converter consumes one item from stack");
                check(EmcCardItem.getStoredEmc(converter.getItem(0)) == EMCManager.get(Items.DIAMOND), "converter credits item EMC");
                var tag = converter.saveWithFullMetadata(server.registryAccess());
                var restored = (EmcConverterBlockEntity) net.minecraft.world.level.block.entity.BlockEntity.loadStatic(pos, level.getBlockState(pos), tag, server.registryAccess());
                check(EmcCardItem.getStoredEmc(restored.getItem(0)) == EMCManager.get(Items.DIAMOND), "converter inventory persistence");
                player.openMenu(converter);
            });
            context.waitForScreen(EmcConverterScreen.class);
            context.waitTicks(8);
            context.takeScreenshot("port-converter");
            context.runOnClient(client -> client.player.closeContainer());
            world.getConnection().waitForServerboundPackets();
            world.getServer().runOnServer(server -> {
                var level = server.overworld();
                var player = world.getConnection().getServerPlayer();
                var pos = player.blockPosition().offset(3, 0, 0);
                level.setBlockAndUpdate(pos, EmcAutoBlocks.EMITTER.defaultBlockState());
                level.setBlockAndUpdate(pos.below(), net.minecraft.world.level.block.Blocks.REDSTONE_BLOCK.defaultBlockState());
                var emitter = (EmcEmitterBlockEntity) level.getBlockEntity(pos);
                var card = new ItemStack(IAExpItems.EMC_CARD);
                long unit = EMCManager.get(Items.DIAMOND);
                EmcCardItem.setStoredEmc(card, unit);
                emitter.setItem(0, card);
                emitter.setSelectedVariant("minecraft:diamond");
                emitter.tick(new net.pitan76.mcpitanlib.api.event.tile.TileTickEvent<>(level, pos, level.getBlockState(pos), emitter));
                check(EmcCardItem.getStoredEmc(card) == 0L, "emitter debits output EMC");
                check(!level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(pos).inflate(2), e -> e.getItem().is(Items.DIAMOND)).isEmpty(), "emitter creates output item");
                player.openMenu(emitter);
            });
            context.waitForScreen(EmcEmitterScreen.class);
            context.waitTicks(8);
            context.takeScreenshot("port-emitter");
            context.runOnClient(client -> client.player.closeContainer());
            world.getConnection().waitForServerboundPackets();
            world.getServer().runOnServer(server -> {
                IAExpConfigHolder.get().autoPricingFromRecipes = true;
                itemalchemy.expansion.recipe.RecipeAutoPricer.forceRecompute(server);
            });
            world.getServer().waitFor(server -> !itemalchemy.expansion.recipe.RecipeAutoPricer.isComputing());
            world.getServer().runOnServer(server -> check(!AutoEmcStore.snapshotGeneral().isEmpty(), "recipe auto pricing produces values"));
            world.getServer().runOnServer(server -> {
                String group = CardAccountStore.newGroupId();
                CardAccountStore.set(server, group, 100L);
                check(CardAccountStore.subtract(server, group, 40L), "shared account withdrawal");
                CardAccountStore.save(server);
                CardAccountStore.load(server);
                check(CardAccountStore.get(group) == 60L, "shared account persists");
            });
            final String[] preciseKey = {null};
            world.getServer().runOnServer(server -> {
                ItemStack named = new ItemStack(Items.DIAMOND);
                named.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Precise test"));
                preciseKey[0] = IAExpServices.variantKeyOf(named).toStorageString();
                world.getConnection().getServerPlayer().setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, named);
                world.getConnection().getServerPlayer().inventoryMenu.broadcastChanges();
            });
            world.getConnection().waitForClientboundPackets();
            context.runOnClient(client -> SetEmcClientNetwork.sendSetEmc("minecraft:diamond", 777L, SetEmcNetwork.SCOPE_THIS_SAVE, true, preciseKey[0], java.util.List.of()));
            world.getServer().waitFor(server -> java.util.Objects.equals(PreciseEmcStore.get(preciseKey[0]), 777L));
            world.getServer().runOnServer(server -> check(EMCManager.get(world.getConnection().getServerPlayer().getMainHandItem()) == 777L, "precise EMC network update affects valuation"));
            // 单人存档默认放行本模组命令（commandsRequireOp 只在服务器上生效）：
            // 直接检查 Brigadier 节点对单人玩家 source 的 requires 判定，避免副作用
            // 门禁挂在顶层字面量上（mcpitanlib 会丢弃子命令的 CommandSettings）。
            // 用一个「零权限执行者」判别：门禁缺失→默认谓词恒 true；沿用旧的静态 permissionLevel(2)→false；
            // 只有「我们的谓词 + 单人豁免」才会对零权限 source 也返回 true。
            boolean[] commandGate = {false, false};
            world.getServer().runOnServer(server -> {
                var root = server.getCommands().getDispatcher().getRoot().getChild("itemalchemy-expansion");
                var plain = world.getConnection().getServerPlayer().createCommandSourceStack().withSuppressedOutput();
                var noPerm = plain.withPermission(net.minecraft.server.permissions.PermissionSet.NO_PERMISSIONS);
                commandGate[0] = root != null && root.getRequirement().test(plain);
                commandGate[1] = root != null && root.getRequirement().test(noPerm);
            });
            System.out.println("PORT_PERM_DIAG topLevelRequires opPlayer=" + commandGate[0]
                    + " zeroPermSource=" + commandGate[1]);
            check(commandGate[0] && commandGate[1], "singleplayer bypasses the command op requirement");

            System.out.println("PORT_CLIENT_GAME_SMOKE_PASS");
        }
    }
}
