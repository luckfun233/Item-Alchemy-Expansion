package itemalchemy.expansion.command;

import itemalchemy.expansion.ItemAlchemyExpansion;
import itemalchemy.expansion.config.IAExpConfigHolder;
import itemalchemy.expansion.network.SetEmcNetwork;
import itemalchemy.expansion.recipe.RecipeAutoPricer;
import net.pitan76.mcpitanlib.api.command.CommandSettings;
import net.pitan76.mcpitanlib.api.command.LiteralCommand;
import net.pitan76.mcpitanlib.api.event.ServerCommandEvent;

/**
 * 命令 {@code /itemalchemy-expansion reprice}：强制重新扫描配方、重算自动定价 EMC。
 *
 * <p>用途：安装新模组/新配方后刷新自动定价；或重新定价对话框已弹过（{@code autoPricingRepricePromptShown=true}）
 * 后想再次触发候选流程；等效于删除 {@code <world>/itemalchemy_expansion_auto_emc.json} 缓存后重启。
 * 权限：permissionLevel 2（与上游 {@code /itemalchemy reloademc} 一致）。
 * 自动定价关闭时执行也能跑（强制扫一遍写出缓存，下次开启时直接命中缓存）。
 * 若要清空自动定价结果，请在配置 GUI 关闭「配方自动定价」（调用
 * {@link itemalchemy.expansion.network.AutoEmcStore#clear()}）。</p>
 */
public class RepriceCommand extends LiteralCommand {

    @Override
    public void init(CommandSettings settings) {
        settings.permissionLevel(2);
    }

    @Override
    public void execute(ServerCommandEvent e) {
        if (e.isClient()) return;

        try {
            // 重置「重新定价对话框已弹过」标志：仅当自动定价开启时才重置
            if (IAExpConfigHolder.get().autoPricingFromRecipes) {
                IAExpConfigHolder.get().autoPricingRepricePromptShown = false;
                IAExpConfigHolder.save();
            }

            // 强制重算：删缓存 + 重新扫描
            // 1.20.1 yarn + mcpitanlib 3.3.2: ServerCommandEvent 无 getMidohraWorld()，
            // 用 getSource().getServer() 直接拿 MinecraftServer
            net.minecraft.server.MinecraftServer server = e.getSource().getServer();
            RecipeAutoPricer.forceRecompute(server);

            // 重同步给所有在线玩家
            try {
                SetEmcNetwork.resyncAllPublic(server);
            } catch (Throwable ignore) {}

            e.sendSuccess("[Item Alchemy Expansion] Re-priced recipes. Check logs for details.");
        } catch (Throwable t) {
            ItemAlchemyExpansion.LOGGER.error("[IAExp] /itemalchemy-expansion reprice failed", t);
            e.sendSuccess("[Item Alchemy Expansion] Re-price failed: " + t.getMessage());
        }
    }
}
