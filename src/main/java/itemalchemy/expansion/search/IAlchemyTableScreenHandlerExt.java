package itemalchemy.expansion.search;

/**
 * 通过 Mixin 接口注入到 {@code AlchemyTableScreenHandler}，暴露筛选模式状态。
 *
 * <p>{@code AlchemyTableScreenHandler} 是上游 Item Alchemy 的类，不能直接修改其字段。
 * 用 Mixin {@code implements} 本接口 + {@code @Unique} 字段实现，外部代码通过 cast 调用。
 * 客户端与服务端各持有自己的 {@code AlchemyTableScreenHandler} 实例，各自的 filterMode 独立。
 * 客户端切换时本地立即生效并通过 {@code filter_mode} 网络包同步服务端。</p>
 */
public interface IAlchemyTableScreenHandlerExt {

    /** 获取当前筛选模式 */
    SearchFilterMode iaexp$getFilterMode();

    /** 设置筛选模式（不触发重新搜索，调用方需自行调用 sortBySearch） */
    void iaexp$setFilterMode(SearchFilterMode mode);

    /** 切换到下一档并返回新值（不触发重新搜索） */
    SearchFilterMode iaexp$cycleFilterMode();
}
