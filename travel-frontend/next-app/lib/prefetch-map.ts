/**
 * FE-P1a（20260912 前端专项）：路由→预取键映射纯数据表 + 查询纯函数。
 * PrefetchProvider 空闲调度按当前 pathname 查询本表决定预取集（接线属 FE-P1b）。
 * 键格式与既有消费端一致（takePrefetch 契约零改动）：
 *   itinerary:{page}:{size} ｜ chat:sessions ｜ attractions:{page}:{size}
 */

interface PrefetchRoute {
  /** pathname 匹配规则（usePathname 结果不含 query；/itinerary* 通配列表与详情页） */
  pattern: RegExp;
  /** 命中后应预取的键（不含当前路由自身的首屏键） */
  keys: readonly string[];
}

export const PREFETCH_ROUTES: readonly PrefetchRoute[] = [
  { pattern: /^\/chat$/, keys: ['itinerary:1:8', 'chat:sessions', 'attractions:1:12'] },
  { pattern: /^\/itinerary(\/|$)/, keys: ['chat:sessions', 'attractions:1:12'] },
  { pattern: /^\/attractions$/, keys: ['itinerary:1:8'] },
];

/** 返回 pathname 对应的预取键列表（副本，可安全变更）；未纳管路由返回空数组。 */
export function keysFor(pathname: string): string[] {
  for (const route of PREFETCH_ROUTES) {
    if (route.pattern.test(pathname)) return [...route.keys];
  }
  return [];
}
