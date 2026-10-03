package itemalchemy.expansion;

import itemalchemy.expansion.config.IAExpConfigHolder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.permissions.Permissions;

/**
 * 服务器端权限门禁。
 *
 * <p>改价（K 键 GUI 的 setemc 包）与 {@code /itemalchemy-expansion} 命令共用同一判定，
 * 避免两处行为漂移：单人存档一律放行（自己玩不受影响），多人/专用服务器按各自配置要求
 * {@code COMMANDS_GAMEMASTER}（权限等级 2）。配置项见 {@link IAExpConfigHolder}。</p>
 */
public final class IAExpPermissions {

    private IAExpPermissions() {}

    /** 改价（setemc 包）是否放行：受 {@code setEmcRequireOp} 控制 */
    public static boolean canSetEmc(MinecraftServer server, PermissionSet permissions) {
        return allowed(server != null && server.isSingleplayer(), IAExpConfigHolder.get().setEmcRequireOp, permissions);
    }

    /** 本模组命令是否放行：受 {@code commandsRequireOp} 控制 */
    public static boolean canUseCommands(MinecraftServer server, PermissionSet permissions) {
        return allowed(server != null && server.isSingleplayer(), IAExpConfigHolder.get().commandsRequireOp, permissions);
    }

    /**
     * 纯判定（无副作用，便于测试）：单人存档或未开启校验时放行，否则要求 GAMEMASTER。
     *
     * @param singleplayer 是否单人存档（集成服务器）
     * @param requireOp    配置是否要求 OP
     * @param permissions  执行者权限集合
     */
    public static boolean allowed(boolean singleplayer, boolean requireOp, PermissionSet permissions) {
        if (singleplayer || !requireOp) return true;
        return permissions != null && permissions.hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }
}
