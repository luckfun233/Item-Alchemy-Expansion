package itemalchemy.expansion.client;

import itemalchemy.expansion.IAExpServices;
import itemalchemy.expansion.item.EmcCardItem;
import itemalchemy.expansion.nbt.ItemVariantKey;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.pitan76.itemalchemy.EMCManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * EMC 输出器选择界面：从「打开者的转换桌列表」选择要喷出的物品。
 *
 * <p>所选物品存于方块 NBT（共享），他人打开可见同一设置。列表基于打开者本人，
 * 卡余额由服务端下发。滚动列表 + 点击选择。</p>
 */
public class EmcEmitterScreen extends Screen {

    private static final int PANEL_W = 260;
    private static final int PANEL_H = 240;
    private static final int LIST_TOP_OFF = 84;
    private static final int LIST_BOT_OFF = 24;
    private static final int ROW_H = 20;

    private final long pos;
    private final List<String> keys = new ArrayList<>();
    private final List<ItemStack> stacks = new ArrayList<>();
    /** 过滤后的下标（指向 keys/stacks），搜索为空时与全量一一对应 */
    private final List<Integer> filtered = new ArrayList<>();
    private String selected = "";
    private long balance = 0;
    private boolean loaded = false;
    private int scroll = 0;
    private TextFieldWidget searchField;

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
        int panelLeft = centerX - PANEL_W / 2;

        // 搜索框（模糊过滤转换桌列表：名称 / id / 变体键）
        String oldText = searchField == null ? "" : searchField.getText();
        searchField = new TextFieldWidget(this.textRenderer, panelLeft + 14, panelTop + 66,
                PANEL_W - 28, 14, Text.translatable("itemalchemy-expansion.emc_emitter.search"));
        searchField.setMaxLength(64);
        searchField.setText(oldText);
        searchField.setChangedListener(t -> applyFilter());
        addDrawableChild(searchField);
        applyFilter();

        addDrawableChild(ButtonWidget.builder(
                Text.translatable("itemalchemy-expansion.emc_emitter.clear"),
                b -> EmcAutoClientNetwork.sendSet(pos, null))
                .dimensions(centerX + 56, panelTop + 34, 60, 20).build());
        addDrawableChild(ButtonWidget.builder(
                Text.translatable("itemalchemy-expansion.close"),
                b -> this.close())
                .dimensions(centerX - 30, panelTop + PANEL_H - 30, 60, 20).build());
    }

    /** 按搜索词重建过滤视图并复位滚动 */
    private void applyFilter() {
        filtered.clear();
        String q = searchField == null ? "" : searchField.getText().trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < keys.size(); i++) {
            if (q.isEmpty() || matches(stacks.get(i), keys.get(i), q)) {
                filtered.add(i);
            }
        }
        scroll = 0;
    }

    private boolean matches(ItemStack stack, String key, String q) {
        if (key != null && key.toLowerCase(Locale.ROOT).contains(q)) {
            return true;
        }
        if (!stack.isEmpty()) {
            String name = stack.getName().getString().toLowerCase(Locale.ROOT);
            if (name.contains(q)) {
                return true;
            }
            try {
                String id = Registries.ITEM.getId(stack.getItem()).toString();
                if (id.toLowerCase(Locale.ROOT).contains(q)) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
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
        applyFilter();
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
                int row = scroll + (int) ((mouseY - listTop) / ROW_H);
                if (row >= 0 && row < filtered.size()) {
                    EmcAutoClientNetwork.sendSet(pos, keys.get(filtered.get(row)));
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
            int maxScroll = Math.max(0, filtered.size() - rows);
            scroll = (int) Math.max(0, Math.min(maxScroll, scroll - amount));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    private boolean canPick() {
        return loaded && !filtered.isEmpty();
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
        } else if (filtered.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.translatable(keys.isEmpty()
                            ? "itemalchemy-expansion.emc_emitter.empty"
                            : "itemalchemy-expansion.emc_emitter.no_match"),
                    centerX, (listTop + listBottom) / 2 - 4, 0xFF707070);
        } else {
            int rows = visibleRows();
            int maxScroll = Math.max(0, filtered.size() - rows);
            if (scroll > maxScroll) scroll = maxScroll;
            int start = scroll;
            int end = Math.min(filtered.size(), start + rows);
            for (int r = start; r < end; r++) {
                int i = filtered.get(r);
                int y = listTop + (r - start) * ROW_H;
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