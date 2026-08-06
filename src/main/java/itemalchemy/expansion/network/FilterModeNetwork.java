package itemalchemy.expansion.network;

import itemalchemy.expansion.ItemAlchemyExpansion;
import itemalchemy.expansion.search.IAlchemyTableScreenHandlerExt;
import itemalchemy.expansion.search.SearchFilterMode;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.util.Identifier;
import net.pitan76.itemalchemy.gui.screen.AlchemyTableScreenHandler;

/**
 * 「筛选模式切换」网络包：C2S。
 *
 * <p>客户端点击转换桌左侧筛选按钮时发送：<pre>{ filterMode: byte (ordinal 0-2) }</pre></p>
 *
 * <p>服务端接收后：
 * <ol>
 *   <li>校验玩家当前 ScreenHandler 是 {@link AlchemyTableScreenHandler}；</li>
 *   <li>cast 为 {@link IAlchemyTableScreenHandlerExt}，设置 filterMode；</li>
 *   <li>调用 {@code sortBySearch()} 重新排序（会触发本模组 Mixin 的新逻辑）。</li>
 * </ol>
 * </p>
 *
 * <p><b>客户端发送</b>在 {@code FilterModeClientNetwork}（client source set），
 * 因为 {@code ClientPlayNetworking} 是客户端专属 API。</p>
 *
 * <p><b>安全</b>：filterMode ordinal 限制 0~2，超出范围忽略。无权限要求
 * （仅影响玩家自己的转换桌显示，不影响他人或世界数据）。</p>
 */
public final class FilterModeNetwork {

    /** C2S 包 id：{@code itemalchemy-expansion:filter_mode} */
    public static final Identifier FILTER_MODE_ID = new Identifier(ItemAlchemyExpansion.MOD_ID, "filter_mode");

    private FilterModeNetwork() {}

    /** 服务端注册接收器。在 {@code onInitialize}（服务端）中调用。 */
    public static void registerServer() {
        ServerPlayNetworking.registerGlobalReceiver(FILTER_MODE_ID, (server, player, handler, buf, responseSender) -> {
            int ordinal = buf.readByte();
            SearchFilterMode newMode;
            try {
                newMode = SearchFilterMode.values()[ordinal];
            } catch (ArrayIndexOutOfBoundsException ex) {
                return; // 非法 ordinal，忽略
            }

            server.execute(() -> {
                if (!(player.currentScreenHandler instanceof AlchemyTableScreenHandler)) return;
                AlchemyTableScreenHandler screenHandler = (AlchemyTableScreenHandler) player.currentScreenHandler;
                if (!(screenHandler instanceof IAlchemyTableScreenHandlerExt)) return;
                IAlchemyTableScreenHandlerExt ext = (IAlchemyTableScreenHandlerExt) screenHandler;
                ext.iaexp$setFilterMode(newMode);
                // 重置到第一页并重新搜索
                screenHandler.index = 0;
                screenHandler.sortBySearch();
            });
        });
    }
}
