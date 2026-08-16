package itemalchemy.expansion.network;

import itemalchemy.expansion.ItemAlchemyExpansion;
import net.minecraft.server.MinecraftServer;
import net.pitan76.itemalchemy.data.PlayerState;
import net.pitan76.itemalchemy.data.ServerState;
import net.pitan76.itemalchemy.data.TeamState;

import java.lang.reflect.Method;
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

    private static void log(String fmt, Object... args) {
        // 诊断日志：仅 debugLogging 开启时输出（绑卡/自动装置高频调用下避免日志刷屏）
        ItemAlchemyExpansion.debug("[IAExp-emc] " + fmt, args);
    }

    private static final String MCSERVER_CLASS = "net.pitan76.mcpitanlib.midohra.server.MCServer";

    /** ServerState.of 反射句柄（1.1.3 收 MinecraftServer，1.3.3 改收 MCServer，见 AGENTS.md §4.1） */
    private static Method serverStateOf;
    /** 仅 1.3.3 路径需要：MCServer.of(MinecraftServer) 工厂 */
    private static Method mcServerOf;
    private static boolean ofTakesMCServer;
    private static boolean ofResolved;

    /**
     * 跨版本获取 ServerState：直接按编译期签名调 {@code ServerState.of(MinecraftServer)} 在
     * itemalchemy 1.3.3 下会 NoSuchMethodError（签名漂移为 {@code of(MCServer)}），导致绑卡
     * 读写全部静默失败。此处反射适配两版，运行时 itemalchemy 为 1.1.3 或 1.3.3 均可。
     */
    private static ServerState resolveServerState(MinecraftServer server) {
        if (server == null) return null;
        try {
            if (!ofResolved) {
                try {
                    serverStateOf = ServerState.class.getMethod("of", MinecraftServer.class);
                    ofTakesMCServer = false;
                } catch (NoSuchMethodException e) {
                    Class<?> mc = Class.forName(MCSERVER_CLASS);
                    mcServerOf = mc.getMethod("of", MinecraftServer.class);
                    serverStateOf = ServerState.class.getMethod("of", mc);
                    ofTakesMCServer = true;
                }
                ofResolved = true;
            }
            Object arg = ofTakesMCServer ? mcServerOf.invoke(null, server) : server;
            return (ServerState) serverStateOf.invoke(null, arg);
        } catch (Throwable t) {
            log("resolveServerState({}) threw: {}", server, t.toString());
            return null;
        }
    }

    /** 读取玩家 team EMC；玩家无队伍返回 0 */
    public static long getEmc(MinecraftServer server, UUID uuid) {
        if (server == null || uuid == null) return 0L;
        try {
            ServerState state = resolveServerState(server);
            if (state == null) return 0L;
            Optional<TeamState> team = state.getTeamByPlayer(uuid);
            long v = team.map(t -> t.storedEMC).orElse(0L);
            log("getEmc({}) = {} | teamFound={} teams={} players={}",
                    uuid, v, team.isPresent(), state.teams.size(), state.players.size());
            return v;
        } catch (Throwable t) {
            log("getEmc({}) threw: {}", uuid, t.toString());
            return 0L;
        }
    }

    /** 玩家是否已有转换桌队伍（绑卡前置条件：无队伍时卡无法接收/支出 EMC） */
    public static boolean hasTeam(MinecraftServer server, UUID uuid) {
        if (server == null || uuid == null) return false;
        try {
            ServerState state = resolveServerState(server);
            if (state == null) return false;
            boolean found = state.getTeamByPlayer(uuid).isPresent();
            log("hasTeam({}) = {} | teams={} players={}", uuid, found, state.teams.size(), state.players.size());
            return found;
        } catch (Throwable t) {
            log("hasTeam({}) threw: {}", uuid, t.toString());
            return false;
        }
    }

    /**
     * 确保目标玩家已有转换桌队伍；没有则创建默认队伍（0 EMC）。
     *
     * <p>若已有 PlayerState 但指向的 team 已失效（脏数据），复用该 PlayerState 并修正
     * teamID，避免 {@code getPlayer} 用 {@code findFirst} 返回旧记录导致永远找不到。</p>
     *
     * @param name 队伍显示名（用于新建队伍；无名字时用 UUID 截断）
     * @return 是否确保存在队伍
     */
    public static boolean ensureTeam(MinecraftServer server, UUID uuid, String name) {
        if (server == null || uuid == null) return false;
        try {
            ServerState state = resolveServerState(server);
            if (state == null) return false;

            Optional<PlayerState> existing = state.getPlayer(uuid);
            if (existing.isPresent()) {
                if (state.getTeam(existing.get().teamID).isPresent()) {
                    log("ensureTeam({}) already ok | teams={} players={}", uuid, state.teams.size(), state.players.size());
                    return true;
                }
                // 残留 PlayerState 指向失效 team：复用并修正 teamID
                TeamState nt = newTeam(uuid, name);
                state.teams.add(nt);
                existing.get().teamID = nt.teamID;
                state.callMarkDirty();
                log("ensureTeam({}) repaired dangling PlayerState -> {}", uuid, nt.teamID);
                return true;
            }

            // 全新创建
            TeamState nt = newTeam(uuid, name);
            PlayerState ps = new PlayerState();
            ps.playerUUID = uuid;
            ps.teamID = nt.teamID;
            state.teams.add(nt);
            state.players.add(ps);
            state.callMarkDirty();
            log("ensureTeam({}) created team {} | teams={} players={}", uuid, nt.teamID, state.teams.size(), state.players.size());
            return true;
        } catch (Throwable t) {
            log("ensureTeam({}) threw: {}", uuid, t.toString());
            return false;
        }
    }

    private static TeamState newTeam(UUID uuid, String name) {
        TeamState team = new TeamState();
        team.name = (name == null || name.isEmpty()) ? uuid.toString().substring(0, 8) : name;
        team.createdAt = System.currentTimeMillis();
        team.teamID = UUID.randomUUID();
        team.owner = uuid;
        team.storedEMC = 0;
        team.isDefault = true;
        return team;
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
            ServerState state = resolveServerState(server);
            if (state == null) return false;
            Optional<TeamState> team = state.getTeamByPlayer(uuid);
            log("add({}, {}) | teamFound={} teams={} players={}",
                    uuid, amount, team.isPresent(), state.teams.size(), state.players.size());
            if (!team.isPresent()) return false;
            team.get().storedEMC += amount;
            state.callMarkDirty();
            return true;
        } catch (Throwable t) {
            log("add({}, {}) threw: {}", uuid, amount, t.toString());
            return false;
        }
    }

    /** 从玩家 team EMC 减值；余额不足返回 false（不扣减） */
    public static boolean subtract(MinecraftServer server, UUID uuid, long amount) {
        if (server == null || uuid == null || amount <= 0) return false;
        try {
            ServerState state = resolveServerState(server);
            if (state == null) return false;
            Optional<TeamState> team = state.getTeamByPlayer(uuid);
            if (!team.isPresent()) return false;
            TeamState ts = team.get();
            if (ts.storedEMC < amount) return false;
            ts.storedEMC -= amount;
            state.callMarkDirty();
            return true;
        } catch (Throwable t) {
            log("subtract({}, {}) threw: {}", uuid, amount, t.toString());
            return false;
        }
    }
}
