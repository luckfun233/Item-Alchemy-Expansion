package itemalchemy.expansion.search;

/**
 * 转换桌搜索筛选模式（三档循环）。
 *
 * <p>由左侧 GUI 筛选按钮切换，控制 {@code sortBySearch} 阶段对结果的过滤：</p>
 * <ul>
 *   <li>{@link #ALL} — 显示全部匹配（直接物品 + 潜影盒），默认值。</li>
 *   <li>{@link #DIRECT_ONLY} — 仅显示直接拥有的物品（非潜影盒）。</li>
 *   <li>{@link #SHULKER_ONLY} — 仅显示内部含匹配物或自身名匹配的潜影盒。</li>
 * </ul>
 *
 * <p>切换顺序：ALL → DIRECT_ONLY → SHULKER_ONLY → ALL。</p>
 */
public enum SearchFilterMode {
    ALL,
    DIRECT_ONLY,
    SHULKER_ONLY;

    /** 三档循环：返回下一档 */
    public SearchFilterMode next() {
        SearchFilterMode[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
