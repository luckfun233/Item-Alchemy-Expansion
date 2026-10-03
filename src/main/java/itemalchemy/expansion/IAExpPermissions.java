package itemalchemy.expansion;

import itemalchemy.expansion.config.IAExpConfigHolder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.permissions.Permissions;

/**
 * 服务器端权限门禁。
 *
 * <p>改价（K 键 GUI 的 setemc 包）与 {@code /itemalchemy-expansion} 命令共用同一判定，
 * 避免两处行为漂移：仅"真单人"（集成服务器且只有自己在线）放行，局域网联机与他人同处
 * 一档时按多人处理，要求 {@code COMMANDS_GAMEMASTER}（权限等级 2）。配置项见
 * {@link IAExpConfigHolder}。</p>
 *
 * <p>{@code MinecraftServer#isSingleplayer()} 在开放到局域网的世界里仍为 true，不能单独
 * 作为豁免依据，否则联机时门禁形同虚设。</p>
 */
public final class IAExpPermissions {

    private IAExpPermissions() {}

    /** 改价（setemc 包）是否放行：受 {@code setEmcRequireOp} 控制 */
    public static boolean canSetEmc(MinecraftServer server, PermissionSet permissions) {
        return allowed(trueSingleplayer(server), IAExpConfigHolder.get().setEmcRequireOp, permissions);
    }

    /** 本模组命令是否放行：受 {@code commandsRequireOp} 控制 */
    public static boolean canUseCommands(MinecraftServer server, PermissionSet permissions) {
        return allowed(trueSingleplayer(server), IAExpConfigHolder.get().commandsRequireOp, permissions);
    }

    /** 真单人 = 集成服务器且在线人数不超过 1（只有自己） */
    private static boolean trueSingleplayer(MinecraftServer server) {
        return server != null && server.isSingleplayer()
                && server.getPlayerList().getPlayers().size() <= 1;
    }

    /**
     * 纯判定（无副作用，便于测试）：真单人或未开启校验时放行，否则要求 GAMEMASTER。
     *
     * @param singleplayer 是否真单人（见 {@link #trueSingleplayer}）
     * @param requireOp    配置是否要求 OP
     * @param permissions  执行者权限集合
     */
    public static boolean allowed(boolean singleplayer, boolean requireOp, PermissionSet permissions) {
        if (singleplayer || !requireOp) return true;
        return permissions != null && permissions.hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }
}
