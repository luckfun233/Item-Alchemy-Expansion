package itemalchemy.expansion.client;

import itemalchemy.expansion.ItemAlchemyExpansion;

/**
 * 「重新定价」触发器（客户端 → 服务端 C2S 转发）。
 *
 * <p>玩家在 Cloth Config GUI 把「配方自动定价」从关切换为开并保存时调用。
 * 转发到 {@link SetEmcClientNetwork#sendRepriceCheck()} 发送 C2S 包，
 * 服务端扫描 {@link itemalchemy.expansion.network.PerSaveEmcStore} 候选并回 S2C，
 * 客户端收到后弹出 {@link RepriceConfirmScreen} 让玩家选择是否重新定价。</p>
 */
public final class RepriceTriggerClient {

    private RepriceTriggerClient() {}

    /**
     * 触发重新定价检查：发送 {@code reprice_check} C2S 包到服务端。
     *
     * <p>由 {@code IAExpClothConfigScreen.setSavingRunnable} 在
     * 「自动定价 OFF→ON 且首次（{@code autoPricingRepricePromptShown=false}）」时调用。</p>
     *
     * <p>注：服务端会在已弹过时静默跳过，调用方无需自行判断标志。</p>
     */
    public static void sendRepriceCheck() {
        try {
            SetEmcClientNetwork.sendRepriceCheck();
        } catch (Throwable t) {
            ItemAlchemyExpansion.LOGGER.warn("[IAExp] RepriceTriggerClient.sendRepriceCheck failed: {}",
                    t.toString());
        }
    }
}
