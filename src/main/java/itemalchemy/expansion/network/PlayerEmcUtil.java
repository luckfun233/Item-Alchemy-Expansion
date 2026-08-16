package itemalchemy.expansion.network;

import net.minecraft.server.MinecraftServer;
import net.pitan76.itemalchemy.data.PlayerState;
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

    /** 玩家是否已有转换桌队伍（绑卡前置条件：无队伍时卡无法接收/支出 EMC） */
    public static boolean hasTeam(MinecraftServer server, UUID uuid) {
        if (server == null || uuid == null) return false;
        try {
            return ServerState.of(server).getTeamByPlayer(uuid).isPresent();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 确保目标玩家已有转换桌队伍；没有则创建默认队伍（0 EMC）。
     * 用于绑卡：即使目标玩家从未用过转换桌，也能有一个可收支的 EMC 账户，
     * 避免「绑卡放入转换器/输出器被阻塞、余额无处入账」。
     *
     * @param name 队伍显示名（用于新建队伍；无名字时用 UUID 截断）
     * @return 是否确保存在队伍
     */
    public static boolean ensureTeam(MinecraftServer server, UUID uuid, String name) {
        if (server == null || uuid == null) return false;
        try {
            ServerState state = ServerState.of(server);
            if (state == null) return false;
            if (state.getTeamByPlayer(uuid).isPresent()) return true;

            TeamState team = new TeamState();
            team.name = (name == null || name.isEmpty()) ? uuid.toString().substring(0, 8) : name;
            team.createdAt = System.currentTimeMillis();
            team.teamID = UUID.randomUUID();
            team.owner = uuid;
            team.storedEMC = 0;
            team.isDefault = true;

            PlayerState ps = new PlayerState();
            ps.playerUUID = uuid;
            ps.teamID = team.teamID;

            state.teams.add(team);
            state.players.add(ps);
            state.callMarkDirty();
            return true;
        } catch (Throwable t) {
            return false;
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