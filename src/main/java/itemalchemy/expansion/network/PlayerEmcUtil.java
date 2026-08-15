package itemalchemy.expansion.network;

import net.minecraft.server.MinecraftServer;
import net.pitan76.itemalchemy.data.ServerState;
import net.pitan76.itemalchemy.data.TeamState;

import java.util.Optional;
import java.util.UUID;

/**
 * 按玩家 UUID 读写其 team EMC（服务端）。
 *
 * <p>供「绑卡」使用：卡余额/收支同步到指定玩家。通过 {@link ServerState} 直接按 UUID 操作，
 * 玩家离线时也可读写（team 数据属服务端存档）。所有方法须在服务端主线程调用。</p>
 */
public final class PlayerEmcUtil {

    private PlayerEmcUtil() {}

    /** 读取玩家 team EMC；玩家无队伍返回 0 */
    public static long getEmc(MinecraftServer server, UUID uuid) {
        if (server == null || uuid == null) return 0L;
        try {
            ServerState state = ServerState.of(server);
            return state.getTeamByPlayer(uuid).map(t -> t.storedEMC).orElse(0L);
        } catch (Throwable t) {
            return 0L;
        }
    }

    /**
     * 给玩家 team EMC 加值（amount &gt; 0）。
     *
     * @return 是否真正入账。玩家无队伍（如从未进过服的绑定目标）时返回 false，
     *         调用方据此决定是否消耗来源物品/EMC，避免「物品被吞、余额未入账」。
     */
    public static boolean add(MinecraftServer server, UUID uuid, long amount) {
        if (server == null || uuid == null || amount <= 0) return false;
        try {
            ServerState state = ServerState.of(server);
            Optional<TeamState> team = state.getTeamByPlayer(uuid);
            if (!team.isPresent()) return false;
            team.get().storedEMC += amount;
            state.callMarkDirty();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 从玩家 team EMC 减值；余额不足返回 false（不扣减） */
    public static boolean subtract(MinecraftServer server, UUID uuid, long amount) {
        if (server == null || uuid == null || amount <= 0) return false;
        try {
            ServerState state = ServerState.of(server);
            Optional<TeamState> team = state.getTeamByPlayer(uuid);
            if (!team.isPresent()) return false;
            TeamState ts = team.get();
            if (ts.storedEMC < amount) return false;
            ts.storedEMC -= amount;
            state.callMarkDirty();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}