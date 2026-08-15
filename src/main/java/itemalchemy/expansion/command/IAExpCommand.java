package itemalchemy.expansion.command;

import net.pitan76.mcpitanlib.api.command.LiteralCommand;
import net.pitan76.mcpitanlib.api.event.ServerCommandEvent;

/**
 * 顶层命令 {@code /itemalchemy-expansion}：本模组的命令入口。
 *
 * <p>子命令 {@code reprice} 强制重新扫描配方、重算自动定价 EMC（{@link RepriceCommand}）。
 * 整体 permissionLevel 2，子命令可在各自 {@code init(CommandSettings)} 中再细化。</p>
 */
public class IAExpCommand extends LiteralCommand {

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
