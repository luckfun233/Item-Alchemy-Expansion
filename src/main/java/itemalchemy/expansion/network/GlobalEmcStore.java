package itemalchemy.expansion.network;

import itemalchemy.expansion.ItemAlchemyExpansion;
import net.pitan76.itemalchemy.EMCManager;

import java.io.File;

/**
 * 全局 EMC 存储：把 EMC 修改持久化到前置模组自带的
 * {@code config/itemalchemy/emc_config.json}，对所有存档生效。
 *
 * <p><b>设计目的</b>：玩家在 SetEmcScreen 选择「所有存档（全局）」时，EMC 修改写入全局配置，
 * 任意存档加载该配置时都会生效。复用前置模组 {@link EMCManager} 的 Config 对象与文件路径，
 * 保证与前置模组的加载逻辑一致。</p>
 *
 * <p><b>实现</b>：
 * <ul>
 *   <li>{@link EMCManager#config} 是前置模组在 {@link EMCManager#init} 时创建的 {@code Config} 对象，
 *       已加载了 {@code emc_config.json} 的内容。本类调用 {@code config.set(itemId, emc)} 更新该对象，
 *       再 {@code config.save(file)} 落盘。</li>
 *   <li>内存 map 已由 {@link SetEmcNetwork#applyOnServer} 调用 {@link EMCManager#set} 更新，
 *       本类只负责持久化到配置文件。</li>
 *   <li>{@link EMCManager#getConfigFile()} 返回 {@code config/itemalchemy/emc_config.json}。</li>
 * </ul>
 * </p>
 *
 * <p><b>线程安全</b>：所有方法在服务端主线程调用（由 {@link SetEmcNetwork#applyOnServer}
 * 通过 {@code server.execute} 调度）。</p>
 */
public final class GlobalEmcStore {

    private GlobalEmcStore() {}

    /**
     * 设置单个物品的全局 EMC：更新前置模组的 Config 对象 + 保存到 {@code emc_config.json}。
     *
     * @param itemId 物品 ID（已规范化，含命名空间）
     * @param emc    新 EMC 值（>= 0）
     */
    public static void set(String itemId, long emc) {
        try {
            // 前置模组的 Config 对象（init 时创建）
            if (EMCManager.config == null) {
                ItemAlchemyExpansion.LOGGER.warn("[IAExp] global emc store: EMCManager.config is null (server not initialized?)");
                return;
            }
            File file = EMCManager.getConfigFile();
            // 更新 Config 对象
            EMCManager.config.set(itemId, emc);
            // 落盘
            EMCManager.config.save(file);
            ItemAlchemyExpansion.debug("[IAExp] global emc override set: {}={} (file={})",
                    itemId, emc, file);
        } catch (Throwable t) {
            ItemAlchemyExpansion.LOGGER.error("[IAExp] global emc store: failed to set {}={}: {}",
                    itemId, emc, t.toString());
        }
    }
}
