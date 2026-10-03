package itemalchemy.expansion;

import itemalchemy.expansion.config.IAExpConfigHolder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * 服务器端权限门禁。
 *
 * <p>改价（K 键 GUI 的 setemc 包）与 {@code /itemalchemy-expansion} 命令共用同一判定，
 * 避免两处行为漂移：单人存档一律放行（自己玩不受影响），多人/专用服务器按各自配置要求
 * 权限等级 2（OP）。配置项见 {@link IAExpConfigHolder}。</p>
 */
public final class IAExpPermissions {

    private IAExpPermissions() {}

    /** 改价（setemc 包）是否放行：受 {@code setEmcRequireOp} 控制 */
    public static boolean canSetEmc(MinecraftServer server, ServerPlayerEntity player) {
        return allowed(server != null && server.isSingleplayer(),
                IAExpConfigHolder.get().setEmcRequireOp,
                player != null && player.hasPermissionLevel(2));
    }

    /** 本模组命令是否放行：受 {@code commandsRequireOp} 控制 */
    public static boolean canUseCommands(MinecraftServer server, ServerCommandSource source) {
        return allowed(server != null && server.isSingleplayer(),
                IAExpConfigHolder.get().commandsRequireOp,
                source != null && source.hasPermissionLevel(2));
    }

    /**
     * 纯判定（无副作用，便于测试）：单人存档或未开启校验时放行，否则要求权限等级 2。
     *
     * @param singleplayer       是否单人存档（集成服务器）
     * @param requireOp          配置是否要求 OP
     * @param hasPermissionLevel2 执行者是否达到权限等级 2
     */
    public static boolean allowed(boolean singleplayer, boolean requireOp, boolean hasPermissionLevel2) {
        if (singleplayer || !requireOp) return true;
        return hasPermissionLevel2;
    }
}
