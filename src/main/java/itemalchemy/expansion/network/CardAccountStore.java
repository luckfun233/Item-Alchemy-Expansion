package itemalchemy.expansion.network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import itemalchemy.expansion.ItemAlchemyExpansion;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * EMC 卡「关联共享账户」存储。
 *
 * <p>两张卡通过制卡台关联后，共享同一 {@code link_group}（UUID）。共享余额存于服务端，
 * key = link_group UUID，value = 共享 EMC。卡可分散在不同玩家/容器，读同一余额，
 * 符合「共享账号」语义：往 A 充值两者同增，从 B 取款两者同减。</p>
 *
 * <p>持久化到 {@code <world>/itemalchemy_expansion_card_accounts.json}，随存档走。
 * 未关联的普通卡不走本类，余额仍在卡 NBT（{@code EmcCardItem.STORED_EMC_KEY}）。</p>
 *
 * <p><b>线程安全</b>：所有方法在服务端主线程调用。</p>
 */
public final class CardAccountStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Type MAP_TYPE = new TypeToken<LinkedHashMap<String, Long>>(){}.getType();
    private static final String FILE_NAME = "itemalchemy_expansion_card_accounts.json";

    /** 运行时共享账户 map：link_group UUID -> 共享 EMC */
    private static final Map<String, Long> accounts = new LinkedHashMap<>();

    /** 脏标记：有变更待写盘 */
    private static boolean dirty = false;

    /** 上次刷盘时的服务器 tick，用于限制写盘频率 */
    private static long lastFlushTick = Long.MIN_VALUE;

    /** 刷盘间隔（tick）：20 tick/秒 → 最坏每 5 秒一次磁盘写 */
    private static final long FLUSH_INTERVAL_TICKS = 100L;

    private CardAccountStore() {}

    /** 返回共享账户存档文件路径 */
    public static Path getFile(MinecraftServer server) {
        return server.getSavePath(WorldSavePath.ROOT).resolve(FILE_NAME);
    }

    /** 查询关联组的共享余额；组不存在返回 0 */
    public static long get(String groupId) {
        if (groupId == null || groupId.isEmpty()) return 0L;
        Long v = accounts.get(groupId);
        return v == null ? 0L : v;
    }

    /** 是否存在该关联组 */
    public static boolean has(String groupId) {
        return groupId != null && !groupId.isEmpty() && accounts.containsKey(groupId);
    }

    /** 向关联组加 EMC（clamp 到 >= 0） */
    public static void add(MinecraftServer server, String groupId, long amount) {
        if (groupId == null || groupId.isEmpty() || amount <= 0) return;
        accounts.put(groupId, Math.max(0L, get(groupId) + amount));
        markDirty(server);
    }

    /** 从关联组减 EMC；余额不足则拒绝（返回 false，不扣减） */
    public static boolean subtract(MinecraftServer server, String groupId, long amount) {
        if (groupId == null || groupId.isEmpty() || amount <= 0) return false;
        long cur = get(groupId);
        if (cur < amount) return false;
        accounts.put(groupId, cur - amount);
        markDirty(server);
        return true;
    }

    /** 设置关联组余额为指定值（clamp 到 >= 0） */
    public static void set(MinecraftServer server, String groupId, long emc) {
        if (groupId == null || groupId.isEmpty()) return;
        accounts.put(groupId, Math.max(0L, emc));
        markDirty(server);
    }

    /** 删除关联组（解散关联时调用） */
    public static void remove(MinecraftServer server, String groupId) {
        if (groupId == null || groupId.isEmpty()) return;
        accounts.remove(groupId);
        markDirty(server);
    }

    /** 生成一个新的关联组 ID */
    public static String newGroupId() {
        return UUID.randomUUID().toString();
    }

    /** 服务端启动时加载存档 */
    public static void load(MinecraftServer server) {
        accounts.clear();
        dirty = false;
        lastFlushTick = Long.MIN_VALUE;
        Path file = getFile(server);
        if (!Files.exists(file)) {
            ItemAlchemyExpansion.debug("[IAExp] card accounts: none (file not found)");
            return;
        }
        try {
            String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            Map<String, Long> map = GSON.fromJson(content, MAP_TYPE);
            if (map != null) accounts.putAll(map);
            ItemAlchemyExpansion.debug("[IAExp] card accounts loaded: {} groups", accounts.size());
        } catch (Throwable t) {
            ItemAlchemyExpansion.LOGGER.warn("[IAExp] Failed to read card accounts ({}): {}", file, t.toString());
        }
    }

    /**
     * 写盘（无条件执行）。
     *
     * <p><b>必须</b>在账户为空时也写文件：若最后一个关联组被解散后跳过写盘，
     * 旧文件会在重启后被重新加载，已解散的组携带旧余额「复活」，造成 EMC 复制。</p>
     */
    public static void save(MinecraftServer server) {
        dirty = false;
        Path file = getFile(server);
        try {
            Files.createDirectories(file.getParent());
            String header = "// Item Alchemy Expansion EMC 卡关联共享账户。key = link_group UUID。\n";
            Files.write(file, (header + GSON.toJson(accounts)).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            ItemAlchemyExpansion.LOGGER.error("[IAExp] Failed to write card accounts ({}): {}", file, e.getMessage());
        }
    }

    /** 每 tick 由 END_SERVER_TICK 调用：脏数据按间隔刷盘，避免红石自动化下每 tick 磁盘 I/O */
    public static void onServerTick(MinecraftServer server) {
        if (!dirty) return;
        long now = server.getTicks();
        if (lastFlushTick != Long.MIN_VALUE && now - lastFlushTick < FLUSH_INTERVAL_TICKS) return;
        lastFlushTick = now;
        save(server);
    }

    private static void markDirty(MinecraftServer server) {
        dirty = true;
    }
}