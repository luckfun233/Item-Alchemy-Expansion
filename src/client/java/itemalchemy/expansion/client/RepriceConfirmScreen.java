package itemalchemy.expansion.client;

import itemalchemy.expansion.IAExpServices;
import itemalchemy.expansion.nbt.ItemVariantKey;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * 「重新定价」确认对话框：玩家首次开启「配方自动定价」时弹出一次。
 *
 * <p>由 {@code SetEmcClientNetwork} 收到 {@code reprice_candidates} S2C 包后打开。
 * 服务端扫描 {@link itemalchemy.expansion.network.PerSaveEmcStore} 的候选（排除原版 +
 * 上游已定义），把候选 itemId 列表推给客户端，本界面展示并让玩家选择：</p>
 *
 * <p><b>界面布局</b>：
 * <pre>
 *   ┌──────────────────────────────────────────────┐
 *   │            重新定价已设置的物品？                │  标题
 *   │                                                │
 *   │   检测到你在本存档中手动设置了以下物品的 EMC：   │  说明
 *   │                                                │
 *   │   [图标] itemA  [图标] itemB  [图标] itemC     │  候选列表（最多 10 个图标 + 名称）
 *   │   [图标] itemD  ...                            │
 *   │                                                │
 *   │   共 N 个物品可被自动重新定价                    │  计数
 *   │                                                │
 *   │   [是，重新自动定价]   [否，保留我的设置]        │  按钮
 *   └──────────────────────────────────────────────┘
 * </pre>
 *
 * <p><b>选择语义</b>：
 * <ul>
 *   <li>「是」：从 {@code PerSaveEmcStore} 删除候选条目 + 服务端强制重算自动定价 + 重同步给所有玩家</li>
 *   <li>「否」：保留玩家覆盖，自动定价只处理未定义物品（候选物品的玩家值继续生效，优先级高于自动）</li>
 * </ul>
 * </p>
 *
 * <p>无论选什么，服务端都会置 {@code autoPricingRepricePromptShown=true} 写盘，
 * 对话框只弹一次。玩家想再次触发可用命令 {@code /itemalchemy-expansion reprice}。</p>
 *
 * <p><b>暂停世界</b>：覆写 {@link #shouldPause()} 返回 true，与 {@link SetEmcScreen} 一致。</p>
 */
public class RepriceConfirmScreen extends Screen {

    /** 面板宽度 */
    private static final int PANEL_WIDTH = 320;
    /** 面板内边距 */
    private static final int PADDING = 12;
    /** 候选图标列表每行最多显示数 */
    private static final int ITEMS_PER_ROW = 5;
    /** 候选最多显示的物品数（超出显示「等 N 个」） */
    private static final int MAX_DISPLAY_ITEMS = 10;

    /** 服务端推送的候选 itemId 列表（已规范化，含命名空间） */
    private final List<String> candidateIds;
    /** 候选对应的 ItemStack（用于显示图标 + 名称；解析失败的物品用空堆占位） */
    private final List<ItemStack> candidateStacks;

    public RepriceConfirmScreen(List<String> candidateIds) {
        super(Text.translatable("itemalchemy-expansion.reprice.title"));
        this.candidateIds = candidateIds == null ? new ArrayList<>() : new ArrayList<>(candidateIds);
        this.candidateStacks = new ArrayList<>();
        if (this.candidateIds != null) {
            for (String id : this.candidateIds) {
                this.candidateStacks.add(resolveStack(id));
            }
        }
    }

    /** 根据 itemId / 变体键解析 ItemStack（用于显示图标，带 NBT 还原） */
    private static ItemStack resolveStack(String idStr) {
        try {
            // 优先按变体键解析（支持 "itemId\u0001nbtFingerprint" 格式，还原 NBT）
            ItemVariantKey vk = ItemVariantKey.fromStorageString(idStr);
            if (vk != null) {
                ItemStack stack = IAExpServices.rebuildStack(vk);
                if (!stack.isEmpty()) return stack;
            }
            // 回退：纯 itemId
            Identifier id = Identifier.tryParse(idStr);
            if (id != null && Registries.ITEM.containsId(id)) {
                return new ItemStack(Registries.ITEM.get(id));
            }
        } catch (Throwable ignore) {}
        return ItemStack.EMPTY;
    }

    @Override
    public boolean shouldPause() {
        return true;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int btnY = this.height / 2 + 50;
        int btnWidth = 140;
        int btnGap = 10;

        // 「是」按钮
        addDrawableChild(ButtonWidget.builder(
                Text.translatable("itemalchemy-expansion.reprice.confirm_yes"),
                b -> onConfirm(true))
                .dimensions(centerX - btnWidth - btnGap / 2, btnY, btnWidth, 20)
                .build());

        // 「否」按钮
        addDrawableChild(ButtonWidget.builder(
                Text.translatable("itemalchemy-expansion.reprice.confirm_no"),
                b -> onConfirm(false))
                .dimensions(centerX + btnGap / 2, btnY, btnWidth, 20)
                .build());
    }

    /** 玩家选择后发送 C2S 包并关闭界面 */
    private void onConfirm(boolean yes) {
        SetEmcClientNetwork.sendRepriceConfirm(yes);
        this.close();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context);

        int centerX = this.width / 2;
        int panelLeft = centerX - PANEL_WIDTH / 2;
        int panelTop = this.height / 2 - 100;
        int panelHeight = 200;
        int panelBottom = panelTop + panelHeight;

        // 面板背景
        context.fill(panelLeft, panelTop, panelLeft + PANEL_WIDTH, panelBottom, 0xC0101010);
        drawBorder(context, panelLeft, panelTop, PANEL_WIDTH, panelHeight, 0xFF404040);

        // 标题
        context.drawCenteredTextWithShadow(this.textRenderer, this.title,
                centerX, panelTop + PADDING, 0xFFFFFF);

        // 说明文字
        Text desc = Text.translatable("itemalchemy-expansion.reprice.desc")
                .formatted(Formatting.GRAY);
        context.drawText(this.textRenderer, desc,
                panelLeft + PADDING, panelTop + PADDING + 18, 0xC0C0C0, false);

        // 候选列表：图标 + 名称
        int listTop = panelTop + PADDING + 36;
        int iconSize = 18;
        int rowGap = 22;
        int colGap = 60;
        int displayCount = Math.min(candidateStacks.size(), MAX_DISPLAY_ITEMS);

        for (int i = 0; i < displayCount; i++) {
            int row = i / ITEMS_PER_ROW;
            int col = i % ITEMS_PER_ROW;
            int x = panelLeft + PADDING + col * colGap;
            int y = listTop + row * rowGap;
            ItemStack stack = candidateStacks.get(i);
            String idStr = candidateIds.get(i);

            // 图标
            context.drawItem(stack, x, y - 2);
            // 名称（截断到 14 字符，超出 ...)
            String name;
            try {
                name = stack.isEmpty() ? idStr : stack.getName().getString();
            } catch (Throwable t) {
                name = idStr;
            }
            if (name == null || name.isEmpty()) name = idStr;
            if (name.length() > 14) name = name.substring(0, 13) + "...";
            context.drawText(this.textRenderer, name, x + 18, y + 4, 0xFFFFFF, false);
        }

        // 「等 N 个...」
        if (candidateStacks.size() > MAX_DISPLAY_ITEMS) {
            int moreCount = candidateStacks.size() - MAX_DISPLAY_ITEMS;
            int moreRow = MAX_DISPLAY_ITEMS / ITEMS_PER_ROW;
            int moreX = panelLeft + PADDING + (MAX_DISPLAY_ITEMS % ITEMS_PER_ROW) * colGap;
            int moreY = listTop + moreRow * rowGap;
            if (MAX_DISPLAY_ITEMS % ITEMS_PER_ROW == 0) {
                // 刚好整行，放下一行第一个
                moreX = panelLeft + PADDING;
                moreY = listTop + moreRow * rowGap;
            }
            Text moreText = Text.translatable("itemalchemy-expansion.reprice.more",
                    String.valueOf(moreCount)).formatted(Formatting.GRAY);
            context.drawText(this.textRenderer, moreText, moreX, moreY + 4, 0xA0A0A0, false);
        }

        // 计数行
        Text countText = Text.translatable("itemalchemy-expansion.reprice.count",
                String.valueOf(candidateStacks.size())).formatted(Formatting.YELLOW);
        context.drawText(this.textRenderer, countText,
                panelLeft + PADDING, panelTop + panelHeight - 56, 0xFFFF00, false);

        // 提示：选择后将立即重算并同步给所有玩家
        Text hint = Text.translatable("itemalchemy-expansion.reprice.hint")
                .formatted(Formatting.DARK_GRAY);
        context.drawText(this.textRenderer, hint,
                panelLeft + PADDING, panelTop + panelHeight - 44, 0x808080, false);

        // 提示：以后可用 /itemalchemy-expansion reprice 再次触发
        Text hint2 = Text.translatable("itemalchemy-expansion.reprice.hint2")
                .formatted(Formatting.DARK_GRAY);
        context.drawText(this.textRenderer, hint2,
                panelLeft + PADDING, panelTop + panelHeight - 34, 0x808080, false);

        super.render(context, mouseX, mouseY, delta);
    }

    /** 绘制边框（4 条线） */
    private static void drawBorder(DrawContext context, int x, int y, int width, int height, int color) {
        context.fill(x, y, x + width, y + 1, color);
        context.fill(x, y + height - 1, x + width, y + height, color);
        context.fill(x, y, x + 1, y + height, color);
        context.fill(x + width - 1, y, x + width, y + height, color);
    }
}
