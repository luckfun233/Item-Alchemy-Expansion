package itemalchemy.expansion.command;

import itemalchemy.expansion.IAExpPermissions;
import net.pitan76.mcpitanlib.api.command.CommandSettings;
import net.pitan76.mcpitanlib.api.command.LiteralCommand;
import net.pitan76.mcpitanlib.api.event.ServerCommandEvent;

/**
 * 顶层命令 {@code /itemalchemy-expansion}：本模组的命令入口。
 *
 * <p>子命令 {@code reprice} 强制重新扫描配方、重算自动定价 EMC（{@link RepriceCommand}），
 * {@code reload} 重读配置（{@link ReloadCommand}）。</p>
 *
 * <p><b>权限门禁挂在本类</b>：mcpitanlib 只把顶层 {@code CommandSettings} 接到 Brigadier
 * （顶层 {@code literal(name).requires(settings::requires)}，而子命令的 settings 被丢弃），
 * 所以子命令上写 permissionLevel 无效。Brigadier 遍历时校验父节点，挂在这里即可覆盖全部子命令。
 * 单人存档豁免，配置项 {@code commandsRequireOp}；判定见 {@link IAExpPermissions}。</p>
 */
public class IAExpCommand extends LiteralCommand {

    @Override
    public void init(CommandSettings settings) {
        // 单人存档放行；服务器上按 commandsRequireOp 要求 OP（默认要求）
        settings.permissionLevel(-1).custom(source ->
                IAExpPermissions.canUseCommands(source.getServer(), source));
        super.init(settings); // 转发到无参 init()，否则子命令不会被注册
    }

    @Override
    public void init() {
        addArgumentCommand("reprice", new RepriceCommand());
        addArgumentCommand("reload", new ReloadCommand());
    }

    @Override
    public void execute(ServerCommandEvent e) {
        e.sendSuccess("[Item Alchemy Expansion]"
                + "\n- /itemalchemy-expansion reprice...Force re-scan recipes and recompute auto-priced EMC"
                + "\n- /itemalchemy-expansion reload...Reload config and sync automation recipes"
        );
    }
}
