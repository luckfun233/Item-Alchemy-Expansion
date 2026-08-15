package itemalchemy.expansion.client;

import itemalchemy.expansion.IAExpServices;
import itemalchemy.expansion.item.EmcCardItem;
import itemalchemy.expansion.nbt.ItemVariantKey;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.pitan76.itemalchemy.EMCManager;

import java.util.ArrayList;
import java.util.List;

/**
 * EMC 输出器选择界面：从「打开者的转换桌列表」选择要喷出的物品。
 *
 * <p>所选物品存于方块 NBT（共享），他人打开可见同一设置。列表基于打开者本人，
 * 卡余额由服务端下发。滚动列表 + 点击选择。</p>
 */
public class EmcEmitterScreen extends Screen {

    private static final int PANEL_W = 260;
    private static final int PANEL_H = 240;
    private static final int LIST_TOP_OFF = 70;
    private static final int LIST_BOT_OFF = 24;
    private static final int ROW_H = 20;

    private final long pos;
    private final List<String> keys = new ArrayList<>();
    private final List<ItemStack> stacks = new ArrayList<>();
    private String selected = "";
    private long balance = 0;
    private boolean loaded = false;
    private int scroll = 0;

    public EmcEmitterScreen(long pos, boolean requestOnInit) {
        super(Text.translatable("itemalchemy-expansion.emc_emitter.title"));
        this.pos = pos;
        EmcAutoClientNetwork.attach(this);
        if (requestOnInit) {
            EmcAutoClientNetwork.sendRequest(pos);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int panelTop = this.height / 2 - PANEL_H / 2;
        addDrawableChild(ButtonWidget.builder(
                Text.translatable("itemalchemy-expansion.emc_emitter.clear"),
                b -> EmcAutoClientNetwork.sendSet(pos, null))
                .dimensions(centerX + 56, panelTop + 34, 60, 20).build());
        addDrawableChild(ButtonWidget.builder(
                Text.translatable("itemalchemy-expansion.close"),
                b -> this.close())
                .dimensions(centerX - 30, panelTop + PANEL_H - 30, 60, 20).build());
    }

    /** 服务端下发列表后调用 */
    public void onListReceived(List<String> newKeys, List<ItemStack> newStacks, String newSelected, long newBalance) {
        this.keys.clear();
        this.keys.addAll(newKeys);
        this.stacks.clear();
        this.stacks.addAll(newStacks);
        this.selected = (newSelected == null || newSelected.isEmpty()) ? "" : newSelected;
        this.balance = newBalance;
        this.loaded = true;
    }

    /** 服务端下发更新后的所选物品 */
    public void onSelectedUpdated(String newSelected) {
        this.selected = (newSelected == null || newSelected.isEmpty()) ? "" : newSelected;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && canPick()) {
            int listTop = this.height / 2 - PANEL_H / 2 + LIST_TOP_OFF;
            int listBottom = this.height / 2 - PANEL_H / 2 + PANEL_H - LIST_BOT_OFF;
            if (mouseY >= listTop && mouseY < listBottom) {
                int idx = scroll + (int) ((mouseY - listTop) / ROW_H);
                if (idx >= 0 && idx < keys.size()) {
                    EmcAutoClientNetwork.sendSet(pos, keys.get(idx));
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (canPick()) {
            int rows = visibleRows();
            int maxScroll = Math.max(0, keys.size() - rows);
            scroll = (int) Math.max(0, Math.min(maxScroll, scroll - amount));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    private boolean canPick() {
        return loaded && !keys.isEmpty();
    }

    private int visibleRows() {
        return (PANEL_H - LIST_TOP_OFF - LIST_BOT_OFF) / ROW_H;
    }

    private ItemStack selectedStack() {
        if (selected.isEmpty()) return ItemStack.EMPTY;
        ItemVariantKey vk = ItemVariantKey.fromStorageString(selected);
        if (vk == null) return ItemStack.EMPTY;
        return IAExpServices.rebuildStack(vk);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context);

        int centerX = this.width / 2;
        int panelLeft = centerX - PANEL_W / 2;
        int panelTop = this.height / 2 - PANEL_H / 2;

        // 面板（MC 浅灰风格）
        context.fill(panelLeft, panelTop, panelLeft + PANEL_W, panelTop + PANEL_H, 0xFFC6C6C6);
        drawBorder(context, panelLeft, panelTop, PANEL_W, PANEL_H, 0xFF555555);

        // 标题
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, centerX, panelTop + 10, 0xFF404040);
        context.fill(panelLeft + 14, panelTop + 24, panelLeft + PANEL_W - 14, panelTop + 25, 0xFF555555);

        // 卡余额
        context.drawText(this.textRenderer,
                Text.translatable("itemalchemy-expansion.emc_emitter.balance", Text.literal(EmcCardItem.formatNumber(balance))),
                panelLeft + 14, panelTop + 34, 0xFF707070, false);

        // 当前选择
        ItemStack sel = selectedStack();
        if (sel.isEmpty()) {
            context.drawText(this.textRenderer,
                    Text.translatable("itemalchemy-expansion.emc_emitter.none"),
                    panelLeft + 14, panelTop + 46, 0xFF888888, false);
        } else {
            context.drawItem(sel, panelLeft + 14, panelTop + 44);
            long cost = emcOf(sel);
            Text name = sel.getName();
            Text row = cost > 0
                    ? Text.literal(name.getString() + "  (" + EmcCardItem.formatNumber(cost) + " EMC)")
                    : name;
            context.drawText(this.textRenderer, row, panelLeft + 34, panelTop + 48, 0xFF404040, false);
        }

        // 列表区
        int listTop = panelTop + LIST_TOP_OFF;
        int listBottom = panelTop + PANEL_H - LIST_BOT_OFF;
        context.fill(panelLeft + 8, listTop, panelLeft + PANEL_W - 8, listBottom, 0xFF8B8B8B);
        drawBorder(context, panelLeft + 8, listTop, PANEL_W - 16, listBottom - listTop, 0xFF555555);

        if (!loaded) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.translatable("itemalchemy-expansion.emc_emitter.loading"),
                    centerX, (listTop + listBottom) / 2 - 4, 0xFF707070);
        } else if (keys.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.translatable("itemalchemy-expansion.emc_emitter.empty"),
                    centerX, (listTop + listBottom) / 2 - 4, 0xFF707070);
        } else {
            int rows = visibleRows();
            int maxScroll = Math.max(0, keys.size() - rows);
            if (scroll > maxScroll) scroll = maxScroll;
            int start = scroll;
            int end = Math.min(keys.size(), start + rows);
            for (int i = start; i < end; i++) {
                int y = listTop + (i - start) * ROW_H;
                ItemStack st = stacks.get(i);
                boolean isSel = keys.get(i).equals(selected);
                if (isSel) {
                    context.fill(panelLeft + 9, y, panelLeft + PANEL_W - 9, y + ROW_H, 0xFFA0A0A0);
                }
                if (!st.isEmpty()) {
                    context.drawItem(st, panelLeft + 12, y + 1);
                }
                String name = st.isEmpty() ? keys.get(i) : st.getName().getString();
                context.drawText(this.textRenderer, name, panelLeft + 32, y + 6,
                        isSel ? 0xFF404040 : 0xFF606060, false);
                if (isSel) {
                    context.drawText(this.textRenderer, ">", panelLeft + 9, y + 6, 0xFF2EC4B6, false);
                }
            }
        }

        super.render(context, mouseX, mouseY, delta);
    }

    private long emcOf(ItemStack stack) {
        try {
            return EMCManager.get(stack);
        } catch (Throwable t) {
            return 0;
        }
    }

    private void drawBorder(DrawContext ctx, int x, int y, int w, int h, int color) {
        ctx.fill(x, y, x + w, y + 1, color);
        ctx.fill(x, y + h - 1, x + w, y + h, color);
        ctx.fill(x, y, x + 1, y + h, color);
        ctx.fill(x + w - 1, y, x + w, y + h, color);
    }

    @Override
    public void close() {
        EmcAutoClientNetwork.detach(this);
        super.close();
    }
}