package itemalchemy.expansion;

import itemalchemy.expansion.config.IAExpConfigHolder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * 服务器端权限门禁。
 *
 * <p>改价（K 键 GUI 的 setemc 包 / 重新定价确认包）与 {@code /itemalchemy-expansion} 命令共用同一判定，
 * 避免两处行为漂移：仅"真单人"（集成服务器且只有自己在线）放行，局域网联机与他人同处
 * 一档时按多人处理，要求权限等级 2（OP）。配置项见 {@link IAExpConfigHolder}。</p>
 *
 * <p>{@code MinecraftServer#isSingleplayer()} 在开放到局域网的世界里仍为 true，不能单独
 * 作为豁免依据，否则联机时门禁形同虚设。</p>
 */
public final class IAExpPermissions {

    private IAExpPermissions() {}

    /** 改价与重新定价是否放行：受 {@code setEmcRequireOp} 控制 */
    public static boolean canSetEmc(MinecraftServer server, ServerPlayerEntity player) {
        return allowed(trueSingleplayer(server),
                IAExpConfigHolder.get().setEmcRequireOp,
                player != null && player.hasPermissionLevel(2));
    }

    /** 本模组命令是否放行：受 {@code commandsRequireOp} 控制 */
    public static boolean canUseCommands(MinecraftServer server, ServerCommandSource source) {
        return allowed(trueSingleplayer(server),
                IAExpConfigHolder.get().commandsRequireOp,
                source != null && source.hasPermissionLevel(2));
    }

    /** 真单人 = 集成服务器且在线人数不超过 1（只有自己） */
    private static boolean trueSingleplayer(MinecraftServer server) {
        return server != null && server.isSingleplayer()
                && server.getPlayerManager().getCurrentPlayerCount() <= 1;
    }

    /**
     * 纯判定（无副作用，便于测试）：真单人或未开启校验时放行，否则要求权限等级 2。
     *
     * @param singleplayer        是否真单人（见 {@link #trueSingleplayer}）
     * @param requireOp           配置是否要求 OP
     * @param hasPermissionLevel2 执行者是否达到权限等级 2
     */
    public static boolean allowed(boolean singleplayer, boolean requireOp, boolean hasPermissionLevel2) {
        if (singleplayer || !requireOp) return true;
        return hasPermissionLevel2;
    }
}
