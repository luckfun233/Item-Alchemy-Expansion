package itemalchemy.expansion;

import itemalchemy.expansion.config.IAExpConfigHolder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.permissions.Permissions;

/**
 * 服务器端权限门禁。
 *
 * <p>改价（K 键 GUI 的 setemc 包）与 {@code /itemalchemy-expansion} 命令共用同一判定，
 * 避免两处行为漂移：存档归属者放行——单人存档与「开放到局域网」的房主都算（豁免由其自行配置），
 * 其余玩家（局域网来客、专用服务器玩家）按配置项要求 {@code COMMANDS_GAMEMASTER}（权限等级 2，
 * 即房主开启了命令执行权限）。配置项见 {@link IAExpConfigHolder}。</p>
 *
 * <p>不能用在线人数判断豁免：局域网开放后房主仍应放行。{@code MinecraftServer#isSingleplayer()}
 * 在开放到局域网的世界里仍为 true（专用服务器为 false），故以它筛出集成服务器，再用
 * {@code MinecraftServer#isSingleplayerOwner} 认房主。</p>
 */
public final class IAExpPermissions {

    private IAExpPermissions() {}

    /** 改价（setemc 包）是否放行：受 {@code setEmcRequireOp} 控制 */
    public static boolean canSetEmc(MinecraftServer server, ServerPlayer player) {
        return allowed(isSaveOwner(server, player), IAExpConfigHolder.get().setEmcRequireOp,
                player == null ? null : player.permissions());
    }

    /** 本模组命令是否放行：受 {@code commandsRequireOp} 控制 */
    public static boolean canUseCommands(MinecraftServer server, CommandSourceStack source) {
        return allowed(isSaveOwner(server, source == null ? null : source.getPlayer()),
                IAExpConfigHolder.get().commandsRequireOp,
                source == null ? null : source.permissions());
    }

    /** 豁免：集成服务器（单人/局域网）的房主。专用服务器无房主概念，一律按 OP 判定 */
    private static boolean isSaveOwner(MinecraftServer server, ServerPlayer player) {
        return server != null && player != null
                && server.isSingleplayer()
                && server.isSingleplayerOwner(player.nameAndId());
    }

    /**
     * 纯判定（无副作用，便于测试）：房主或未开启校验时放行，否则要求 GAMEMASTER。
     *
     * @param saveOwner   是否为存档归属者（单人存档 / 局域网房主），见 {@link #isSaveOwner}
     * @param requireOp   配置是否要求 OP
     * @param permissions 执行者权限集合
     */
    public static boolean allowed(boolean saveOwner, boolean requireOp, PermissionSet permissions) {
        if (saveOwner || !requireOp) return true;
        return permissions != null && permissions.hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }
}
