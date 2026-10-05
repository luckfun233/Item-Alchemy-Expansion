package itemalchemy.expansion.command;

import itemalchemy.expansion.IAExpServices;
import itemalchemy.expansion.ItemAlchemyExpansion;
import itemalchemy.expansion.config.IAExpConfigHolder;
import net.pitan76.mcpitanlib.api.command.LiteralCommand;
import net.pitan76.mcpitanlib.api.event.ServerCommandEvent;

/**
 * 命令 {@code /itemalchemy-expansion reload}：重读配置文件并应用运行时变更。
 *
 * <p>用途：管理员修改 {@code config/itemalchemy-expansion.json5} 后不重启生效。
 * 当前会同步「自动装置」总开关对应的合成配方（关闭移除 / 开启恢复），
 * 并重建 NBT 指纹器。权限：门禁挂在顶层 {@link IAExpCommand}（默认仅 OP，单人存档豁免；
 * 服主可用配置项 {@code commandsRequireOp} 放开）。</p>
 */
public class ReloadCommand extends LiteralCommand {

    @Override
    public void execute(ServerCommandEvent e) {
        if (e.isClient()) return;

        try {
            IAExpServices.refresh(); // 重读配置 + 重建指纹器

            // 自动装置总开关：配方随配置即时增删（无需重启）
            net.minecraft.server.MinecraftServer server = e.getSource().getServer();
            ItemAlchemyExpansion.syncAutomationRecipes(server);

            e.sendSuccess("[Item Alchemy Expansion] Config reloaded. automationEnabled="
                    + IAExpConfigHolder.get().automationEnabled);
        } catch (Throwable t) {
            ItemAlchemyExpansion.LOGGER.error("[IAExp] /itemalchemy-expansion reload failed", t);
            e.sendSuccess("[Item Alchemy Expansion] Reload failed: " + t.getMessage());
        }
    }
}
